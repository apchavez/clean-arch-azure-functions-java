package com.clinic.application;

import static org.junit.jupiter.api.Assertions.*;

import com.clinic.application.usecases.CreateAppointmentUseCase;
import com.clinic.application.usecases.GetAppointmentsUseCase;
import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.AppointmentEvent;
import com.clinic.domain.entities.CountryISO;
import com.clinic.domain.ports.AppointmentEventPublisher;
import com.clinic.domain.ports.AppointmentEventStore;
import com.clinic.domain.ports.AppointmentStateRepository;
import com.clinic.domain.shared.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GetAppointmentsUseCaseTest {

  static class InMemoryState implements AppointmentStateRepository {
    final List<Appointment> store = new ArrayList<>();

    public void save(Appointment a) {
      store.add(a);
    }

    public Optional<Appointment> findById(String id) {
      return store.stream().filter(x -> x.getAppointmentId().equals(id)).findFirst();
    }

    public List<Appointment> findByInsuredId(String insuredId) {
      return store.stream().filter(x -> x.getInsuredId().equals(insuredId)).toList();
    }

    public void updateStatus(Appointment a) {}

    int lastRequestedPageSize = -1;

    @Override
    public Page<Appointment> findByInsuredId(String insuredId, int pageSize, String cursor) {
      this.lastRequestedPageSize = pageSize;
      return new Page<>(findByInsuredId(insuredId), null);
    }
  }

  static class NoOpPublisher implements AppointmentEventPublisher {
    public void publishCreated(Appointment a) {}

    public void publishCompleted(Appointment a) {}

    public void publishCancelled(Appointment a) {}
  }

  static class NoOpEventStore implements AppointmentEventStore {
    public void append(AppointmentEvent e) {}

    public java.util.List<AppointmentEvent> findByAppointmentId(String id) {
      return java.util.List.of();
    }
  }

  @Test
  void returnsAllAppointmentsForInsured() {
    InMemoryState state = new InMemoryState();
    CreateAppointmentUseCase create =
        new CreateAppointmentUseCase(state, new NoOpPublisher(), new NoOpEventStore());
    GetAppointmentsUseCase query = new GetAppointmentsUseCase(state);

    create.execute("ins-10", 1, CountryISO.PE, null);
    create.execute("ins-10", 2, CountryISO.CL, null);
    create.execute("ins-99", 3, CountryISO.PE, null);

    List<Appointment> results = query.byInsured("ins-10");
    assertEquals(2, results.size());
    assertTrue(results.stream().allMatch(a -> "ins-10".equals(a.getInsuredId())));
  }

  @Test
  void returnsEmptyListWhenNoAppointmentsExist() {
    InMemoryState state = new InMemoryState();
    GetAppointmentsUseCase query = new GetAppointmentsUseCase(state);

    List<Appointment> results = query.byInsured("unknown");
    assertNotNull(results);
    assertTrue(results.isEmpty());
  }

  // --- paginated byInsured(insuredId, pageSize, cursor): size = (pageSize < 1 || pageSize >
  // MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : pageSize — both operands of the OR, each side.

  @Test
  void paginatedByInsuredUsesRequestedPageSizeWhenWithinValidRange() {
    InMemoryState state = new InMemoryState();
    GetAppointmentsUseCase query = new GetAppointmentsUseCase(state);

    Page<Appointment> page = query.byInsured("ins-10", 5, null);

    assertNotNull(page);
    assertEquals(5, state.lastRequestedPageSize);
  }

  @Test
  void paginatedByInsuredFallsBackToDefaultWhenPageSizeIsZero() {
    InMemoryState state = new InMemoryState();
    GetAppointmentsUseCase query = new GetAppointmentsUseCase(state);

    query.byInsured("ins-10", 0, null);

    assertEquals(GetAppointmentsUseCase.DEFAULT_PAGE_SIZE, state.lastRequestedPageSize);
  }

  @Test
  void paginatedByInsuredFallsBackToDefaultWhenPageSizeIsNegative() {
    InMemoryState state = new InMemoryState();
    GetAppointmentsUseCase query = new GetAppointmentsUseCase(state);

    query.byInsured("ins-10", -1, null);

    assertEquals(GetAppointmentsUseCase.DEFAULT_PAGE_SIZE, state.lastRequestedPageSize);
  }

  @Test
  void paginatedByInsuredFallsBackToDefaultWhenPageSizeExceedsMax() {
    InMemoryState state = new InMemoryState();
    GetAppointmentsUseCase query = new GetAppointmentsUseCase(state);

    query.byInsured("ins-10", GetAppointmentsUseCase.MAX_PAGE_SIZE + 1, null);

    assertEquals(GetAppointmentsUseCase.DEFAULT_PAGE_SIZE, state.lastRequestedPageSize);
  }

  @Test
  void paginatedByInsuredAcceptsPageSizeExactlyAtMax() {
    InMemoryState state = new InMemoryState();
    GetAppointmentsUseCase query = new GetAppointmentsUseCase(state);

    query.byInsured("ins-10", GetAppointmentsUseCase.MAX_PAGE_SIZE, null);

    assertEquals(GetAppointmentsUseCase.MAX_PAGE_SIZE, state.lastRequestedPageSize);
  }
}
