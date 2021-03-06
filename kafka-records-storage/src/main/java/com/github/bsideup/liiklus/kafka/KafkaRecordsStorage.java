package com.github.bsideup.liiklus.kafka;

import com.github.bsideup.liiklus.positions.PositionsStorage;
import com.github.bsideup.liiklus.records.RecordsStorage;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteBufferDeserializer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverPartition;
import reactor.kafka.receiver.internals.DefaultKafkaReceiver;
import reactor.kafka.receiver.internals.DefaultKafkaReceiverAccessor;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;

@RequiredArgsConstructor
@FieldDefaults(makeFinal = true)
@Slf4j
public class KafkaRecordsStorage implements RecordsStorage {

    String bootstrapServers;

    PositionsStorage positionsStorage;

    KafkaSender<ByteBuffer, ByteBuffer> sender;

    @Override
    public CompletionStage<Void> publish(String topic, ByteBuffer key, ByteBuffer value) {
        return sender.send(Mono.just(SenderRecord.create(new ProducerRecord<>(topic, key, value), 1)))
                .single()
                .flatMap(it -> it.exception() != null ? Mono.error(it.exception()) : Mono.<Void>empty())
                .toFuture();
    }

    @Override
    public Subscription subscribe(String topic, String groupId, Optional<String> autoOffsetReset) {
        val props = new HashMap<String, Object>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        autoOffsetReset.ifPresent(it -> props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, it));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteBufferDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteBufferDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "0");

        return () -> Flux.create(assignmentsSink -> {
            val revocations = new ConcurrentHashMap<Integer, UnicastProcessor<TopicPartition>>();

            val receiverRef = new AtomicReference<DefaultKafkaReceiver<ByteBuffer, ByteBuffer>>();
            val recordsFluxRef = new AtomicReference<Flux<Record>>();

            val receiverOptions = ReceiverOptions.<ByteBuffer, ByteBuffer>create(props)
                    .subscription(singletonList(topic))
                    .addRevokeListener(partitions -> {
                        for (val partition : partitions) {
                            val topicPartition = partition.topicPartition();
                            revocations.get(topicPartition.partition()).onNext(topicPartition);
                        }
                    })
                    .addAssignListener(partitions -> {
                        val offsets = Mono
                                .fromCompletionStage(
                                        positionsStorage
                                                .fetch(
                                                        topic,
                                                        groupId,
                                                        partitions.stream().map(it -> it.topicPartition().partition()).collect(Collectors.toSet()),
                                                        partitions.stream().collect(Collectors.toMap(
                                                                it -> it.topicPartition().partition(),
                                                                ReceiverPartition::position
                                                        ))
                                                )
                                )
                                .block(Duration.ofSeconds(10));

                        val kafkaReceiver = receiverRef.get();
                        val recordFlux = recordsFluxRef.get();

                        for (val partition : partitions) {
                            DefaultKafkaReceiverAccessor.pause(kafkaReceiver, partition.topicPartition());

                            val lastKnownPosition = offsets.get(partition.topicPartition().partition());
                            if (lastKnownPosition != null && lastKnownPosition > 0) {
                                partition.seek(lastKnownPosition + 1);
                            }

                            val topicPartition = partition.topicPartition();
                            val partitionList = Arrays.asList(topicPartition);
                            val partitionNum = topicPartition.partition();

                            val requests = new AtomicLong();

                            val revocationsProcessor = UnicastProcessor.<TopicPartition>create();
                            revocations.put(partitionNum, revocationsProcessor);

                            assignmentsSink.next(
                                    new DelegatingGroupedPublisher<>(
                                            partitionNum,
                                            recordFlux
                                                    .filter(it -> it.getPartition() == partitionNum)
                                                    .delayUntil(record -> {
                                                        if (requests.decrementAndGet() <= 0) {
                                                            return kafkaReceiver.doOnConsumer(consumer -> {
                                                                consumer.pause(partitionList);
                                                                return true;
                                                            });
                                                        } else {
                                                            return Mono.empty();
                                                        }
                                                    })
                                                    .doOnRequest(requested -> {
                                                        if (requests.addAndGet(requested) > 0) {
                                                            DefaultKafkaReceiverAccessor.resume(kafkaReceiver, topicPartition);
                                                        }
                                                    })
                                                    .takeUntilOther(revocationsProcessor)
                                    )
                            );
                        }
                    });

            val kafkaReceiver = (DefaultKafkaReceiver<ByteBuffer, ByteBuffer>) KafkaReceiver.create(receiverOptions);
            receiverRef.set(kafkaReceiver);

            recordsFluxRef.set(
                    Flux
                            .defer(kafkaReceiver::receive)
                            .map(record -> new Record(
                                    record.key(),
                                    record.value(),
                                    Instant.ofEpochMilli(record.timestamp()),
                                    record.partition(),
                                    record.offset()
                            ))
                            .share()
            );

            val disposable = recordsFluxRef.get().subscribe(
                    __ -> {},
                    assignmentsSink::error,
                    assignmentsSink::complete
            );

            assignmentsSink.onDispose(() -> {
                disposable.dispose();
                DefaultKafkaReceiverAccessor.close(kafkaReceiver);
            });
        });
    }
}
