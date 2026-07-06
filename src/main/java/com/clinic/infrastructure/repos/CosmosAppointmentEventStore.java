package com.clinic.infrastructure.repos;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.clinic.domain.entities.AppointmentEvent;
import com.clinic.domain.ports.AppointmentEventStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Cosmos DB adapter for the append-only event store. Writes to the appointment-events container
 * (separate from the state container) using appointmentId as the partition key so all events for
 * one appointment are co-located and can be retrieved in a single cross-partition-free query.
 *
 * <p>Documents are written once and never updated — immutability is enforced by always using
 * createItem (never replaceItem).
 */
public class CosmosAppointmentEventStore implements AppointmentEventStore {

  private final CosmosContainer container;
  private final Retry retry;
  private final CircuitBreaker circuitBreaker;

  public CosmosAppointmentEventStore(
      String endpoint,
      String databaseName,
      String containerName,
      Retry retry,
      CircuitBreaker circuitBreaker) {
    this(buildContainer(endpoint, databaseName, containerName), retry, circuitBreaker);
  }

  /** Visible for testing — lets tests inject a mocked {@link CosmosContainer}. */
  CosmosAppointmentEventStore(
      CosmosContainer container, Retry retry, CircuitBreaker circuitBreaker) {
    this.container = container;
    this.retry = retry;
    this.circuitBreaker = circuitBreaker;
  }

  private static CosmosContainer buildContainer(
      String endpoint, String databaseName, String containerName) {
    CosmosClient client =
        new CosmosClientBuilder()
            .endpoint(endpoint)
            .credential(new DefaultAzureCredentialBuilder().build())
            .buildClient();
    return client.getDatabase(databaseName).getContainer(containerName);
  }

  @Override
  public void append(AppointmentEvent event) {
    resilient(
        () ->
            container.createItem(toItem(event), new PartitionKey(event.getAppointmentId()), null));
  }

  @Override
  public List<AppointmentEvent> findByAppointmentId(String appointmentId) {
    return resilient(
        () -> {
          SqlQuerySpec spec =
              new SqlQuerySpec(
                  "SELECT * FROM c WHERE c.appointmentId = @appointmentId ORDER BY c.occurredAt ASC",
                  new SqlParameter("@appointmentId", appointmentId));
          return container
              .queryItems(spec, new CosmosQueryRequestOptions(), EventItem.class)
              .stream()
              .map(this::toDomain)
              .sorted(Comparator.comparing(AppointmentEvent::getOccurredAt))
              .toList();
        });
  }

  private EventItem toItem(AppointmentEvent e) {
    EventItem item = new EventItem();
    item.id = e.getEventId();
    item.appointmentId = e.getAppointmentId();
    item.eventType = e.getEventType();
    item.insuredId = e.getInsuredId();
    item.scheduleId = e.getScheduleId();
    item.countryISO = e.getCountryISO();
    item.status = e.getStatus();
    item.occurredAt = e.getOccurredAt() != null ? e.getOccurredAt().toString() : null;
    return item;
  }

  private AppointmentEvent toDomain(EventItem item) {
    AppointmentEvent e = new AppointmentEvent();
    e.setEventId(item.id);
    e.setAppointmentId(item.appointmentId);
    e.setEventType(item.eventType);
    e.setInsuredId(item.insuredId);
    e.setScheduleId(item.scheduleId);
    e.setCountryISO(item.countryISO);
    e.setStatus(item.status);
    if (item.occurredAt != null) e.setOccurredAt(Instant.parse(item.occurredAt));
    return e;
  }

  // Every call site in this class (append/findByAppointmentId) always returns a value from
  // Cosmos (createItem's response, the query results), so only the Supplier overload is ever
  // exercised — a Runnable overload existed here previously but was genuinely dead code (it's
  // not part of the AppointmentEventStore contract and was never invoked), so it was removed
  // rather than tested with reflection just to satisfy coverage.
  private <T> T resilient(Supplier<T> operation) {
    return Retry.decorateSupplier(retry, circuitBreaker.decorateSupplier(operation)).get();
  }

  public static class EventItem {
    public String id;
    public String appointmentId;
    public String eventType;
    public String insuredId;
    public int scheduleId;
    public String countryISO;
    public String status;
    public String occurredAt;
  }
}
