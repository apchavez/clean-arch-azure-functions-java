package com.clinic.infrastructure.repos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.AppointmentStatus;
import com.clinic.domain.entities.CountryISO;
import com.clinic.domain.shared.Page;
import com.clinic.infrastructure.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CosmosAppointmentStateRepositoryTest {

  private final CosmosContainer container = mock(CosmosContainer.class);
  private final Retry retry = ResilienceConfig.exponentialRetry("test-state-repo");
  private final CircuitBreaker circuitBreaker = ResilienceConfig.circuitBreaker("test-state-repo");
  private final CosmosAppointmentStateRepository repo =
      new CosmosAppointmentStateRepository(container, retry, circuitBreaker);

  private Appointment appointment() {
    Appointment a = new Appointment("apt-1", "insured-1", 42, CountryISO.PE);
    a.setContactEmail("insured@example.com");
    return a;
  }

  @SuppressWarnings("unchecked")
  private CosmosItemResponse<CosmosAppointmentStateRepository.AppointmentItem> mockItemResponse() {
    return mock(CosmosItemResponse.class);
  }

  @Test
  void save_createsItemInContainer() {
    when(container.createItem(any(CosmosAppointmentStateRepository.AppointmentItem.class)))
        .thenReturn(mockItemResponse());

    repo.save(appointment());

    verify(container).createItem(any(CosmosAppointmentStateRepository.AppointmentItem.class));
  }

  @Test
  void save_nullCreatedAt_mapsToNullItemField() {
    // toItem()'s "createdAt != null ? ... : null" ternary's null branch: createdAt is always set
    // by the Appointment constructor, so this is the one path that requires clearing it manually.
    Appointment a = appointment();
    a.setCreatedAt(null);
    ArgumentCaptor<CosmosAppointmentStateRepository.AppointmentItem> captor =
        ArgumentCaptor.forClass(CosmosAppointmentStateRepository.AppointmentItem.class);
    when(container.createItem(captor.capture())).thenReturn(mockItemResponse());

    repo.save(a);

    assertEquals(null, captor.getValue().createdAt);
  }

  @Test
  void save_completedAndCancelledAppointment_mapsBothTimestamps() {
    // toItem()'s completedAt/cancelledAt ternaries' non-null branch: the default test appointment
    // has neither set, so this exercises the "has a value" side for both. (Set directly rather
    // than via markCompleted()/markCancelled() since those enforce a PENDING-only precondition
    // that a single appointment can't satisfy twice — only the mapping is under test here.)
    Appointment a = appointment();
    a.setCompletedAt(java.time.Instant.parse("2026-06-28T16:00:00Z"));
    a.setCancelledAt(java.time.Instant.parse("2026-06-29T09:00:00Z"));
    ArgumentCaptor<CosmosAppointmentStateRepository.AppointmentItem> captor =
        ArgumentCaptor.forClass(CosmosAppointmentStateRepository.AppointmentItem.class);
    when(container.createItem(captor.capture())).thenReturn(mockItemResponse());

    repo.save(a);

    assertEquals("2026-06-28T16:00:00Z", captor.getValue().completedAt);
    assertEquals("2026-06-29T09:00:00Z", captor.getValue().cancelledAt);
  }

  @Test
  void findById_found_returnsMappedAppointment() {
    CosmosAppointmentStateRepository.AppointmentItem item =
        new CosmosAppointmentStateRepository.AppointmentItem();
    item.id = "apt-1";
    item.insuredId = "insured-1";
    item.scheduleId = 42;
    item.countryISO = "PE";
    item.status = "COMPLETED";
    item.contactEmail = "insured@example.com";
    item.createdAt = "2026-06-28T15:30:00Z";
    item.completedAt = "2026-06-28T16:00:00Z";
    item.cancelledAt = null;

    CosmosItemResponse<CosmosAppointmentStateRepository.AppointmentItem> response =
        mockItemResponse();
    when(response.getItem()).thenReturn(item);
    when(container.readItem(
            eq("apt-1"),
            any(PartitionKey.class),
            eq(CosmosAppointmentStateRepository.AppointmentItem.class)))
        .thenReturn(response);

    var found = repo.findById("apt-1");

    assertTrue(found.isPresent());
    assertEquals("apt-1", found.get().getAppointmentId());
    assertEquals("insured-1", found.get().getInsuredId());
    assertEquals(CountryISO.PE, found.get().getCountryISO());
    assertEquals(AppointmentStatus.COMPLETED, found.get().getStatus());
    assertEquals(java.time.Instant.parse("2026-06-28T15:30:00Z"), found.get().getCreatedAt());
    assertEquals(java.time.Instant.parse("2026-06-28T16:00:00Z"), found.get().getCompletedAt());
    assertEquals(null, found.get().getCancelledAt());
  }

  @Test
  void findById_found_mapsCancelledTimestampWhenPresent() {
    CosmosAppointmentStateRepository.AppointmentItem item =
        new CosmosAppointmentStateRepository.AppointmentItem();
    item.id = "apt-2";
    item.insuredId = "insured-2";
    item.scheduleId = 7;
    item.countryISO = "CL";
    item.status = "CANCELLED";
    item.cancelledAt = "2026-06-29T09:00:00Z";

    CosmosItemResponse<CosmosAppointmentStateRepository.AppointmentItem> response =
        mockItemResponse();
    when(response.getItem()).thenReturn(item);
    when(container.readItem(
            eq("apt-2"),
            any(PartitionKey.class),
            eq(CosmosAppointmentStateRepository.AppointmentItem.class)))
        .thenReturn(response);

    var found = repo.findById("apt-2");

    assertTrue(found.isPresent());
    assertEquals(AppointmentStatus.CANCELLED, found.get().getStatus());
    assertEquals(java.time.Instant.parse("2026-06-29T09:00:00Z"), found.get().getCancelledAt());
  }

  @Test
  void findById_notFound_returnsEmpty() {
    CosmosException notFound = mock(CosmosException.class);
    when(notFound.getStatusCode()).thenReturn(404);
    when(container.readItem(
            anyString(),
            any(PartitionKey.class),
            eq(CosmosAppointmentStateRepository.AppointmentItem.class)))
        .thenThrow(notFound);

    var found = repo.findById("missing");

    assertFalse(found.isPresent());
  }

  @Test
  void findById_otherCosmosError_wrapsInRuntimeException() {
    CosmosException serverError = mock(CosmosException.class);
    when(serverError.getStatusCode()).thenReturn(500);
    when(serverError.getMessage()).thenReturn("internal error");
    when(container.readItem(
            anyString(),
            any(PartitionKey.class),
            eq(CosmosAppointmentStateRepository.AppointmentItem.class)))
        .thenThrow(serverError);

    assertThrows(RuntimeException.class, () -> repo.findById("apt-1"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void findByInsuredId_returnsMappedList() {
    CosmosAppointmentStateRepository.AppointmentItem item =
        new CosmosAppointmentStateRepository.AppointmentItem();
    item.id = "apt-1";
    item.insuredId = "insured-1";
    item.scheduleId = 42;
    item.countryISO = "PE";
    item.status = "PENDING";

    CosmosPagedIterable<CosmosAppointmentStateRepository.AppointmentItem> paged =
        mock(CosmosPagedIterable.class);
    when(paged.stream()).thenReturn(java.util.stream.Stream.of(item));
    when(container.queryItems(
            any(com.azure.cosmos.models.SqlQuerySpec.class),
            any(com.azure.cosmos.models.CosmosQueryRequestOptions.class),
            eq(CosmosAppointmentStateRepository.AppointmentItem.class)))
        .thenReturn(paged);

    List<Appointment> results = repo.findByInsuredId("insured-1");

    assertEquals(1, results.size());
    assertEquals("apt-1", results.get(0).getAppointmentId());
  }

  @SuppressWarnings("unchecked")
  @Test
  void findByInsuredIdPaged_returnsPageWithContinuationToken() {
    CosmosAppointmentStateRepository.AppointmentItem item =
        new CosmosAppointmentStateRepository.AppointmentItem();
    item.id = "apt-1";
    item.insuredId = "insured-1";
    item.scheduleId = 42;
    item.countryISO = "CL";
    item.status = "COMPLETED";

    FeedResponse<CosmosAppointmentStateRepository.AppointmentItem> feedResponse =
        mock(FeedResponse.class);
    when(feedResponse.getResults()).thenReturn(List.of(item));
    when(feedResponse.getContinuationToken()).thenReturn("next-token");

    Iterator<FeedResponse<CosmosAppointmentStateRepository.AppointmentItem>> iterator =
        List.of(feedResponse).iterator();
    Iterable<FeedResponse<CosmosAppointmentStateRepository.AppointmentItem>> iterable =
        () -> iterator;

    CosmosPagedIterable<CosmosAppointmentStateRepository.AppointmentItem> paged =
        mock(CosmosPagedIterable.class);
    when(paged.iterableByPage("token-in", 10)).thenReturn(iterable);
    when(container.queryItems(
            any(com.azure.cosmos.models.SqlQuerySpec.class),
            any(com.azure.cosmos.models.CosmosQueryRequestOptions.class),
            eq(CosmosAppointmentStateRepository.AppointmentItem.class)))
        .thenReturn(paged);

    Page<Appointment> page = repo.findByInsuredId("insured-1", 10, "token-in");

    assertEquals(1, page.items.size());
    assertEquals("apt-1", page.items.get(0).getAppointmentId());
    assertEquals("next-token", page.nextCursor);
  }

  @SuppressWarnings("unchecked")
  @Test
  void findByInsuredIdPaged_noPages_returnsEmptyPage() {
    CosmosPagedIterable<CosmosAppointmentStateRepository.AppointmentItem> paged =
        mock(CosmosPagedIterable.class);
    when(paged.iterableByPage("token-in", 10))
        .thenReturn(List.<FeedResponse<CosmosAppointmentStateRepository.AppointmentItem>>of());
    when(container.queryItems(
            any(com.azure.cosmos.models.SqlQuerySpec.class),
            any(com.azure.cosmos.models.CosmosQueryRequestOptions.class),
            eq(CosmosAppointmentStateRepository.AppointmentItem.class)))
        .thenReturn(paged);

    Page<Appointment> page = repo.findByInsuredId("insured-1", 10, "token-in");

    assertTrue(page.items.isEmpty());
    assertEquals(null, page.nextCursor);
  }

  @Test
  void updateStatus_replacesItemInContainer() {
    when(container.replaceItem(
            any(CosmosAppointmentStateRepository.AppointmentItem.class),
            eq("apt-1"),
            any(PartitionKey.class),
            any()))
        .thenReturn(mockItemResponse());

    repo.updateStatus(appointment());

    verify(container)
        .replaceItem(
            any(CosmosAppointmentStateRepository.AppointmentItem.class),
            eq("apt-1"),
            any(PartitionKey.class),
            any());
  }

  @Test
  void ping_containerReadSucceeds_returnsUp() {
    when(container.read()).thenReturn(mock(com.azure.cosmos.models.CosmosContainerResponse.class));

    assertEquals("UP", repo.ping());
  }

  @Test
  void ping_containerReadThrows_returnsDownWithMessage() {
    when(container.read()).thenThrow(new RuntimeException("unreachable"));

    assertTrue(repo.ping().startsWith("DOWN: "));
  }
}
