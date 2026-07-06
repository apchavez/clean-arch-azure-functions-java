package com.clinic.infrastructure.notifications;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstructionWithAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.core.util.polling.SyncPoller;
import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.CountryISO;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class AcsAppointmentNotifierTest {

  @SuppressWarnings("unchecked")
  private final EmailClient emailClient = mock(EmailClient.class);

  private final AcsAppointmentNotifier notifier =
      new AcsAppointmentNotifier(emailClient, "sender@example.com");

  @SuppressWarnings("unchecked")
  private void stubSuccessfulSend() {
    SyncPoller<EmailSendResult, EmailSendResult> poller = mock(SyncPoller.class);
    when(emailClient.beginSend(any(EmailMessage.class))).thenReturn(poller);
  }

  private Appointment appointmentWithEmail() {
    Appointment a = new Appointment("apt-1", "insured-1", 42, CountryISO.PE);
    a.setContactEmail("insured@example.com");
    return a;
  }

  private Appointment appointmentWithoutEmail() {
    return new Appointment("apt-2", "insured-2", 43, CountryISO.CL);
  }

  @Test
  void notifyCompleted_hasEmail_sendsMessage() {
    stubSuccessfulSend();

    notifier.notifyCompleted(appointmentWithEmail());

    verify(emailClient).beginSend(any(EmailMessage.class));
  }

  @Test
  void notifyCompleted_noEmail_doesNotSend() {
    notifier.notifyCompleted(appointmentWithoutEmail());

    verify(emailClient, never()).beginSend(any());
  }

  @Test
  void notifyCompleted_blankEmail_doesNotSend() {
    // hasEmail()'s "contactEmail != null && !isBlank()": the no-email test above only exercises
    // the null half of the guard. A present-but-blank contactEmail must be treated the same way.
    Appointment a = appointmentWithoutEmail();
    a.setContactEmail("   ");

    notifier.notifyCompleted(a);

    verify(emailClient, never()).beginSend(any());
  }

  @Test
  void notifyCancelled_hasEmail_sendsMessage() {
    stubSuccessfulSend();

    notifier.notifyCancelled(appointmentWithEmail());

    verify(emailClient).beginSend(any(EmailMessage.class));
  }

  @Test
  void notifyCancelled_noEmail_doesNotSend() {
    notifier.notifyCancelled(appointmentWithoutEmail());

    verify(emailClient, never()).beginSend(any());
  }

  @Test
  void notifyRescheduled_oldHasEmail_sendsMessage() {
    stubSuccessfulSend();

    notifier.notifyRescheduled(appointmentWithEmail(), appointmentWithoutEmail());

    verify(emailClient).beginSend(any(EmailMessage.class));
  }

  @Test
  void notifyRescheduled_oldHasNoEmail_doesNotSend() {
    notifier.notifyRescheduled(appointmentWithoutEmail(), appointmentWithEmail());

    verify(emailClient, never()).beginSend(any());
  }

  @Test
  void notifyCompleted_sendThrows_isSwallowed() {
    when(emailClient.beginSend(any(EmailMessage.class)))
        .thenThrow(new RuntimeException("ACS unavailable"));

    assertDoesNotThrow(() -> notifier.notifyCompleted(appointmentWithEmail()));
  }

  @Test
  void publicConstructor_wiresRealEmailClientBuilder() {
    // The (endpoint, senderAddress) constructor builds a real EmailClientBuilder + Managed
    // Identity credential. Intercept the builder construction (deep-stubbed) so the object graph
    // is exercised without a real ACS endpoint/network call.
    try (MockedConstruction<EmailClientBuilder> builder =
        mockConstructionWithAnswer(EmailClientBuilder.class, RETURNS_DEEP_STUBS)) {
      AcsAppointmentNotifier realNotifier =
          new AcsAppointmentNotifier("https://acs.example.com", "sender@example.com");

      assertNotNull(realNotifier);
    }
  }
}
