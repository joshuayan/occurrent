/*
 * Copyright 2020 Johan Haleby
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.occurrent.eventstore.mongodb.spring.reactor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.awaitility.Awaitility;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.occurrent.cloudevents.OccurrentCloudEventExtension;
import org.occurrent.condition.Condition;
import org.occurrent.domain.*;
import org.occurrent.eventstore.api.DuplicateCloudEventException;
import org.occurrent.eventstore.api.WriteCondition;
import org.occurrent.eventstore.api.WriteConditionNotFulfilledException;
import org.occurrent.eventstore.api.reactor.EventStoreQueries;
import org.occurrent.eventstore.api.reactor.EventStream;
import org.occurrent.functional.CheckedFunction;
import org.occurrent.mongodb.timerepresentation.TimeRepresentation;
import org.occurrent.testsupport.mongodb.FlushMongoDBExtension;
import org.occurrent.time.TimeConversion;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.SimpleReactiveMongoDatabaseFactory;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.condition.JRE.JAVA_11;
import static org.junit.jupiter.api.condition.JRE.JAVA_8;
import static org.occurrent.condition.Condition.*;
import static org.occurrent.filter.Filter.*;
import static org.occurrent.mongodb.timerepresentation.TimeRepresentation.RFC_3339_STRING;
import static org.occurrent.time.TimeConversion.offsetDateTimeFrom;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@SuppressWarnings("SameParameterValue")
@Testcontainers
public class SpringReactorMongoEventStoreTest {

    @Container
    private static final MongoDBContainer mongoDBContainer;
    private static final URI NAME_SOURCE = URI.create("http://name");

    static {
        mongoDBContainer = new MongoDBContainer("mongo:4.2.8");
        List<String> ports = new ArrayList<>();
        ports.add("27017:27017");
        mongoDBContainer.setPortBindings(ports);
    }

    private SpringReactorMongoEventStore eventStore;

    @RegisterExtension
    FlushMongoDBExtension flushMongoDBExtension = new FlushMongoDBExtension(new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events"));
    private ObjectMapper objectMapper;
    private ReactiveMongoTemplate mongoTemplate;
    private ConnectionString connectionString;
    private MongoClient mongoClient;
    private ReactiveMongoTransactionManager reactiveMongoTransactionManager;

    @BeforeEach
    void create_mongo_spring_reactive_event_store() {
        connectionString = new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events");
        mongoClient = MongoClients.create(connectionString);
        mongoTemplate = new ReactiveMongoTemplate(mongoClient, requireNonNull(connectionString.getDatabase()));
        objectMapper = new ObjectMapper();
        reactiveMongoTransactionManager = new ReactiveMongoTransactionManager(new SimpleReactiveMongoDatabaseFactory(mongoClient, requireNonNull(connectionString.getDatabase())));
        EventStoreConfig eventStoreConfig = new EventStoreConfig.Builder().eventStoreCollectionName(connectionString.getCollection()).transactionConfig(reactiveMongoTransactionManager).timeRepresentation(RFC_3339_STRING).build();
        eventStore = new SpringReactorMongoEventStore(mongoTemplate, eventStoreConfig);
    }

    @Test
    void can_read_and_write_single_event_to_mongo_spring_reactive_event_store() {
        LocalDateTime now = LocalDateTime.now();

        // When
        List<DomainEvent> events = Name.defineName(UUID.randomUUID().toString(), now, "John Doe");
        persist("name", WriteCondition.streamVersionEq(0), events).block();

        // Then
        Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name");
        VersionAndEvents versionAndEvents = deserialize(eventStream);

        assertAll(
                () -> assertThat(versionAndEvents.version).isEqualTo(1),
                () -> assertThat(versionAndEvents.events).hasSize(1),
                () -> assertThat(versionAndEvents.events).containsExactlyElementsOf(events)
        );
    }

    @Test
    void can_read_and_write_multiple_events_at_once_to_mongo_spring_reactive_event_store() {
        LocalDateTime now = LocalDateTime.now();
        List<DomainEvent> events = Composition.chain(Name.defineName(UUID.randomUUID().toString(), now, "Hello World"), es -> Name.changeName(es, UUID.randomUUID().toString(), now, "John Doe"));

        // When
        persist("name", WriteCondition.streamVersionEq(0), events).block();

        // Then
        Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name");
        VersionAndEvents versionAndEvents = deserialize(eventStream);

        assertAll(
                () -> assertThat(versionAndEvents.version).isEqualTo(events.size()),
                () -> assertThat(versionAndEvents.events).hasSize(2),
                () -> assertThat(versionAndEvents.events).containsExactlyElementsOf(events)
        );
    }

    @Test
    void can_read_and_write_multiple_events_at_different_occasions_to_mongo_spring_reactive_event_store() {
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

        // When
        persist("name", WriteCondition.streamVersionEq(0), nameDefined).block();
        persist("name", WriteCondition.streamVersionEq(1), nameWasChanged1).block();
        persist("name", WriteCondition.streamVersionEq(2), nameWasChanged2).block();

        // Then
        Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name");
        VersionAndEvents versionAndEvents = deserialize(eventStream);

        assertAll(
                () -> assertThat(versionAndEvents.version).isEqualTo(3),
                () -> assertThat(versionAndEvents.events).hasSize(3),
                () -> assertThat(versionAndEvents.events).containsExactly(nameDefined, nameWasChanged1, nameWasChanged2)
        );
    }

    @Test
    void can_read_events_with_skip_and_limit_using_mongo_event_store() {
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

        // When
        persist("name", WriteCondition.streamVersionEq(0), nameDefined).block();
        persist("name", WriteCondition.streamVersionEq(1), nameWasChanged1).block();
        persist("name", WriteCondition.streamVersionEq(2), nameWasChanged2).block();

        // Then
        Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name", 1, 1);
        VersionAndEvents versionAndEvents = deserialize(eventStream);

        assertAll(
                () -> assertThat(versionAndEvents.version).isEqualTo(3),
                () -> assertThat(versionAndEvents.events).hasSize(1),
                () -> assertThat(versionAndEvents.events).containsExactly(nameWasChanged1)
        );
    }

    @Test
    void stream_version_is_not_updated_when_event_insertion_fails() {
        LocalDateTime now = LocalDateTime.now();
        List<DomainEvent> events = Composition.chain(Name.defineName(UUID.randomUUID().toString(), now, "Hello World"), es -> Name.changeName(es, UUID.randomUUID().toString(), now, "John Doe"));

        persist("name", WriteCondition.streamVersionEq(0), events).block();

        // When
        Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersionEq(events.size()), events).block());

        // Then
        Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name");
        VersionAndEvents versionAndEvents = deserialize(eventStream);

        assertAll(
                () -> assertThat(throwable).isExactlyInstanceOf(DuplicateCloudEventException.class),
                () -> assertThat(versionAndEvents.version).isEqualTo(events.size()),
                () -> assertThat(versionAndEvents.events).hasSize(2),
                () -> assertThat(versionAndEvents.events).containsExactlyElementsOf(events)
        );
    }

    @Test
    void read_skew_is_avoided_and_transaction_is_started() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

        persist("name", WriteCondition.streamVersionEq(0), Flux.just(nameDefined, nameWasChanged1)).block();

        TransactionalOperator transactionalOperator = TransactionalOperator.create(reactiveMongoTransactionManager);
        CountDownLatch countDownLatch = new CountDownLatch(1);

        AtomicReference<VersionAndEvents> versionAndEventsRef = new AtomicReference<>();

        // When
        transactionalOperator.execute(__ -> eventStore.read("name")
                .flatMap(es -> es.events().collectList().map(eventList -> {
                    await(countDownLatch);
                    return new VersionAndEvents(es.version(), eventList.stream().map(deserialize()).collect(Collectors.toList()));
                }))
                .doOnNext(versionAndEventsRef::set))
                .subscribe();

        transactionalOperator.execute(__ -> persist("name", WriteCondition.streamVersionEq(2), nameWasChanged2)
                .then(Mono.fromRunnable(countDownLatch::countDown)).then())
                .blockFirst();

        // Then
        VersionAndEvents versionAndEvents = Awaitility.await().untilAtomic(versionAndEventsRef, not(nullValue()));

        assertAll(
                () -> assertThat(versionAndEvents.version).describedAs("version").isEqualTo(2L),
                () -> assertThat(versionAndEvents.events).containsExactly(nameDefined, nameWasChanged1)
        );
    }

    @Test
    void read_skew_is_avoided_and_skip_and_limit_is_defined_even_when_no_transaction_is_started() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

        persist("name", WriteCondition.streamVersionEq(0), Flux.just(nameDefined, nameWasChanged1)).block();

        // When
        VersionAndEvents versionAndEvents =
                eventStore.read("name", 0, 2)
                        .flatMap(es -> persist("name", WriteCondition.streamVersionEq(2), nameWasChanged2)
                                .then(es.events().collectList())
                                .map(eventList -> new VersionAndEvents(es.version(), eventList.stream().map(deserialize()).collect(Collectors.toList()))))
                        .block();
        // Then
        assert versionAndEvents != null;
        assertAll(
                () -> assertThat(versionAndEvents.version).describedAs("version").isEqualTo(2L),
                () -> assertThat(versionAndEvents.events).containsExactly(nameDefined, nameWasChanged1)
        );
    }

    @Test
    void no_events_are_inserted_when_batch_contains_duplicate_events() {
        LocalDateTime now = LocalDateTime.now();

        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name4");

        // When
        Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersionEq(0), Flux.just(nameDefined, nameWasChanged1, nameWasChanged1, nameWasChanged2)).block());

        // Then
        Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name");
        VersionAndEvents versionAndEvents = deserialize(eventStream);

        assertAll(
                () -> assertThat(throwable).isExactlyInstanceOf(DuplicateCloudEventException.class).hasCauseExactlyInstanceOf(MongoBulkWriteException.class),
                () -> assertThat(versionAndEvents.version).isEqualTo(0),
                () -> assertThat(versionAndEvents.events).isEmpty()
        );
    }

    @Test
    void no_events_are_inserted_when_batch_contains_event_that_has_already_been_persisted() {
        LocalDateTime now = LocalDateTime.now();

        NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
        NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name4");

        persist("name", WriteCondition.streamVersionEq(0), Flux.just(nameDefined, nameWasChanged1)).block();

        // When
        Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersionEq(2), Flux.just(nameWasChanged2, nameWasChanged1)).block());

        // Then
        Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name");
        VersionAndEvents versionAndEvents = deserialize(eventStream);

        assertAll(
                () -> assertThat(throwable).isExactlyInstanceOf(DuplicateCloudEventException.class).hasCauseExactlyInstanceOf(MongoBulkWriteException.class),
                () -> assertThat(versionAndEvents.version).isEqualTo(2),
                () -> assertThat(versionAndEvents.events).hasSize(2),
                () -> assertThat(versionAndEvents.events).containsExactly(nameDefined, nameWasChanged1)
        );
    }

    @Nested
    @DisplayName("deletion")
    class Delete {

        @Test
        void deleteEventStream_deletes_all_events_in_event_stream() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Flux.just(nameDefined, nameWasChanged1)).block();

            // When
            eventStore.deleteEventStream("name").block();

            // Then
            VersionAndEvents versionAndEvents = deserialize(eventStore.read("name"));
            assertAll(
                    () -> assertThat(versionAndEvents.version).isZero(),
                    () -> assertThat(versionAndEvents.events).isEmpty(),
                    () -> assertThat(eventStore.exists("name").block()).isFalse(),
                    () -> assertThat(mongoTemplate.count(query(where(OccurrentCloudEventExtension.STREAM_ID).is("name")), "events").block()).isZero()
            );
        }

        @Test
        void deleteEvent_deletes_only_specific_event_in_event_stream() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Flux.just(nameDefined, nameWasChanged1)).block();

            // When
            eventStore.deleteEvent(nameWasChanged1.getEventId(), NAME_SOURCE).block();

            // Then
            VersionAndEvents versionAndEvents = deserialize(eventStore.read("name"));
            assertAll(
                    () -> assertThat(versionAndEvents.version).isEqualTo(1),
                    () -> assertThat(versionAndEvents.events).containsExactly(nameDefined),
                    () -> assertThat(eventStore.exists("name").block()).isTrue(),
                    () -> assertThat(mongoTemplate.count(query(where(OccurrentCloudEventExtension.STREAM_ID).is("name")), "events").block()).isEqualTo(1)
            );
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Nested
    @DisplayName("exists")
    class Exists {

        @Test
        void returns_true_when_stream_exists_and_contains_events() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Flux.just(nameDefined, nameWasChanged1)).block();

            // When
            boolean exists = eventStore.exists("name").block();

            // Then
            assertThat(exists).isTrue();
        }

        @Test
        void returns_false_when_no_events_have_been_persisted_to_stream() {
            // When
            boolean exists = eventStore.exists("name").block();

            // Then
            assertThat(exists).isFalse();
        }
    }

    @Nested
    @DisplayName("update when stream consistency guarantee is transactional")
    class Update {

        @Test
        void updates_cloud_event_when_cloud_event_exists() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            String eventId2 = UUID.randomUUID().toString();
            NameWasChanged nameWasChanged1 = new NameWasChanged(eventId2, now.plusHours(1), "name2");
            persist("name", Flux.just(nameDefined, nameWasChanged1)).block();

            // When
            eventStore.updateEvent(eventId2, NAME_SOURCE, cloudEvent -> {
                NameWasChanged e = deserialize(cloudEvent);
                NameWasChanged correctedName = new NameWasChanged(e.getEventId(), e.getTimestamp(), "name3");
                return CloudEventBuilder.v1(cloudEvent).withData(serializeEvent(correctedName)).build();
            }).block();

            // Then
            Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name");
            VersionAndEvents versionAndEvents = deserialize(eventStream);
            assertThat(versionAndEvents.events).containsExactly(nameDefined, new NameWasChanged(eventId2, now.plusHours(1), "name3"));
        }

        @Test
        void returns_updated_cloud_event_when_cloud_event_exists() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            String eventId2 = UUID.randomUUID().toString();
            NameWasChanged nameWasChanged1 = new NameWasChanged(eventId2, now.plusHours(1), "name2");
            persist("name", Flux.just(nameDefined, nameWasChanged1)).block();

            // When
            DomainEvent updatedCloudEvent = eventStore.updateEvent(eventId2, NAME_SOURCE, cloudEvent -> {
                NameWasChanged e = deserialize(cloudEvent);
                NameWasChanged correctedName = new NameWasChanged(e.getEventId(), e.getTimestamp(), "name3");
                return CloudEventBuilder.v1(cloudEvent).withData(serializeEvent(correctedName)).build();
            }).map(deserialize()).block();

            // Then
            assertThat(updatedCloudEvent).isEqualTo(new NameWasChanged(eventId2, now.plusHours(1), "name3"));
        }

        @Test
        void returns_empty_optional_when_cloud_event_does_not_exists() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            persist("name", Flux.just(nameDefined, nameWasChanged1)).block();

            // When
            CloudEvent updatedCloudEvent = eventStore.updateEvent(UUID.randomUUID().toString(), NAME_SOURCE, cloudEvent -> {
                NameWasChanged e = deserialize(cloudEvent);
                NameWasChanged correctedName = new NameWasChanged(e.getEventId(), e.getTimestamp(), "name3");
                return CloudEventBuilder.v1(cloudEvent).withData(serializeEvent(correctedName)).build();
            }).block();
            // Then
            assertThat(updatedCloudEvent).isNull();
        }

        @Test
        void throw_iae_when_update_function_returns_null() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            String eventId2 = UUID.randomUUID().toString();
            NameWasChanged nameWasChanged1 = new NameWasChanged(eventId2, now.plusHours(1), "name2");
            persist("name", Flux.just(nameDefined, nameWasChanged1)).block();

            // When
            Throwable throwable = catchThrowable(() -> eventStore.updateEvent(eventId2, NAME_SOURCE, cloudEvent -> null).block());

            // Then
            assertThat(throwable).isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("Cloud event update function is not allowed to return null");
        }

        @Test
        void when_update_function_returns_the_same_argument_then_cloud_event_is_unchanged_in_the_database() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            String eventId2 = UUID.randomUUID().toString();
            NameWasChanged nameWasChanged1 = new NameWasChanged(eventId2, now.plusHours(1), "name2");
            persist("name", Flux.just(nameDefined, nameWasChanged1)).block();

            // When
            eventStore.updateEvent(eventId2, NAME_SOURCE, Function.identity()).block();

            // Then
            Mono<EventStream<CloudEvent>> eventStream = eventStore.read("name");
            VersionAndEvents versionAndEvents = deserialize(eventStream);
            assertThat(versionAndEvents.events).containsExactly(nameDefined, nameWasChanged1);
        }
    }

    @Nested
    @DisplayName("Conditionally Write to Mongo Event Store")
    class ConditionallyWriteToSpringMongoEventStore {

        LocalDateTime now = LocalDateTime.now();

        @Nested
        @DisplayName("eq")
        class Eq {

            @Test
            void writes_events_when_stream_version_matches_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");

                eventStore.read("name").flatMap(eventStream1 -> persist(eventStream1.id(), WriteCondition.streamVersionEq(eventStream1.version()), event2)).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_does_not_match_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersionEq(10), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be equal to 10 but was 1.");
            }
        }

        @Nested
        @DisplayName("ne")
        class Ne {

            @Test
            void writes_events_when_stream_version_does_not_match_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(ne(20L)), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_match_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(ne(1L)), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to not be equal to 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("lt")
        class Lt {

            @Test
            void writes_events_when_stream_version_is_less_than_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(lt(10L)), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_greater_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(lt(0L)), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be less than 0 but was 1.");
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_equal_to_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(lt(1L)), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be less than 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("gt")
        class Gt {

            @Test
            void writes_events_when_stream_version_is_greater_than_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(gt(0L)), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_less_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(gt(100L)), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than 100 but was 1.");
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_equal_to_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(gt(1L)), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("lte")
        class Lte {

            @Test
            void writes_events_when_stream_version_is_less_than_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(lte(10L)), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }


            @Test
            void writes_events_when_stream_version_is_equal_to_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(lte(1L)), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_greater_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(lte(0L)), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be less than or equal to 0 but was 1.");
            }
        }

        @Nested
        @DisplayName("gte")
        class Gte {

            @Test
            void writes_events_when_stream_version_is_greater_than_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(gte(0L)), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void writes_events_when_stream_version_is_equal_to_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(gte(0L)), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_less_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(gte(100L)), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than or equal to 100 but was 1.");
            }
        }

        @Nested
        @DisplayName("and")
        class And {

            @Test
            void writes_events_when_stream_version_is_when_all_conditions_match_and_expression() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(and(gte(0L), lt(100L), ne(40L))), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_any_of_the_operations_in_the_and_expression_is_not_fulfilled() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(and(gte(0L), lt(100L), ne(1L))), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than or equal to 0 and to be less than 100 and to not be equal to 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("or")
        class Or {

            @Test
            void writes_events_when_stream_version_is_when_any_condition_in_or_expression_matches() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(or(gte(100L), lt(0L), ne(40L))), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_none_of_the_operations_in_the_and_expression_is_fulfilled() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(or(gte(100L), lt(1L))), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than or equal to 100 or to be less than 1 but was 1.");
            }
        }

        @Nested
        @DisplayName("not")
        class Not {

            @Test
            void writes_events_when_stream_version_is_not_matching_condition() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Mono<EventStream<CloudEvent>> eventStream1 = eventStore.read("name");
                persist(streamIdOf(eventStream1), WriteCondition.streamVersion(Condition.not(eq(100L))), event2).block();

                // Then
                Mono<EventStream<CloudEvent>> eventStream2 = eventStore.read("name");
                assertThat(deserialize(eventStream2).events).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_condition_is_fulfilled_but_should_not_be_so() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                persist("name", event1).block();

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> persist("name", WriteCondition.streamVersion(Condition.not(eq(1L))), event2).block());

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version not to be equal to 1 but was 1.");
            }
        }
    }

    @Nested
    @DisplayName("queries")
    class QueriesTest {

        @Test
        void all_without_skip_and_limit_returns_all_events() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", nameDefined).block();
            persist("name2", nameWasChanged1).block();
            persist("name3", nameWasChanged2).block();

            // Then
            Flux<CloudEvent> events = eventStore.all();
            assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged1, nameWasChanged2);
        }

        @Test
        void all_with_skip_and_limit_returns_all_events_within_skip_and_limit() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", Flux.just(nameWasChanged2)).block();

            // Then
            Flux<CloudEvent> events = eventStore.all(1, 2);
            assertThat(deserialize(events)).containsExactly(nameWasChanged1, nameWasChanged2);
        }

        @Test
        void query_with_single_filter_without_skip_and_limit() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();
            persist("something", CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("http://something"))
                    .withType("something")
                    .withTime(LocalDateTime.now().atOffset(UTC))
                    .withSubject("subject")
                    .withDataContentType("application/json")
                    .withData("{\"hello\":\"world\"}".getBytes(UTF_8))
                    .build()
            );

            // Then
            Flux<CloudEvent> events = eventStore.query(source(NAME_SOURCE));
            assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged1, nameWasChanged2);
        }

        @Test
        void query_with_single_filter_with_skip_and_limit() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();
            persist("something", CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("http://something"))
                    .withType("something")
                    .withTime(LocalDateTime.now().atOffset(UTC))
                    .withSubject("subject")
                    .withDataContentType("application/json")
                    .withData("{\"hello\":\"world\"}".getBytes(UTF_8))
                    .build()
            );

            // Then
            Flux<CloudEvent> events = eventStore.query(source(NAME_SOURCE), 1, 1);
            assertThat(deserialize(events)).containsExactly(nameWasChanged1);
        }

        @Test
        void compose_filters_using_and() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            UUID uuid = UUID.randomUUID();
            NameDefined nameDefined = new NameDefined(uuid.toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();

            // Then
            Flux<CloudEvent> events = eventStore.query(time(lt(OffsetDateTime.of(now.plusHours(2), UTC))).and(id(uuid.toString())));
            assertThat(deserialize(events)).containsExactly(nameDefined);
        }

        @Test
        void compose_filters_using_or() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();

            // Then
            Flux<CloudEvent> events = eventStore.query(time(OffsetDateTime.of(now.plusHours(2), UTC)).or(source(NAME_SOURCE)));
            assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged1, nameWasChanged2);
        }

        @Test
        void query_filter_by_subject() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();

            // Then
            Flux<CloudEvent> events = eventStore.query(subject("WasChanged"));
            assertThat(deserialize(events)).containsExactly(nameWasChanged1, nameWasChanged2);
        }

        @Test
        void query_filter_by_cloud_event() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            String eventId = UUID.randomUUID().toString();
            NameWasChanged nameWasChanged1 = new NameWasChanged(eventId, now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();

            // Then
            Flux<CloudEvent> events = eventStore.query(cloudEvent(eventId, NAME_SOURCE));
            assertThat(deserialize(events)).containsExactly(nameWasChanged1);
        }

        @Test
        void query_filter_by_type() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();

            // Then
            Flux<CloudEvent> events = eventStore.query(type(NameDefined.class.getName()));
            assertThat(deserialize(events)).containsExactly(nameDefined);
        }

        @Test
        void query_filter_by_data_schema() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();
            CloudEvent cloudEvent = CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("http://something"))
                    .withType("something")
                    .withTime(LocalDateTime.now().atOffset(UTC))
                    .withSubject("subject")
                    .withDataSchema(URI.create("urn:myschema"))
                    .withDataContentType("application/json")
                    .withData("{\"hello\":\"world\"}".getBytes(UTF_8))
                    .withExtension(OccurrentCloudEventExtension.occurrent("something", 1))
                    .build();
            persist("something", cloudEvent).block();

            // Then
            Flux<CloudEvent> events = eventStore.query(dataSchema(URI.create("urn:myschema")));
            assertThat(events.toStream()).containsExactly(cloudEvent);
        }

        @Test
        void query_filter_by_data_content_type() {
            // Given
            LocalDateTime now = LocalDateTime.now();
            NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
            NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
            NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

            // When
            persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
            persist("name2", nameWasChanged2).block();
            CloudEvent cloudEvent = CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("http://something"))
                    .withType("something")
                    .withTime(offsetDateTimeFrom(LocalDateTime.now(), ZoneId.of("Europe/Stockholm")))
                    .withSubject("subject")
                    .withDataSchema(URI.create("urn:myschema"))
                    .withDataContentType("text/plain")
                    .withData("text".getBytes(UTF_8))
                    .withExtension(OccurrentCloudEventExtension.occurrent("something", 1))
                    .build();
            persist("something", cloudEvent).block();

            // Then
            Flux<CloudEvent> events = eventStore.query(dataContentType("text/plain"));
            assertThat(events.toStream()).containsExactly(cloudEvent);
        }

        @Nested
        @DisplayName("sort")
        class SortTest {

            @Test
            void sort_by_natural_asc() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(-2), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name3");

                // When
                persist("name3", nameWasChanged1).block();
                persist("name2", nameWasChanged2).block();
                persist("name1", nameDefined).block();

                // Then
                Flux<CloudEvent> events = eventStore.all(EventStoreQueries.SortBy.NATURAL_ASC);
                assertThat(deserialize(events)).containsExactly(nameWasChanged1, nameWasChanged2, nameDefined);
            }

            @Test
            void sort_by_natural_desc() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(-2), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name3");

                // When
                persist("name3", nameWasChanged1).block();
                persist("name2", nameWasChanged2).block();
                persist("name1", nameDefined).block();

                // Then
                Flux<CloudEvent> events = eventStore.all(EventStoreQueries.SortBy.NATURAL_DESC);
                assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged2, nameWasChanged1);
            }

            @Test
            void sort_by_time_asc() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(-2), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name3");

                // When
                persist("name3", nameWasChanged1).block();
                persist("name2", nameWasChanged2).block();
                persist("name1", nameDefined).block();

                // Then
                Flux<CloudEvent> events = eventStore.all(EventStoreQueries.SortBy.TIME_ASC);
                assertThat(deserialize(events)).containsExactly(nameWasChanged1, nameDefined, nameWasChanged2);
            }

            @Test
            void sort_by_time_desc() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now.plusHours(3), "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(-2), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name3");

                // When
                persist("name3", nameWasChanged1).block();
                persist("name2", nameWasChanged2).block();
                persist("name1", nameDefined).block();

                // Then
                Flux<CloudEvent> events = eventStore.all(EventStoreQueries.SortBy.TIME_DESC);
                assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged2, nameWasChanged1);
            }
        }

        @Nested
        @DisplayName("when time is represented as rfc 3339 string")
        class TimeRepresentedAsRfc3339String {

            @Test
            void query_filter_by_time_but_is_using_slow_string_comparison() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(lt(OffsetDateTime.of(now.plusHours(2), UTC))));
                assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged1);
            }

            @Test
            void query_filter_by_time_range_is_wider_than_persisted_time_range() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(and(gte(OffsetDateTime.of(now.plusMinutes(35), UTC)), lte(OffsetDateTime.of(now.plusHours(4), UTC)))));
                assertThat(deserialize(events)).containsExactly(nameWasChanged1, nameWasChanged2);
            }

            @EnabledOnJre(JAVA_8)
            @Test
            void query_filter_by_time_range_has_exactly_the_same_range_as_persisted_time_range_when_using_java_8() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(and(gte(OffsetDateTime.of(now, UTC)), lte(OffsetDateTime.of(now.plusHours(2), UTC)))));
                assertThat(deserialize(events)).isNotEmpty(); // Java 8 seem to return nondeterministic results
            }

            @EnabledForJreRange(min = JAVA_11)
            @Test
            void query_filter_by_time_range_has_exactly_the_same_range_as_persisted_time_range_when_using_java_11_and_above() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(and(gte(OffsetDateTime.of(now, UTC)), lte(OffsetDateTime.of(now.plusHours(2), UTC)))));
                assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged1); // nameWasChanged2 _should_ be included but it's not due to string comparison instead of date
            }

            @Test
            void query_filter_by_time_range_has_a_range_smaller_as_persisted_time_range() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(and(gt(OffsetDateTime.of(now.plusMinutes(50), UTC)), lt(OffsetDateTime.of(now.plusMinutes(110), UTC)))));
                assertThat(deserialize(events)).containsExactly(nameWasChanged1);
            }
        }

        @Nested
        @DisplayName("when time is represented as date")
        class TimeRepresentedAsDate {

            @BeforeEach
            void event_store_is_configured_to_using_date_as_time_representation() {
                eventStore = new SpringReactorMongoEventStore(mongoTemplate, new EventStoreConfig(connectionString.getCollection(), TransactionalOperator.create(reactiveMongoTransactionManager), TimeRepresentation.DATE));
            }

            @Test
            void query_filter_by_time_lt() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(lt(OffsetDateTime.of(now.plusHours(2), UTC))));
                assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged1);
            }

            @Test
            void query_filter_by_time_range_is_wider_than_persisted_time_range() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(and(gte(OffsetDateTime.of(now.plusMinutes(35), UTC)), lte(OffsetDateTime.of(now.plusHours(4), UTC)))));
                assertThat(deserialize(events)).containsExactly(nameWasChanged1, nameWasChanged2);
            }

            @Test
            void query_filter_by_time_range_has_exactly_the_same_range_as_persisted_time_range() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(and(gte(OffsetDateTime.of(now, UTC)), lte(OffsetDateTime.of(now.plusHours(2), UTC)))));
                assertThat(deserialize(events)).containsExactly(nameDefined, nameWasChanged1, nameWasChanged2);
            }

            @Test
            void query_filter_by_time_range_has_a_range_smaller_as_persisted_time_range() {
                // Given
                LocalDateTime now = LocalDateTime.now();
                NameDefined nameDefined = new NameDefined(UUID.randomUUID().toString(), now, "name");
                NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(1), "name2");
                NameWasChanged nameWasChanged2 = new NameWasChanged(UUID.randomUUID().toString(), now.plusHours(2), "name3");

                // When
                persist("name1", Flux.just(nameDefined, nameWasChanged1)).block();
                persist("name2", nameWasChanged2).block();

                // Then
                Flux<CloudEvent> events = eventStore.query(time(and(gt(OffsetDateTime.of(now.plusMinutes(50), UTC)), lt(OffsetDateTime.of(now.plusMinutes(110), UTC)))));
                assertThat(deserialize(events)).containsExactly(nameWasChanged1);
            }
        }
    }


    private VersionAndEvents deserialize(Mono<EventStream<CloudEvent>> eventStreamMono) {
        return eventStreamMono
                .map(es -> {
                    List<DomainEvent> events = es.events()
                            .map(deserialize())
                            .toStream()
                            .collect(Collectors.toList());
                    return new VersionAndEvents(es.version(), events);
                })
                .block();
    }

    private List<DomainEvent> deserialize(Flux<CloudEvent> flux) {
        return flux.map(deserialize()).toStream().collect(Collectors.toList());
    }

    @NotNull
    private Function<CloudEvent, DomainEvent> deserialize() {
        return CheckedFunction.unchecked(this::deserialize);
    }

    @SuppressWarnings("unchecked")
    private <T extends DomainEvent> T deserialize(CloudEvent cloudEvent) {
        try {
            return (T) objectMapper.readValue(cloudEvent.getData(), Class.forName(cloudEvent.getType()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class VersionAndEvents {
        private final long version;
        private final List<DomainEvent> events;

        VersionAndEvents(long version, List<DomainEvent> events) {
            this.version = version;
            this.events = events;
        }

        @Override
        public String toString() {
            return "VersionAndEvents{" +
                    "version=" + version +
                    ", events=" + events +
                    '}';
        }
    }

    private static String streamIdOf(Mono<EventStream<CloudEvent>> eventStreamMono) {
        return eventStreamMono.map(EventStream::id).block();
    }


    private Mono<Void> persist(String eventStreamId, CloudEvent event) {
        return eventStore.write(eventStreamId, Flux.just(event));
    }

    private Mono<Void> persist(String eventStreamId, DomainEvent event) {
        return eventStore.write(eventStreamId, Flux.just(convertDomainEventCloudEvent(event)));
    }

    private Mono<Void> persist(String eventStreamId, Flux<DomainEvent> events) {
        return eventStore.write(eventStreamId, events.map(this::convertDomainEventCloudEvent));
    }

    private Mono<Void> persist(String eventStreamId, List<DomainEvent> events) {
        return persist(eventStreamId, Flux.fromIterable(events));
    }

    private Mono<Void> persist(String eventStreamId, WriteCondition writeCondition, DomainEvent event) {
        List<DomainEvent> events = new ArrayList<>();
        events.add(event);
        return persist(eventStreamId, writeCondition, events);
    }

    private Mono<Void> persist(String eventStreamId, WriteCondition writeCondition, List<DomainEvent> events) {
        return persist(eventStreamId, writeCondition, Flux.fromIterable(events));
    }

    private Mono<Void> persist(String eventStreamId, WriteCondition writeCondition, Flux<DomainEvent> events) {
        return eventStore.write(eventStreamId, writeCondition, events.map(this::convertDomainEventCloudEvent));
    }

    @NotNull
    private CloudEvent convertDomainEventCloudEvent(DomainEvent domainEvent) {
        return CloudEventBuilder.v1()
                .withId(domainEvent.getEventId())
                .withSource(NAME_SOURCE)
                .withType(domainEvent.getClass().getName())
                .withTime(TimeConversion.toLocalDateTime(domainEvent.getTimestamp()).atOffset(UTC))
                .withSubject(domainEvent.getClass().getSimpleName().substring(4)) // Defined or WasChanged
                .withDataContentType("application/json")
                .withData(serializeEvent(domainEvent))
                .build();
    }

    private byte[] serializeEvent(DomainEvent domainEvent) {
        return CheckedFunction.unchecked(objectMapper::writeValueAsBytes).apply(domainEvent);
    }

    private static void await(CountDownLatch countDownLatch) {
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
