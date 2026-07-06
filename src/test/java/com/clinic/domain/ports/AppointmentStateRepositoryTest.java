package com.clinic.domain.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.CountryISO;
import com.clinic.domain.shared.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every AppointmentStateRepository implementation in this codebase (the Cosmos adapter and every
 * in-memory test fake) overrides the paged {@code findByInsuredId(insuredId, pageSize, cursor)}
 * default method itself, so the interface's own default implementation -- the single-page fallback
 * used by any future/minimal implementer that doesn't need real pagination -- was never actually
 * exercised. This test implements the interface without overriding it, to exercise the default
 * method's own body directly.
 */
class AppointmentStateRepositoryTest {

  private static class MinimalFake implements AppointmentStateRepository {
    private final List<Appointment> store = new ArrayList<>();

    @Override
    public void save(Appointment appointment) {
      store.add(appointment);
    }

    @Override
    public Optional<Appointment> findById(String appointmentId) {
      return store.stream().filter(a -> a.getAppointmentId().equals(appointmentId)).findFirst();
    }

    @Override
    public List<Appointment> findByInsuredId(String insuredId) {
      return store.stream().filter(a -> a.getInsuredId().equals(insuredId)).toList();
    }

    @Override
    public void updateStatus(Appointment appointment) {
      // no-op: not exercised by this test
    }
  }

  @Test
  void findByInsuredIdPaged_defaultImplementation_returnsSinglePageWithNullCursor() {
    MinimalFake repo = new MinimalFake();
    repo.save(new Appointment("apt-1", "insured-1", 42, CountryISO.PE));
    repo.save(new Appointment("apt-2", "insured-1", 43, CountryISO.PE));
    repo.save(new Appointment("apt-3", "insured-other", 44, CountryISO.CL));

    Page<Appointment> page = repo.findByInsuredId("insured-1", 10, "ignored-cursor");

    assertEquals(2, page.items.size());
    assertNull(page.nextCursor, "single-page fallback must never return a continuation token");
  }
}
