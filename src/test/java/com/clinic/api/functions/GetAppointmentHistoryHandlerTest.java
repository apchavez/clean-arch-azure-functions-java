package com.clinic.api.functions;

import static com.clinic.api.functions.HandlerTestSupport.mockAuthenticatedRequest;
import static com.clinic.api.functions.HandlerTestSupport.mockContext;
import static com.clinic.api.functions.HandlerTestSupport.mockRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.AppointmentEvent;
import com.clinic.domain.entities.CountryISO;
import com.clinic.domain.ports.AppointmentEventStore;
import com.clinic.infrastructure.config.AppContext;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class GetAppointmentHistoryHandlerTest {

  private final GetAppointmentHistoryHandler handler = new GetAppointmentHistoryHandler();
  private final ExecutionContext context = mockContext();

  @Test
  void run_missingToken_returns401() {
    HttpRequestMessage<Optional<String>> request = mockRequest(Map.of(), Map.of(), null);

    HttpResponseMessage response = handler.run(request, "apt-1", context);

    assertEquals(401, response.getStatus().value());
  }

  @Test
  void run_blankAppointmentId_returns400() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);

    HttpResponseMessage response = handler.run(request, "", context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_nullAppointmentId_returns400() {
    // The other half of "appointmentId == null || appointmentId.isBlank()" — the blank-string
    // test above only exercises isBlank(); appointmentId itself being null never gets exercised.
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);

    HttpResponseMessage response = handler.run(request, null, context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_validRequest_returns200WithEvents() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);
    Appointment appointment = new Appointment("apt-1", "12345", 10, CountryISO.PE);
    List<AppointmentEvent> events =
        List.of(AppointmentEvent.of("APPOINTMENT_CREATED", appointment));

    AppointmentEventStore eventStore = org.mockito.Mockito.mock(AppointmentEventStore.class);
    when(eventStore.findByAppointmentId(anyString())).thenReturn(events);

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::eventStore).thenReturn(eventStore);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(200, response.getStatus().value());
      assertTrue(response.getBody().toString().contains("APPOINTMENT_CREATED"));
    }
  }

  @Test
  void run_eventStoreThrows_returns500() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);
    AppointmentEventStore eventStore = org.mockito.Mockito.mock(AppointmentEventStore.class);
    when(eventStore.findByAppointmentId(anyString()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::eventStore).thenReturn(eventStore);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(500, response.getStatus().value());
    }
  }
}
