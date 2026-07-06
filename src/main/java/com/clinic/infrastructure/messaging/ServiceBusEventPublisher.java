package com.clinic.infrastructure.messaging;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.administration.ServiceBusAdministrationClientBuilder;
import com.clinic.domain.entities.Appointment;
import com.clinic.domain.ports.AppointmentEventPublisher;
import com.clinic.infrastructure.config.CorrelationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Service Bus adapter implementing the event publisher port. Azure equivalent of the AWS project's
 * SNS+SQS / EventBridge publishing.
 *
 * <p>Authenticates via Managed Identity (DefaultAzureCredential) using the fully-qualified
 * namespace name (SERVICEBUS_FQNS). No connection string stored in config. Uses a topic so multiple
 * subscribers (country workers) can fan out.
 */
public class ServiceBusEventPublisher implements AppointmentEventPublisher {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ServiceBusSenderClient createdSender;
  private final ServiceBusSenderClient completedSender;
  private final ServiceBusSenderClient cancelledSender;
  private final String fullyQualifiedNamespace;
  private final TokenCredential credential;
  private final Retry retry;
  private final CircuitBreaker circuitBreaker;

  public ServiceBusEventPublisher(
      String fullyQualifiedNamespace,
      String createdTopic,
      String completedTopic,
      String cancelledTopic,
      Retry retry,
      CircuitBreaker circuitBreaker) {
    this.fullyQualifiedNamespace = fullyQualifiedNamespace;
    this.retry = retry;
    this.circuitBreaker = circuitBreaker;
    this.credential = new DefaultAzureCredentialBuilder().build();
    this.createdSender = buildSender(fullyQualifiedNamespace, credential, createdTopic);
    this.completedSender = buildSender(fullyQualifiedNamespace, credential, completedTopic);
    this.cancelledSender = buildSender(fullyQualifiedNamespace, credential, cancelledTopic);
  }

  /** Visible for testing — lets tests inject mocked {@link ServiceBusSenderClient}s. */
  ServiceBusEventPublisher(
      String fullyQualifiedNamespace,
      ServiceBusSenderClient createdSender,
      ServiceBusSenderClient completedSender,
      ServiceBusSenderClient cancelledSender,
      Retry retry,
      CircuitBreaker circuitBreaker) {
    this.fullyQualifiedNamespace = fullyQualifiedNamespace;
    this.credential = null;
    this.createdSender = createdSender;
    this.completedSender = completedSender;
    this.cancelledSender = cancelledSender;
    this.retry = retry;
    this.circuitBreaker = circuitBreaker;
  }

  private static ServiceBusSenderClient buildSender(
      String fullyQualifiedNamespace, TokenCredential credential, String topic) {
    return new ServiceBusClientBuilder()
        .fullyQualifiedNamespace(fullyQualifiedNamespace)
        .credential(credential)
        .sender()
        .topicName(topic)
        .buildClient();
  }

  public String ping() {
    try {
      new ServiceBusAdministrationClientBuilder()
          .endpoint("https://" + fullyQualifiedNamespace)
          .credential(credential)
          .buildClient()
          .getNamespaceProperties();
      return "UP";
    } catch (Exception e) {
      return "DOWN: " + e.getMessage();
    }
  }

  @Override
  public void publishCreated(Appointment a) {
    resilient(
        () -> {
          try {
            String correlationId = correlationId(a);
            ObjectNode node =
                MAPPER
                    .createObjectNode()
                    .put("eventType", "APPOINTMENT_CREATED")
                    .put("appointmentId", a.getAppointmentId())
                    .put("correlationId", correlationId)
                    .put("insuredId", a.getInsuredId())
                    .put("scheduleId", a.getScheduleId())
                    .put("countryISO", a.getCountryISO().name())
                    .put("occurredAt", Instant.now().toString());
            ServiceBusMessage message = new ServiceBusMessage(MAPPER.writeValueAsString(node));
            message.setSubject(a.getCountryISO().name());
            message.setCorrelationId(correlationId);
            message.getApplicationProperties().put("eventType", "APPOINTMENT_CREATED");
            message.getApplicationProperties().put("countryISO", a.getCountryISO().name());
            createdSender.sendMessage(message);
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new RuntimeException("Failed to publish APPOINTMENT_CREATED event", e);
          }
        });
  }

  @Override
  public void publishCompleted(Appointment a) {
    resilient(
        () -> {
          try {
            String correlationId = correlationId(a);
            ObjectNode node =
                MAPPER
                    .createObjectNode()
                    .put("eventType", "APPOINTMENT_COMPLETED")
                    .put("appointmentId", a.getAppointmentId())
                    .put("correlationId", correlationId)
                    .put("insuredId", a.getInsuredId())
                    .put("countryISO", a.getCountryISO().name())
                    .put("occurredAt", Instant.now().toString());
            ServiceBusMessage message = new ServiceBusMessage(MAPPER.writeValueAsString(node));
            message.setSubject(a.getCountryISO().name());
            message.setCorrelationId(correlationId);
            message.getApplicationProperties().put("eventType", "APPOINTMENT_COMPLETED");
            message.getApplicationProperties().put("countryISO", a.getCountryISO().name());
            completedSender.sendMessage(message);
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new RuntimeException("Failed to publish APPOINTMENT_COMPLETED event", e);
          }
        });
  }

  @Override
  public void publishCancelled(Appointment a) {
    resilient(
        () -> {
          try {
            String correlationId = correlationId(a);
            ObjectNode node =
                MAPPER
                    .createObjectNode()
                    .put("eventType", "APPOINTMENT_CANCELLED")
                    .put("appointmentId", a.getAppointmentId())
                    .put("correlationId", correlationId)
                    .put("insuredId", a.getInsuredId())
                    .put("countryISO", a.getCountryISO().name())
                    .put("occurredAt", Instant.now().toString());
            ServiceBusMessage message = new ServiceBusMessage(MAPPER.writeValueAsString(node));
            message.setSubject(a.getCountryISO().name());
            message.setCorrelationId(correlationId);
            message.getApplicationProperties().put("eventType", "APPOINTMENT_CANCELLED");
            message.getApplicationProperties().put("countryISO", a.getCountryISO().name());
            cancelledSender.sendMessage(message);
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new RuntimeException("Failed to publish APPOINTMENT_CANCELLED event", e);
          }
        });
  }

  private static String correlationId(Appointment a) {
    String ctx = CorrelationContext.get();
    return (ctx != null && !ctx.isBlank()) ? ctx : a.getAppointmentId();
  }

  private <T> T resilient(Supplier<T> operation) {
    return Retry.decorateSupplier(retry, circuitBreaker.decorateSupplier(operation)).get();
  }

  private void resilient(Runnable operation) {
    resilient(
        () -> {
          operation.run();
          return null;
        });
  }
}
