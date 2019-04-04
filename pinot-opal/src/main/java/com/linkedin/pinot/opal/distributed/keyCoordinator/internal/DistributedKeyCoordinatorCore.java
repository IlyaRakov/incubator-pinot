/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.pinot.opal.distributed.keyCoordinator.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import com.linkedin.pinot.opal.common.RpcQueue.KafkaQueueConsumer;
import com.linkedin.pinot.opal.common.RpcQueue.KafkaQueueProducer;
import com.linkedin.pinot.opal.common.RpcQueue.ProduceTask;
import com.linkedin.pinot.opal.common.keyValueStore.ByteArrayWrapper;
import com.linkedin.pinot.opal.common.keyValueStore.KeyValueStoreDB;
import com.linkedin.pinot.opal.common.keyValueStore.KeyValueStoreTable;
import com.linkedin.pinot.opal.common.keyValueStore.RocksDBKeyValueStoreDB;
import com.linkedin.pinot.opal.common.messages.KeyCoordinatorMessageContext;
import com.linkedin.pinot.opal.common.messages.KeyCoordinatorQueueMsg;
import com.linkedin.pinot.opal.common.messages.LogCoordinatorMessage;
import com.linkedin.pinot.opal.common.messages.LogEventType;
import com.linkedin.pinot.opal.common.updateStrategy.MessageResolveStrategy;
import com.linkedin.pinot.opal.common.utils.CommonUtils;
import com.linkedin.pinot.opal.common.utils.PartitionIdMapper;
import com.linkedin.pinot.opal.common.utils.State;
import com.linkedin.pinot.opal.distributed.keyCoordinator.common.DistributedCommonUtils;
import com.linkedin.pinot.opal.distributed.keyCoordinator.common.OffsetInfo;
import com.linkedin.pinot.opal.distributed.keyCoordinator.starter.KeyCoordinatorConf;
import org.apache.commons.configuration.Configuration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


public class DistributedKeyCoordinatorCore {
  private static final Logger LOGGER = LoggerFactory.getLogger(DistributedKeyCoordinatorCore.class);
  private static final long TERMINATION_WAIT_MS = 10000;

  private KeyCoordinatorConf _conf;
  private KafkaQueueProducer<Integer, LogCoordinatorMessage> _outputKafkaProducer;
  private KafkaQueueConsumer<Integer, KeyCoordinatorQueueMsg> _inputKafkaConsumer;
  private MessageResolveStrategy _messageResolveStrategy;
  private KeyValueStoreDB<ByteArrayWrapper, KeyCoordinatorMessageContext> _keyValueStoreDB;
  private ExecutorService _messageProcessThread;
  private ExecutorService _consumerThread;
  private BlockingQueue<ConsumerRecord<Integer, KeyCoordinatorQueueMsg>> _consumerRecordBlockingQueue;
  private PartitionIdMapper _partitionIdMapper;
  private volatile State _state = State.SHUTDOWN;

  private int fetchMsgDelayMs;
  private int fetchMsgMaxDelayMs;
  private int fetchMsgMaxCount;


  public DistributedKeyCoordinatorCore() {}

  public void init(KeyCoordinatorConf conf, KafkaQueueProducer<Integer, LogCoordinatorMessage> keyCoordinatorProducer,
                   KafkaQueueConsumer<Integer, KeyCoordinatorQueueMsg> keyCoordinatorConsumer,
                   MessageResolveStrategy messageResolveStrategy) {
    init(conf, keyCoordinatorProducer, keyCoordinatorConsumer, messageResolveStrategy,
        getKeyValueStore(conf.subset(KeyCoordinatorConf.KEY_COORDINATOR_KV_STORE)), Executors.newSingleThreadExecutor());
  }

