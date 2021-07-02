/*******************************************************************************
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.hono.tests.commandrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import javax.security.auth.x500.X500Principal;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.eclipse.hono.client.kafka.HonoTopic;
import org.eclipse.hono.client.kafka.KafkaProducerFactory;
import org.eclipse.hono.client.kafka.KafkaRecordHelper;
import org.eclipse.hono.client.kafka.consumer.HonoKafkaConsumer;
import org.eclipse.hono.client.kafka.consumer.KafkaConsumerConfigProperties;
import org.eclipse.hono.client.registry.TenantClient;
import org.eclipse.hono.commandrouter.CommandTargetMapper;
import org.eclipse.hono.commandrouter.impl.kafka.KafkaBasedCommandConsumerFactoryImpl;
import org.eclipse.hono.test.TracingMockSupport;
import org.eclipse.hono.tests.AssumeMessagingSystem;
import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.util.DeviceConnectionConstants;
import org.eclipse.hono.util.Lifecycle;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.MessagingType;
import org.eclipse.hono.util.TenantObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.admin.KafkaAdminClient;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.KafkaHeader;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;

/**
 * Test cases verifying the behavior of {@link KafkaBasedCommandConsumerFactoryImpl}.
 * <p>
 * To run this on a specific Kafka cluster instance, set the
 * {@value IntegrationTestSupport#PROPERTY_DOWNSTREAM_BOOTSTRAP_SERVERS} system property,
 * e.g. <code>-Ddownstream.bootstrap.servers="PLAINTEXT://localhost:9092"</code>.
 */
