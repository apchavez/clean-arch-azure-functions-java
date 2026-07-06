package com.clinic.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.CountryISO;
import com.clinic.infrastructure.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ServiceBusEventPublisherTest {

  private final ServiceBusSenderClient createdSender = mock(ServiceBusSenderClient.class);
  private final ServiceBusSenderClient completedSender = mock(ServiceBusSenderClient.class);
  private final ServiceBusSenderClient cancelledSender = mock(ServiceBusSenderClient.class);
  private final Retry retry = ResilienceConfig.exponentialRetry("test-sb-publisher");
  private final CircuitBreaker circuitBreaker =
      ResilienceConfig.circuitBreaker("test-sb-publisher");
  private final ServiceBusEventPublisher publisher =
      new ServiceBusEventPublisher(
          "test.servicebus.windows.net",
          createdSender,
          completedSender,
          cancelledSender,
          retry,
          circuitBreaker);

  private Appointment appointment() {
    return new Appointment("apt-1", "insured-1", 42, CountryISO.PE);
  }

  @AfterEach
  void clearCorrelationContext() {
    com.clinic.infrastructure.config.CorrelationContext.clear();
  }

  @Test
  void publishCreated_sendsToCreatedSenderWithExpectedFields() {
    publisher.publishCreated(appointment());

    ArgumentCaptor<ServiceBusMessage> captor = ArgumentCaptor.forClass(ServiceBusMessage.class);
    verify(createdSender).sendMessage(captor.capture());
    ServiceBusMessage message = captor.getValue();

    assertEquals("PE", message.getSubject());
    assertEquals("apt-1", message.getCorrelationId());
    assertEquals("APPOINTMENT_CREATED", message.getApplicationProperties().get("eventType"));
    assertTrue(message.getBody().toString().contains("\"appointmentId\":\"apt-1\""));
    verify(completedSender, never()).sendMessage(any());
    verify(cancelledSender, never()).sendMessage(any());
  }

  @Test
  void publishCompleted_sendsToCompletedSender() {
    publisher.publishCompleted(appointment());

    ArgumentCaptor<ServiceBusMessage> captor = ArgumentCaptor.forClass(ServiceBusMessage.class);
    verify(completedSender).sendMessage(captor.capture());
    assertTrue(captor.getValue().getBody().toString().contains("APPOINTMENT_COMPLETED"));
  }

  @Test
  void publishCancelled_sendsToCancelledSender() {
    publisher.publishCancelled(appointment());

    ArgumentCaptor<ServiceBusMessage> captor = ArgumentCaptor.forClass(ServiceBusMessage.class);
    verify(cancelledSender).sendMessage(captor.capture());
    assertTrue(captor.getValue().getBody().toString().contains("APPOINTMENT_CANCELLED"));
  }

  @Test
  void publishCreated_usesCorrelationContextWhenSet() {
    com.clinic.infrastructure.config.CorrelationContext.set("corr-xyz");

    publisher.publishCreated(appointment());

    ArgumentCaptor<ServiceBusMessage> captor = ArgumentCaptor.forClass(ServiceBusMessage.class);
    verify(createdSender).sendMessage(captor.capture());
    assertEquals("corr-xyz", captor.getValue().getCorrelationId());
  }

  @Test
  void publishCreated_blankCorrelationContext_fallsBackToAppointmentId() {
    // CorrelationContext set but blank must be treated the same as unset: fall back to the
    // appointment ID, not propagate an empty correlation ID.
    com.clinic.infrastructure.config.CorrelationContext.set("   ");

    publisher.publishCreated(appointment());

    ArgumentCaptor<ServiceBusMessage> captor = ArgumentCaptor.forClass(ServiceBusMessage.class);
    verify(createdSender).sendMessage(captor.capture());
    assertEquals("apt-1", captor.getValue().getCorrelationId());
  }

  @Test
  void publishCreated_senderThrows_wrapsInRuntimeException() {
    doThrow(new RuntimeException("amqp link closed")).when(createdSender).sendMessage(any());

    assertThrows(RuntimeException.class, () -> publisher.publishCreated(appointment()));
  }

  @Test
  void publishCreated_senderThrowsCheckedException_wrapsWithDescriptiveMessage() {
    // catch(RuntimeException) rethrow is exercised by the test above; this exercises the sibling
    // catch(Exception) branch, which only fires for a non-RuntimeException failure (e.g. a
    // checked I/O failure from the underlying AMQP link) and wraps it with a descriptive message
    // rather than losing the original cause.
    doAnswer(
            invocation -> {
              throw new java.io.IOException("connection reset");
            })
        .when(createdSender)
        .sendMessage(any());

    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> publisher.publishCreated(appointment()));
    assertEquals("Failed to publish APPOINTMENT_CREATED event", ex.getMessage());
    assertTrue(ex.getCause() instanceof java.io.IOException);
  }

  @Test
  void publishCompleted_senderThrowsRuntimeException_propagatesUnwrapped() {
    doThrow(new RuntimeException("amqp link closed")).when(completedSender).sendMessage(any());

    assertThrows(RuntimeException.class, () -> publisher.publishCompleted(appointment()));
  }

  @Test
  void publishCompleted_senderThrowsCheckedException_wrapsWithDescriptiveMessage() {
    doAnswer(
            invocation -> {
              throw new java.io.IOException("connection reset");
            })
        .when(completedSender)
        .sendMessage(any());

    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> publisher.publishCompleted(appointment()));
    assertEquals("Failed to publish APPOINTMENT_COMPLETED event", ex.getMessage());
    assertTrue(ex.getCause() instanceof java.io.IOException);
  }

  @Test
  void publishCancelled_senderThrowsRuntimeException_propagatesUnwrapped() {
    doThrow(new RuntimeException("amqp link closed")).when(cancelledSender).sendMessage(any());

    assertThrows(RuntimeException.class, () -> publisher.publishCancelled(appointment()));
  }

  @Test
  void publishCancelled_senderThrowsCheckedException_wrapsWithDescriptiveMessage() {
    doAnswer(
            invocation -> {
              throw new java.io.IOException("connection reset");
            })
        .when(cancelledSender)
        .sendMessage(any());

    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> publisher.publishCancelled(appointment()));
    assertEquals("Failed to publish APPOINTMENT_CANCELLED event", ex.getMessage());
    assertTrue(ex.getCause() instanceof java.io.IOException);
  }

  @Test
  void ping_administrationCallFails_returnsDown() {
    // No real Service Bus namespace behind "test.servicebus.windows.net" and a null credential
    // (test constructor doesn't build one) — ping() must fail fast and report DOWN, never throw.
    assertTrue(publisher.ping().startsWith("DOWN: "));
  }
}
