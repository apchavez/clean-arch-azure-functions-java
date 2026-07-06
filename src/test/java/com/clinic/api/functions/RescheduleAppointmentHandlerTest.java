package com.clinic.api.functions;

import static com.clinic.api.functions.HandlerTestSupport.mockAuthenticatedRequest;
import static com.clinic.api.functions.HandlerTestSupport.mockContext;
import static com.clinic.api.functions.HandlerTestSupport.mockRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.clinic.application.usecases.RescheduleAppointmentUseCase;
import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.CountryISO;
import com.clinic.infrastructure.config.AppContext;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class RescheduleAppointmentHandlerTest {

  private final RescheduleAppointmentHandler handler = new RescheduleAppointmentHandler();
  private final ExecutionContext context = mockContext();

  @Test
  void run_missingToken_returns401() {
    HttpRequestMessage<Optional<String>> request =
        mockRequest(Map.of(), Map.of(), "{\"newScheduleId\":42}");

    HttpResponseMessage response = handler.run(request, "apt-1", context);

    assertEquals(401, response.getStatus().value());
  }

  @Test
  void run_blankAppointmentId_returns400() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":42}");

    HttpResponseMessage response = handler.run(request, "", context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_nullAppointmentId_returns400() {
    // The path-bound appointmentId itself being null (vs. merely blank) is the other half of the
    // "appointmentId == null || appointmentId.isBlank()" guard.
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":42}");

    HttpResponseMessage response = handler.run(request, null, context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_missingBody_returns400() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest("");

    HttpResponseMessage response = handler.run(request, "apt-1", context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_invalidNewScheduleId_returns400() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":0}");

    HttpResponseMessage response = handler.run(request, "apt-1", context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_missingNewScheduleIdField_returns400() {
    // scheduleNode == null branch: the field is absent from the request body entirely.
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest("{}");

    HttpResponseMessage response = handler.run(request, "apt-1", context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_nonIntegerNewScheduleId_returns400() {
    // !scheduleNode.isInt() branch: the field is present but not an integer.
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":\"not-a-number\"}");

    HttpResponseMessage response = handler.run(request, "apt-1", context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_validRequest_returns202() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":42}");
    Appointment newAppointment = new Appointment("apt-2", "12345", 42, CountryISO.PE);
    RescheduleAppointmentUseCase useCase =
        org.mockito.Mockito.mock(RescheduleAppointmentUseCase.class);
    when(useCase.execute(anyString(), anyInt())).thenReturn(newAppointment);

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::rescheduleAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(202, response.getStatus().value());
    }
  }

  @Test
  void run_appointmentNotFound_returns404() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":42}");
    RescheduleAppointmentUseCase useCase =
        org.mockito.Mockito.mock(RescheduleAppointmentUseCase.class);
    when(useCase.execute(anyString(), anyInt()))
        .thenThrow(new IllegalStateException("Appointment not found: apt-1"));

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::rescheduleAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(404, response.getStatus().value());
    }
  }

  @Test
  void run_illegalStateWithNullMessage_returns409() {
    // msg != null is the short-circuiting half of "msg != null && msg.startsWith(...)" — an
    // IllegalStateException with no message must still fall through to the generic CONFLICT path
    // rather than NPE on startsWith().
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":42}");
    RescheduleAppointmentUseCase useCase =
        org.mockito.Mockito.mock(RescheduleAppointmentUseCase.class);
    when(useCase.execute(anyString(), anyInt())).thenThrow(new IllegalStateException());

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::rescheduleAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(409, response.getStatus().value());
    }
  }

  @Test
  void run_useCaseThrowsUnexpectedException_returns500() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":42}");
    RescheduleAppointmentUseCase useCase =
        org.mockito.Mockito.mock(RescheduleAppointmentUseCase.class);
    when(useCase.execute(anyString(), anyInt()))
        .thenThrow(new RuntimeException("Service Bus unavailable"));

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::rescheduleAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(500, response.getStatus().value());
    }
  }

  @Test
  void run_appointmentNotPending_returns409() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"newScheduleId\":42}");
    RescheduleAppointmentUseCase useCase =
        org.mockito.Mockito.mock(RescheduleAppointmentUseCase.class);
    when(useCase.execute(anyString(), anyInt()))
        .thenThrow(new IllegalStateException("Only a PENDING appointment can be rescheduled"));

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::rescheduleAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(409, response.getStatus().value());
    }
  }
}