  @VisibleForTesting
  public void init(KeyCoordinatorConf conf, KafkaQueueProducer<Integer, LogCoordinatorMessage> keyCoordinatorProducer,
                   KafkaQueueConsumer<Integer, KeyCoordinatorQueueMsg> keyCoordinatorConsumer,
                   MessageResolveStrategy messageResolveStrategy,
                   KeyValueStoreDB<ByteArrayWrapper, KeyCoordinatorMessageContext> keyValueStoreDB,
                   ExecutorService coreThread) {
    CommonUtils.printConfiguration(conf, "distributed key coordinator core");
    Preconditions.checkState(_state == State.SHUTDOWN, "can only init if it is not running yet");
    _conf = conf;
    _outputKafkaProducer = keyCoordinatorProducer;
    _inputKafkaConsumer = keyCoordinatorConsumer;
    _messageResolveStrategy = messageResolveStrategy;
    _keyValueStoreDB = keyValueStoreDB;
    _messageProcessThread = coreThread;
    _partitionIdMapper = new PartitionIdMapper();

    _consumerThread = Executors.newSingleThreadExecutor();
    _consumerRecordBlockingQueue = new ArrayBlockingQueue<>(_conf.getConsumerBlockingQueueSize());

    fetchMsgDelayMs = conf.getInt(KeyCoordinatorConf.FETCH_MSG_DELAY_MS,
        KeyCoordinatorConf.FETCH_MSG_DELAY_MS_DEFAULT);
    fetchMsgMaxDelayMs = conf.getInt(KeyCoordinatorConf.FETCH_MSG_MAX_DELAY_MS,
        KeyCoordinatorConf.FETCH_MSG_MAX_DELAY_MS_DEFAULT);
    fetchMsgMaxCount = conf.getInt(KeyCoordinatorConf.FETCH_MSG_MAX_BATCH_SIZE,
        KeyCoordinatorConf.FETCH_MSG_MAX_BATCH_SIZE_DEFAULT);
    LOGGER.info("starting with fetch delay: {} max delay: {}, fetch max count: {}", fetchMsgDelayMs, fetchMsgMaxDelayMs,
        fetchMsgMaxCount);

    _state = State.INIT;
  }

  public void start() {
    Preconditions.checkState(_state == State.INIT, "key coordinate is not in correct state");
    LOGGER.info("starting key coordinator message process loop");
    _state = State.RUNNING;
    _consumerThread.submit(this::consumerIngestLoop);
    _messageProcessThread.submit(this::messageProcessLoop);
  }

  private void consumerIngestLoop() {
    while (_state == State.RUNNING) {
      try {
        ConsumerRecords<Integer, KeyCoordinatorQueueMsg> records = _inputKafkaConsumer.getConsumerRecords(fetchMsgMaxDelayMs,
            TimeUnit.MILLISECONDS);
        records.forEach(c -> {
          try {
            _consumerRecordBlockingQueue.put(c);
          } catch (InterruptedException e) {
            LOGGER.warn("exception while trying to put message to queue", e);
          }
        });
      } catch (Exception ex) {
        LOGGER.error("encountered exception in consumer ingest loop, will retry", ex);
      }
    }
    LOGGER.info("exiting consumer ingest loop");
  }

  private void messageProcessLoop() {
    try {
      long deadline = System.currentTimeMillis() + fetchMsgMaxDelayMs;
      while (_state == State.RUNNING) {
        LOGGER.info("starting new loop");
        long start = System.currentTimeMillis();
        // process message when we got max message count or reach max delay ms
        MessageAndOffset<KeyCoordinatorQueueMsg> messageAndOffset = getMessagesFromQueue(_consumerRecordBlockingQueue, deadline);
        List<KeyCoordinatorQueueMsg> messages = messageAndOffset.getMessages();
        deadline = System.currentTimeMillis() + fetchMsgMaxDelayMs;

        if (messages.size() > 0) {
          LOGGER.info("fetch {} messages in {} ms", messages.size(), System.currentTimeMillis() - start);
          processMessages(messages);
          _inputKafkaConsumer.ackOffset(messageAndOffset.getOffsetInfo());
          LOGGER.info("kc message processed {} messages in this loop for {} ms", messages.size(),
              System.currentTimeMillis() - start);
        } else {
          LOGGER.info("no message received in the current loop");
        }
      }
    } catch (Exception ex) {
      LOGGER.warn("key coordinator is exiting due to exception", ex);
    } finally {
      _state = State.SHUTTING_DOWN;
      LOGGER.info("exiting key coordinator loop");
    }
    LOGGER.info("existing key coordinator core /procthread");
  }

