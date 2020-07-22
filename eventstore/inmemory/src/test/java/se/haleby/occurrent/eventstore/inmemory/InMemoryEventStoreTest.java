package se.haleby.occurrent.eventstore.inmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import se.haleby.occurrent.domain.DomainEvent;
import se.haleby.occurrent.domain.Name;
import se.haleby.occurrent.domain.NameDefined;
import se.haleby.occurrent.domain.NameWasChanged;
import se.haleby.occurrent.eventstore.api.blocking.EventStore;
import se.haleby.occurrent.eventstore.api.blocking.EventStream;
import se.haleby.occurrent.eventstore.api.WriteCondition;
import se.haleby.occurrent.eventstore.api.WriteConditionNotFulfilledException;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static se.haleby.occurrent.cloudevents.OccurrentCloudEventExtension.STREAM_ID;
import static se.haleby.occurrent.eventstore.api.WriteCondition.Condition.*;
import static se.haleby.occurrent.eventstore.api.WriteCondition.streamVersion;
import static se.haleby.occurrent.eventstore.api.WriteCondition.streamVersionEq;
import static se.haleby.occurrent.functional.CheckedFunction.unchecked;
import static se.haleby.occurrent.time.TimeConversion.toLocalDateTime;

@ExtendWith(SoftAssertionsExtension.class)
public class InMemoryEventStoreTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void create_object_mapper() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void read_and_write(SoftAssertions softly) {
        // Given
        InMemoryEventStore inMemoryEventStore = new InMemoryEventStore();
        String eventId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        // When
        List<DomainEvent> events = Name.defineName(eventId, now, "John Doe");
        unconditionallyPersist(inMemoryEventStore, "name", events);

        // Then
        EventStream<NameDefined> eventStream = inMemoryEventStore.read("name").map(unchecked(cloudEvent -> objectMapper.readValue(cloudEvent.getData(), NameDefined.class)));
        softly.assertThat(eventStream.version()).isEqualTo(1L);
        softly.assertThat(eventStream.events()).hasSize(1);
        softly.assertThat(eventStream.events().collect(Collectors.toList())).containsExactly(new NameDefined(eventId, now, "John Doe"));
    }

    @Test
    void adds_stream_id_extension_to_each_event() {
        // Given
        InMemoryEventStore inMemoryEventStore = new InMemoryEventStore();
        LocalDateTime now = LocalDateTime.now();

        // When
        DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
        DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
        unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1, event2).collect(Collectors.toList()));

        // Then
        EventStream<CloudEvent> eventStream = inMemoryEventStore.read("name");
        assertThat(eventStream.events().map(e -> e.getExtension(STREAM_ID))).containsOnly("name");
    }


    @Nested
    @DisplayName("Conditionally Write to InMemory Event Store")
    class ConditionallyWriteToInMemoryEventStore {

        InMemoryEventStore inMemoryEventStore = new InMemoryEventStore();
        LocalDateTime now = LocalDateTime.now();

        @Nested
        @DisplayName("eq")
        class Eq {

            @Test
            void writes_events_when_stream_version_matches_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersionEq(eventStream1.version()), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_does_not_match_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersionEq(10), Stream.of(event2)));

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
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(ne(20L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_match_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(ne(1L)), Stream.of(event2)));

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
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(lt(10L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_greater_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(lt(0L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be less than 0 but was 1.");
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_equal_to_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(lt(1L)), Stream.of(event2)));

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
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(gt(0L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_less_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(gt(100L)), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version to be greater than 100 but was 1.");
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_equal_to_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(gt(1L)), Stream.of(event2)));

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
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(lte(10L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }


            @Test
            void writes_events_when_stream_version_is_equal_to_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(lte(1L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_greater_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(lte(0L)), Stream.of(event2)));

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
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(gte(0L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void writes_events_when_stream_version_is_equal_to_expected_version() {
                // When
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(gte(0L)), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_stream_version_is_less_than_expected_version() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(gte(100L)), Stream.of(event2)));

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
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(and(gte(0L), lt(100L), ne(40L))), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_any_of_the_operations_in_the_and_expression_is_not_fulfilled() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(and(gte(0L), lt(100L), ne(1L))), Stream.of(event2)));

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
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(or(gte(100L), lt(0L), ne(40L))), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_none_of_the_operations_in_the_and_expression_is_fulfilled() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(or(gte(100L), lt(1L))), Stream.of(event2)));

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
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                EventStream<CloudEvent> eventStream1 = inMemoryEventStore.read("name");
                conditionallyPersist(inMemoryEventStore, eventStream1.id(), streamVersion(not(eq(100L))), Stream.of(event2));

                // Then
                EventStream<CloudEvent> eventStream2 = inMemoryEventStore.read("name");
                assertThat(eventStream2.map(deserialize(objectMapper))).containsExactly(event1, event2);
            }

            @Test
            void throws_write_condition_not_fulfilled_when_condition_is_fulfilled_but_should_not_be_so() {
                // Given
                DomainEvent event1 = new NameDefined(UUID.randomUUID().toString(), now, "John Doe");
                unconditionallyPersist(inMemoryEventStore, "name", Stream.of(event1));

                // When
                DomainEvent event2 = new NameWasChanged(UUID.randomUUID().toString(), now, "Jan Doe");
                Throwable throwable = catchThrowable(() -> conditionallyPersist(inMemoryEventStore, "name", streamVersion(not(eq(1L))), Stream.of(event2)));

                // Then
                assertThat(throwable).isExactlyInstanceOf(WriteConditionNotFulfilledException.class)
                        .hasMessage("WriteCondition was not fulfilled. Expected version not to be equal to 1 but was 1.");
            }
        }
    }

    private void unconditionallyPersist(EventStore inMemoryEventStore, String eventStreamId, List<DomainEvent> events) {
        unconditionallyPersist(inMemoryEventStore, eventStreamId, events.stream());
    }

    private void unconditionallyPersist(EventStore inMemoryEventStore, String eventStreamId, Stream<DomainEvent> events) {
        inMemoryEventStore.write(eventStreamId, events.map(convertDomainEventToCloudEvent(objectMapper)));
    }

    private void conditionallyPersist(EventStore inMemoryEventStore, String eventStreamId, WriteCondition writeCondition, Stream<DomainEvent> events) {
        inMemoryEventStore.write(eventStreamId, writeCondition, events.map(convertDomainEventToCloudEvent(objectMapper)));
    }

    private static Function<DomainEvent, CloudEvent> convertDomainEventToCloudEvent(ObjectMapper objectMapper) {
        return e -> CloudEventBuilder.v1()
                .withId(e.getEventId())
                .withSource(URI.create("http://name"))
                .withType(e.getClass().getName())
                .withTime(toLocalDateTime(e.getTimestamp()).atZone(UTC))
                .withSubject(e.getName())
                .withData(unchecked(objectMapper::writeValueAsBytes).apply(e))
                .build();
    }

    private Function<CloudEvent, DomainEvent> deserialize(ObjectMapper objectMapper) {
        return cloudEvent -> {
            try {
                return (DomainEvent) objectMapper.readValue(cloudEvent.getData(), Class.forName(cloudEvent.getType()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}