package com.clinic.infrastructure.notifications;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.CountryISO;
import org.junit.jupiter.api.Test;

class NoOpAppointmentNotifierTest {

  private final NoOpAppointmentNotifier notifier = new NoOpAppointmentNotifier();
  private final Appointment appointment = new Appointment("apt-1", "insured-1", 42, CountryISO.PE);

  @Test
  void notifyCompleted_doesNothing() {
    assertDoesNotThrow(() -> notifier.notifyCompleted(appointment));
  }

  @Test
  void notifyCancelled_doesNothing() {
    assertDoesNotThrow(() -> notifier.notifyCancelled(appointment));
  }

  @Test
  void notifyRescheduled_doesNothing() {
    Appointment newAppointment = new Appointment("apt-2", "insured-1", 43, CountryISO.PE);
    assertDoesNotThrow(() -> notifier.notifyRescheduled(appointment, newAppointment));
  }
}