  private MessageAndOffset<KeyCoordinatorQueueMsg> getMessagesFromQueue(
      BlockingQueue<ConsumerRecord<Integer, KeyCoordinatorQueueMsg>> queue, long deadline) {
    List<ConsumerRecord<Integer, KeyCoordinatorQueueMsg>> buffer = new ArrayList<>(fetchMsgMaxCount * 2);
    while(System.currentTimeMillis() < deadline && buffer.size() < fetchMsgMaxCount) {
      queue.drainTo(buffer, fetchMsgMaxCount - buffer.size());
      if (buffer.size() < fetchMsgMaxCount) {
        Uninterruptibles.sleepUninterruptibly(fetchMsgDelayMs, TimeUnit.MILLISECONDS);
      }
    }
    OffsetInfo offsetInfo = new OffsetInfo();
    List<KeyCoordinatorQueueMsg> messages = new ArrayList<>(buffer.size());
    for (ConsumerRecord<Integer, KeyCoordinatorQueueMsg> record: buffer) {
      offsetInfo.updateOffsetIfNecessary(record);
      messages.add(record.value());
    }
    return new MessageAndOffset<>(messages, offsetInfo);
  }

  public void stop() {
    _state = State.SHUTTING_DOWN;
    _messageProcessThread.shutdown();
    _consumerThread.shutdownNow();
    try {
      _consumerThread.awaitTermination(TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS);
      _messageProcessThread.awaitTermination(TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      LOGGER.error("failed to wait for key coordinator thread to shutdown", ex);
    }
    _consumerThread.shutdownNow();
    _messageProcessThread.shutdownNow();
    _state = State.SHUTDOWN;
  }

  public State getState() {
    return _state;
  }

  private void processMessages(List<KeyCoordinatorQueueMsg> messages) {
    Map<String, List<KeyCoordinatorQueueMsg>> topicMsgMap = new HashMap<>();
    for (KeyCoordinatorQueueMsg msg: messages) {
      topicMsgMap.computeIfAbsent(msg.getPinotTable(), t -> new ArrayList<>()).add(msg);
    }
    for (Map.Entry<String, List<KeyCoordinatorQueueMsg>> entry: topicMsgMap.entrySet()) {
      processMessagesForTable(entry.getKey(), entry.getValue());
    }
  }

  private void processMessagesForTable(String tableName, List<KeyCoordinatorQueueMsg> msgList) {
    try {
      long start = System.currentTimeMillis();
      int deleteTaskCount = 0;
      if (msgList == null || msgList.size() == 0) {
        LOGGER.warn("trying to process topic message with empty list {}", tableName);
        return;
      }
      KeyValueStoreTable<ByteArrayWrapper, KeyCoordinatorMessageContext> table = _keyValueStoreDB.getTable(tableName);

      List<ProduceTask<Integer, LogCoordinatorMessage>> tasks = new ArrayList<>();
      // get a set of unique primary key and retrieve its corresponding value in kv store
      Set<ByteArrayWrapper> primaryKeys = msgList.stream().map(msg -> new ByteArrayWrapper(msg.getKey()))
          .collect(Collectors.toCollection(HashSet::new));
      Map<ByteArrayWrapper, KeyCoordinatorMessageContext> primaryKeyToValueMap = new HashMap<>(table.multiGet(new ArrayList<>(primaryKeys)));
      LOGGER.info("input keys got {} results from kv store in {} ms", primaryKeyToValueMap.size(), System.currentTimeMillis() - start);
      start = System.currentTimeMillis();

      for (KeyCoordinatorQueueMsg msg: msgList) {
        KeyCoordinatorMessageContext currentContext = msg.getContext();
        ByteArrayWrapper key = new ByteArrayWrapper(msg.getKey());
        boolean isDuplicatedInput = false;
        if (primaryKeyToValueMap.containsKey(key)) {
          deleteTaskCount++;
          // key conflicts, should resolve which one to delete
          KeyCoordinatorMessageContext oldContext = primaryKeyToValueMap.get(key);
          if (!oldContext.equals(currentContext)) {
            // message are equals, it is from another replica and should skip
            if (_messageResolveStrategy.shouldDeleteFirstMessage(oldContext, currentContext)) {
              // the existing message we have is older than the message we just processed, create delete for it
              tasks.add(createMessageToLogCoordinator(tableName, oldContext.getSegmentName(), oldContext.getKafkaOffset(),
                  currentContext.getKafkaOffset(), LogEventType.DELETE));
              // update the local cache to the latest message, so message within the same batch can override each other
              primaryKeyToValueMap.put(key, currentContext);
            } else {
              // the new message is older than the existing message

              // check if the input message is older than our existing message
              if (currentContext.getKafkaOffset() <= oldContext.getKafkaOffset()) {
                isDuplicatedInput = true;
              } else {
                tasks.add(createMessageToLogCoordinator(tableName, currentContext.getSegmentName(), currentContext.getKafkaOffset(),
                    currentContext.getKafkaOffset(), LogEventType.DELETE));
              }
            }
          }
        } else {
          // no key in the existing map, adding this key to the running set
          primaryKeyToValueMap.put(key, currentContext);
        }
        // always create a insert event for validFrom if is not a duplicated input
        if (!isDuplicatedInput) {
          tasks.add(createMessageToLogCoordinator(tableName, currentContext.getSegmentName(), currentContext.getKafkaOffset(),
              currentContext.getKafkaOffset(), LogEventType.INSERT));
        }
      }
      LOGGER.info("processed all messages in {} ms", System.currentTimeMillis() - start);
      LOGGER.info("sending {} tasks including {} deletion to log coordinator queue", tasks.size(), deleteTaskCount);
      long startTime = System.currentTimeMillis();
      List<ProduceTask<Integer, LogCoordinatorMessage>> failedTasks = sendMessagesToLogCoordinator(tasks, 10, TimeUnit.SECONDS);
      LOGGER.info("send to producer take {} ms and {} failed", System.currentTimeMillis() - startTime, failedTasks.size());

      start = System.currentTimeMillis();
      // update kv store
      table.multiPut(primaryKeyToValueMap);
      LOGGER.info("updated {} message to key value store in {} ms", primaryKeyToValueMap.size(), System.currentTimeMillis() - start);
    } catch (IOException e) {
      throw new RuntimeException("failed to interact with rocksdb", e);
    } catch (RuntimeException e) {
      throw new RuntimeException("failed to interact with key value store", e);
    }
  }

  public ProduceTask<Integer, LogCoordinatorMessage> createMessageToLogCoordinator(String tableName, String segmentName,
                                                                                   long oldKafkaOffset, long value,
                                                                                   LogEventType eventType) {
    return new ProduceTask<>(DistributedCommonUtils.getKafkaTopicFromTableName(tableName),
        _partitionIdMapper.getPartitionFromLLRealtimeSegment(segmentName),
        new LogCoordinatorMessage(segmentName, oldKafkaOffset, value, eventType));
  }

  private List<ProduceTask<Integer, LogCoordinatorMessage>> sendMessagesToLogCoordinator(
      List<ProduceTask<Integer, LogCoordinatorMessage>> tasks, long timeout, TimeUnit timeUnit) {

    // send all and wait for result, batch for better perf
    CountDownLatch countDownLatch = new CountDownLatch(tasks.size());
    tasks.forEach(t -> t.setCountDownLatch(countDownLatch));
    _outputKafkaProducer.batchProduce(tasks);
    _outputKafkaProducer.flush();
    try {
      boolean allFinished = countDownLatch.await(timeout, timeUnit);
      if (allFinished) {
        return new ArrayList<>();
      } else {
        return tasks.stream().filter(t -> !t.isSucceed()).collect(Collectors.toList());
      }
    } catch (InterruptedException e) {
      throw new RuntimeException("encountered run time exception while waiting for the loop to finish");
    }
  }

  protected KeyValueStoreDB<ByteArrayWrapper, KeyCoordinatorMessageContext> getKeyValueStore(Configuration conf) {
    RocksDBKeyValueStoreDB keyValueStore = new RocksDBKeyValueStoreDB();
    keyValueStore.init(conf);
    return keyValueStore;
  }

  private static class MessageAndOffset<K> {
    private List<K> _messages;
    private OffsetInfo _offsetInfo;

    public MessageAndOffset(List<K> messages, OffsetInfo offsetInfo) {
      _messages = messages;
      _offsetInfo  = offsetInfo;
    }

    public List<K> getMessages() {
      return _messages;
    }

    public OffsetInfo getOffsetInfo() {
      return _offsetInfo;
    }
  }
}