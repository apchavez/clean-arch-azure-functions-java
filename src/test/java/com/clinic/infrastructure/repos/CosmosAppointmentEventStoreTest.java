package com.clinic.infrastructure.repos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.clinic.domain.entities.AppointmentEvent;
import com.clinic.infrastructure.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CosmosAppointmentEventStoreTest {

  private final CosmosContainer container = mock(CosmosContainer.class);
  private final Retry retry = ResilienceConfig.exponentialRetry("test-event-store");
  private final CircuitBreaker circuitBreaker = ResilienceConfig.circuitBreaker("test-event-store");
  private final CosmosAppointmentEventStore store =
      new CosmosAppointmentEventStore(container, retry, circuitBreaker);

  private AppointmentEvent event(String eventId, Instant occurredAt) {
    AppointmentEvent e = new AppointmentEvent();
    e.setEventId(eventId);
    e.setAppointmentId("apt-1");
    e.setEventType("APPOINTMENT_CREATED");
    e.setInsuredId("insured-1");
    e.setScheduleId(42);
    e.setCountryISO("PE");
    e.setStatus("PENDING");
    e.setOccurredAt(occurredAt);
    return e;
  }

  @SuppressWarnings("unchecked")
  @Test
  void append_createsItemInContainer() {
    CosmosItemResponse<CosmosAppointmentEventStore.EventItem> response =
        mock(CosmosItemResponse.class);
    when(container.createItem(
            any(CosmosAppointmentEventStore.EventItem.class), any(PartitionKey.class), any()))
        .thenReturn(response);

    store.append(event("evt-1", Instant.now()));

    verify(container)
        .createItem(
            any(CosmosAppointmentEventStore.EventItem.class), any(PartitionKey.class), any());
  }

  @SuppressWarnings("unchecked")
  @Test
  void append_nullOccurredAt_mapsToNullItemField() {
    // toItem()'s "e.getOccurredAt() != null ? ... : null" ternary null branch — every other test
    // supplies a real Instant, so the "absent" side was never exercised.
    CosmosItemResponse<CosmosAppointmentEventStore.EventItem> response =
        mock(CosmosItemResponse.class);
    ArgumentCaptor<CosmosAppointmentEventStore.EventItem> captor =
        ArgumentCaptor.forClass(CosmosAppointmentEventStore.EventItem.class);
    when(container.createItem(captor.capture(), any(PartitionKey.class), any()))
        .thenReturn(response);

    store.append(event("evt-1", null));

    assertEquals(null, captor.getValue().occurredAt);
  }

  @SuppressWarnings("unchecked")
  @Test
  void findByAppointmentId_itemWithNullOccurredAt_mapsToNullDomainField() {
    // toDomain()'s "if (item.occurredAt != null) ..." false branch — the sorted-fetch test below
    // always has occurredAt set on every item, so this was never exercised.
    CosmosAppointmentEventStore.EventItem item = toItem(event("evt-1", Instant.now()));
    item.occurredAt = null;

    CosmosPagedIterable<CosmosAppointmentEventStore.EventItem> paged =
        mock(CosmosPagedIterable.class);
    when(paged.stream()).thenReturn(Stream.of(item));
    when(container.queryItems(
            any(com.azure.cosmos.models.SqlQuerySpec.class),
            any(com.azure.cosmos.models.CosmosQueryRequestOptions.class),
            eq(CosmosAppointmentEventStore.EventItem.class)))
        .thenReturn(paged);

    List<AppointmentEvent> events = store.findByAppointmentId("apt-1");

    assertEquals(1, events.size());
    assertEquals(null, events.get(0).getOccurredAt());
  }

  @SuppressWarnings("unchecked")
  @Test
  void findByAppointmentId_returnsEventsSortedByOccurredAt() {
    CosmosAppointmentEventStore.EventItem later =
        toItem(event("evt-2", Instant.parse("2026-01-02T00:00:00Z")));
    CosmosAppointmentEventStore.EventItem earlier =
        toItem(event("evt-1", Instant.parse("2026-01-01T00:00:00Z")));

    CosmosPagedIterable<CosmosAppointmentEventStore.EventItem> paged =
        mock(CosmosPagedIterable.class);
    // Intentionally out of order — the store must sort by occurredAt itself.
    when(paged.stream()).thenReturn(Stream.of(later, earlier));
    when(container.queryItems(
            any(com.azure.cosmos.models.SqlQuerySpec.class),
            any(com.azure.cosmos.models.CosmosQueryRequestOptions.class),
            eq(CosmosAppointmentEventStore.EventItem.class)))
        .thenReturn(paged);

    List<AppointmentEvent> events = store.findByAppointmentId("apt-1");

    assertEquals(2, events.size());
    assertEquals("evt-1", events.get(0).getEventId());
    assertEquals("evt-2", events.get(1).getEventId());
  }

  private CosmosAppointmentEventStore.EventItem toItem(AppointmentEvent e) {
    CosmosAppointmentEventStore.EventItem item = new CosmosAppointmentEventStore.EventItem();
    item.id = e.getEventId();
    item.appointmentId = e.getAppointmentId();
    item.eventType = e.getEventType();
    item.insuredId = e.getInsuredId();
    item.scheduleId = e.getScheduleId();
    item.countryISO = e.getCountryISO();
    item.status = e.getStatus();
    item.occurredAt = e.getOccurredAt().toString();
    return item;
  }
}