@ExtendWith(VertxExtension.class)
@AssumeMessagingSystem(type = MessagingType.kafka)
public class KafkaBasedCommandConsumerFactoryImplIT {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaBasedCommandConsumerFactoryImplIT.class);

    private static Vertx vertx;
    private static KafkaAdminClient adminClient;
    private static KafkaProducer<String, Buffer> kafkaProducer;

    private final Set<String> topicsToDeleteAfterTest = new HashSet<>();
    private final List<Lifecycle> componentsToStopAfterTest = new ArrayList<>();
    private final String adapterInstanceId = "myAdapterInstanceId_" + UUID.randomUUID();
    private final String commandRouterGroupId = "cmdRouter_" + UUID.randomUUID();

    /**
     * Sets up fixture.
     */
    @BeforeAll
    public static void init() {
        vertx = Vertx.vertx();

        final Map<String, String> adminClientConfig = IntegrationTestSupport.getKafkaAdminClientConfig()
                .getAdminClientConfig("test");
        adminClient = KafkaAdminClient.create(vertx, adminClientConfig);
        final Map<String, String> producerConfig = IntegrationTestSupport.getKafkaProducerConfig()
                .getProducerConfig("test");
        kafkaProducer = KafkaProducer.create(vertx, producerConfig);
    }

    /**
     * Cleans up fixture.
     *
     * @param ctx The vert.x test context.
     */
    @AfterAll
    public static void shutDown(final VertxTestContext ctx) {
        final Promise<Void> producerClosePromise = Promise.promise();
        kafkaProducer.close(producerClosePromise);
        producerClosePromise.future()
                .onComplete(ar -> {
                    adminClient.close();
                    adminClient = null;
                    kafkaProducer = null;
                    vertx.close();
                    vertx = null;
                })
                .onComplete(ctx.completing());
    }

    /**
     * Closes and removes resources created during the test.
     *
     * @param ctx The vert.x test context.
     */
    @AfterEach
    void cleanupAfterTest(final VertxTestContext ctx) {
        @SuppressWarnings("rawtypes")
        final List<Future> stopFutures = new ArrayList<>();
        componentsToStopAfterTest.forEach(component -> stopFutures.add(component.stop()));
        componentsToStopAfterTest.clear();
        CompositeFuture.join(stopFutures)
                .onComplete(f -> {
                    final Promise<Void> topicsDeletedPromise = Promise.promise();
                    adminClient.deleteTopics(new ArrayList<>(topicsToDeleteAfterTest), topicsDeletedPromise);
                    topicsDeletedPromise.future()
                            .recover(thr -> {
                                LOG.info("error deleting topics", thr);
                                return Future.succeededFuture();
                            }).onComplete(ar -> {
                                topicsToDeleteAfterTest.clear();
                                ctx.completeNow();
                            });
                });
    }

    /**
     * Verifies that records, published on the tenant-specific Kafka command topic, get received by
     * the consumer created by the factory and get forwarded on the internal command topic in the
     * same order they were published.
     *
     * @param ctx The vert.x test context.
     * @throws InterruptedException if test execution gets interrupted.
     */
    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    public void testCommandsGetForwardedInIncomingOrder(final VertxTestContext ctx) throws InterruptedException {

        final String tenantId = "tenant_" + UUID.randomUUID();
        final VertxTestContext setup = new VertxTestContext();

        final int numTestCommands = 10;
        final CountDownLatch allRecordsReceivedLatch = new CountDownLatch(numTestCommands);
        final List<String> receivedCommandSubjects = new ArrayList<>();
        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler = record -> {
            LOG.trace("received {}", record);
            receivedCommandSubjects.add(KafkaRecordHelper
                    .getHeaderValue(record.headers(), MessageHelper.SYS_PROPERTY_SUBJECT, String.class).orElse(""));
            allRecordsReceivedLatch.countDown();
        };
        final LinkedList<Promise<Void>> completionPromisesQueue = new LinkedList<>();
        // don't let getting the target adapter instance finish immediately
        // - let the futures complete in the reverse order
        final Supplier<Future<Void>> targetAdapterInstanceGetterCompletionFutureSupplier = () -> {
            final Promise<Void> resultPromise = Promise.promise();
            completionPromisesQueue.addFirst(resultPromise);
            // complete all promises in reverse order when processing the last command
            if (completionPromisesQueue.size() == numTestCommands) {
                completionPromisesQueue.forEach(Promise::complete);
            }
            return resultPromise.future();
        };

        final Context vertxContext = vertx.getOrCreateContext();
        vertxContext.runOnContext(v0 -> {
            final HonoKafkaConsumer internalConsumer = getInternalCommandConsumer(recordHandler);
            final KafkaBasedCommandConsumerFactoryImpl consumerFactory = getKafkaBasedCommandConsumerFactory(
                    targetAdapterInstanceGetterCompletionFutureSupplier);
            CompositeFuture.join(internalConsumer.start(), consumerFactory.start())
                    .compose(f -> createCommandConsumer(tenantId, consumerFactory))
                    .onComplete(setup.completing());
        });

        assertThat(setup.awaitCompletion(IntegrationTestSupport.getTestSetupTimeout(), TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
            return;
        }
        LOG.debug("command consumer started");

        final List<String> sentCommandSubjects = new ArrayList<>();
        IntStream.range(0, numTestCommands).forEach(i -> {
            final String subject = "cmd_" + i;
            sentCommandSubjects.add(subject);
            sendOneWayCommand(tenantId, "myDeviceId", subject);
        });

        if (!allRecordsReceivedLatch.await(8, TimeUnit.SECONDS)) {
            ctx.failNow(new AssertionError(String.format("did not receive %d out of %d expected messages after 8s",
                    allRecordsReceivedLatch.getCount(), numTestCommands)));
        } else {
            ctx.verify(() -> {
                assertThat(receivedCommandSubjects).isEqualTo(sentCommandSubjects);
            });
            ctx.completeNow();
        }
    }

    /**
     * Verifies that records, published on the tenant-specific Kafka command topic, get received
     * and forwarded by consumers created by factory instances even if one factory and its contained
     * consumer gets closed in the middle of processing some of the commands.
     *
     * @param ctx The vert.x test context.
     * @throws InterruptedException if test execution gets interrupted.
     */
    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    public void testCommandsGetForwardedIfOneConsumerInstanceGetsClosed(final VertxTestContext ctx) throws InterruptedException {

        final String tenantId = "tenant_" + UUID.randomUUID();
        final VertxTestContext setup = new VertxTestContext();

        // Scenario to test:
        // - first command gets sent, forwarded and received without any imposed delay
        // - second command gets sent, received by the factory consumer instance; processing gets blocked
        //   while trying to get the target adapter instance
        // - for the rest of the commands, retrieval of the target adapter instance is successful, but they won't
        //   get forwarded until processing of the second command is finished
        // - now the factory consumer gets closed and a new factory/consumer gets started; at that point
        //   also the processing of the second command gets finished
        //
        // Expected outcome:
        // - processing of the second command and all following commands by the first consumer gets aborted, so that
        //   these commands don't get forwarded on the internal command topic
        // - instead, the second consumer takes over at the offset of the first command (position must have been committed
        //   when closing the first consumer) and processes and forwards all commands starting with the second command

        final int numTestCommands = 10;
        final Promise<Void> firstRecordReceivedPromise = Promise.promise();
        final CountDownLatch allRecordsReceivedLatch = new CountDownLatch(numTestCommands);
        final List<String> receivedCommandSubjects = new ArrayList<>();
        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler = record -> {
            LOG.trace("received {}", record);
            receivedCommandSubjects.add(KafkaRecordHelper
                    .getHeaderValue(record.headers(), MessageHelper.SYS_PROPERTY_SUBJECT, String.class).orElse(""));
            if (receivedCommandSubjects.size() == 1) {
                firstRecordReceivedPromise.complete();
            }
            allRecordsReceivedLatch.countDown();
        };
        final Promise<Void> firstConsumerAllGetAdapterInstanceInvocationsDone = Promise.promise();
        final LinkedList<Promise<Void>> firstConsumerGetAdapterInstancePromisesQueue = new LinkedList<>();
        // don't let getting the target adapter instance finish immediately
        final Supplier<Future<Void>> firstConsumerGetAdapterInstanceSupplier = () -> {
            final Promise<Void> resultPromise = Promise.promise();
            firstConsumerGetAdapterInstancePromisesQueue.addFirst(resultPromise);
            // don't complete the future for the second command here yet
            if (firstConsumerGetAdapterInstancePromisesQueue.size() != 2) {
                resultPromise.complete();
            }
            if (firstConsumerGetAdapterInstancePromisesQueue.size() == numTestCommands) {
                firstConsumerAllGetAdapterInstanceInvocationsDone.complete();
            }
            return resultPromise.future();
        };

        final AtomicReference<KafkaBasedCommandConsumerFactoryImpl> consumerFactory1Ref = new AtomicReference<>();
        final Context vertxContext = vertx.getOrCreateContext();
        vertxContext.runOnContext(v0 -> {
            final HonoKafkaConsumer internalConsumer = getInternalCommandConsumer(recordHandler);
            final KafkaBasedCommandConsumerFactoryImpl consumerFactory1 = getKafkaBasedCommandConsumerFactory(
                    firstConsumerGetAdapterInstanceSupplier);
            consumerFactory1Ref.set(consumerFactory1);
            CompositeFuture.join(internalConsumer.start(), consumerFactory1.start())
                    .compose(f -> createCommandConsumer(tenantId, consumerFactory1))
                    .onComplete(setup.completing());
        });

        assertThat(setup.awaitCompletion(IntegrationTestSupport.getTestSetupTimeout(), TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
            return;
        }
        LOG.debug("command consumer started");

        final List<String> sentCommandSubjects = new ArrayList<>();
        IntStream.range(0, numTestCommands).forEach(i -> {
            final String subject = "cmd_" + i;
            sentCommandSubjects.add(subject);
            sendOneWayCommand(tenantId, "myDeviceId", subject);
        });

        final AtomicInteger secondConsumerGetAdapterInstanceInvocations = new AtomicInteger();
        // wait for first record on internal topic to have been received (making sure its offset got committed)
        CompositeFuture.join(firstConsumerAllGetAdapterInstanceInvocationsDone.future(), firstRecordReceivedPromise.future())
            .onComplete(v -> {
                LOG.debug("stopping consumer factory");
                consumerFactory1Ref.get().stop()
                    .onComplete(ctx.succeeding(ar -> {
                        LOG.debug("starting new consumer factory");
                        // no delay on getting the target adapter instance added here
                        final KafkaBasedCommandConsumerFactoryImpl consumerFactory2 = getKafkaBasedCommandConsumerFactory(() -> {
                            secondConsumerGetAdapterInstanceInvocations.incrementAndGet();
                            return Future.succeededFuture();
                        });
                        consumerFactory2.start()
                            .onComplete(ctx.succeeding(ar2 -> {
                                LOG.debug("creating command consumer in new consumer factory");
                                createCommandConsumer(tenantId, consumerFactory2)
                                        .onComplete(ctx.succeeding(ar3 -> {
                                            LOG.debug("consumer created, now complete the promises to get the target adapter instances");
                                            firstConsumerGetAdapterInstancePromisesQueue.forEach(Promise::tryComplete);
                                        }));
                            }));
                    }));
            });

        if (!allRecordsReceivedLatch.await(8, TimeUnit.SECONDS)) {
            ctx.failNow(new AssertionError(String.format("did not receive %d out of %d expected messages after 8s",
                    allRecordsReceivedLatch.getCount(), numTestCommands)));
        } else {
            ctx.verify(() -> {
                assertThat(receivedCommandSubjects).isEqualTo(sentCommandSubjects);
                // all but the first command should have been processed by the second consumer
                assertThat(secondConsumerGetAdapterInstanceInvocations.get()).isEqualTo(numTestCommands - 1);
            });
            ctx.completeNow();
        }
    }

    private Future<Void> createCommandConsumer(final String tenantId,
            final KafkaBasedCommandConsumerFactoryImpl consumerFactory) {
        topicsToDeleteAfterTest.add(new HonoTopic(HonoTopic.Type.COMMAND, tenantId).toString());
        return consumerFactory.createCommandConsumer(tenantId, null);
    }

    private HonoKafkaConsumer getInternalCommandConsumer(final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler) {
        final Map<String, String> consumerConfig = IntegrationTestSupport.getKafkaConsumerConfig()
                .getConsumerConfig("internal_cmd_consumer_test");
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());

        final String topic = new HonoTopic(HonoTopic.Type.COMMAND_INTERNAL, adapterInstanceId).toString();
        final HonoKafkaConsumer honoKafkaConsumer = new HonoKafkaConsumer(vertx, Set.of(topic), recordHandler,
                consumerConfig);
        componentsToStopAfterTest.add(honoKafkaConsumer);
        topicsToDeleteAfterTest.add(topic);
        return honoKafkaConsumer;
    }

    private KafkaBasedCommandConsumerFactoryImpl getKafkaBasedCommandConsumerFactory(
            final Supplier<Future<Void>> targetAdapterInstanceGetterCompletionFutureSupplier) {
        final KafkaProducerFactory<String, Buffer> producerFactory = KafkaProducerFactory.sharedProducerFactory(vertx);
        final TenantClient tenantClient = getTenantClient();
        final CommandTargetMapper commandTargetMapper = new CommandTargetMapper() {
            @Override
            public Future<JsonObject> getTargetGatewayAndAdapterInstance(final String tenantId,
                    final String deviceId,
                    final SpanContext context) {
                final JsonObject jsonObject = new JsonObject();
                jsonObject.put(DeviceConnectionConstants.FIELD_ADAPTER_INSTANCE_ID, adapterInstanceId);
                jsonObject.put(DeviceConnectionConstants.FIELD_PAYLOAD_DEVICE_ID, deviceId);
                if (targetAdapterInstanceGetterCompletionFutureSupplier == null) {
                    return Future.succeededFuture(jsonObject);
                }
                return targetAdapterInstanceGetterCompletionFutureSupplier.get().map(jsonObject);
            }
        };
        final Span span = TracingMockSupport.mockSpan();
        final Tracer tracer = TracingMockSupport.mockTracer(span);

        final KafkaConsumerConfigProperties kafkaConsumerConfig = new KafkaConsumerConfigProperties();
        kafkaConsumerConfig.setConsumerConfig(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, IntegrationTestSupport.DOWNSTREAM_BOOTSTRAP_SERVERS));
        final var kafkaBasedCommandConsumerFactoryImpl = new KafkaBasedCommandConsumerFactoryImpl(vertx, tenantClient,
                commandTargetMapper, producerFactory, IntegrationTestSupport.getKafkaProducerConfig(),
                kafkaConsumerConfig, tracer);
        kafkaBasedCommandConsumerFactoryImpl.setGroupId(commandRouterGroupId);
        componentsToStopAfterTest.add(kafkaBasedCommandConsumerFactoryImpl);
        return kafkaBasedCommandConsumerFactoryImpl;
    }

    private static void sendOneWayCommand(final String tenantId, final String deviceId, final String subject) {
        kafkaProducer.send(getOneWayCommandRecord(tenantId, deviceId, subject), ar -> {
            if (ar.succeeded()) {
                LOG.debug("sent command {}; metadata {}", subject, ar.result().toJson());
            } else {
                LOG.error("error sending command {}", subject, ar.cause());
            }
        });
    }

    private static KafkaProducerRecord<String, Buffer> getOneWayCommandRecord(final String tenantId,
            final String deviceId, final String subject) {
        final List<KafkaHeader> headers = List.of(
                KafkaHeader.header(MessageHelper.APP_PROPERTY_DEVICE_ID, deviceId),
                KafkaHeader.header(MessageHelper.SYS_PROPERTY_SUBJECT, subject)
        );
        final String commandTopic = new HonoTopic(HonoTopic.Type.COMMAND, tenantId).toString();
        final KafkaProducerRecord<String, Buffer> record = KafkaProducerRecord.create(commandTopic, deviceId, Buffer.buffer(subject + "_payload"));
        record.addHeaders(headers);
        return record;
    }

    private TenantClient getTenantClient() {
        return new TenantClient() {
            @Override
            public Future<TenantObject> get(final String tenantId, final SpanContext context) {
                return Future.succeededFuture(TenantObject.from(tenantId));
            }

            @Override
            public Future<TenantObject> get(final X500Principal subjectDn, final SpanContext context) {
                return Future.failedFuture("unsupported");
            }

            @Override
            public Future<Void> start() {
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> stop() {
                return Future.succeededFuture();
            }
        };
    }
}

