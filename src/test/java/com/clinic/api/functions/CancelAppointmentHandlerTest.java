package com.clinic.api.functions;

import static com.clinic.api.functions.HandlerTestSupport.mockAuthenticatedRequest;
import static com.clinic.api.functions.HandlerTestSupport.mockContext;
import static com.clinic.api.functions.HandlerTestSupport.mockRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;

import com.clinic.application.usecases.CancelAppointmentUseCase;
import com.clinic.infrastructure.config.AppContext;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CancelAppointmentHandlerTest {

  private final CancelAppointmentHandler handler = new CancelAppointmentHandler();
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
  void run_illegalStateWithNullMessage_returns409() {
    // msg != null is the short-circuiting half of "msg != null && msg.startsWith(...)" — an
    // IllegalStateException with no message must still fall through to the generic CONFLICT path
    // rather than NPE on startsWith().
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);
    CancelAppointmentUseCase useCase = org.mockito.Mockito.mock(CancelAppointmentUseCase.class);
    doThrow(new IllegalStateException()).when(useCase).execute(anyString());

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::cancelAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(409, response.getStatus().value());
    }
  }

  @Test
  void run_validRequest_returns200() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);
    CancelAppointmentUseCase useCase = org.mockito.Mockito.mock(CancelAppointmentUseCase.class);
    doNothing().when(useCase).execute(anyString());

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::cancelAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(200, response.getStatus().value());
    }
  }

  @Test
  void run_appointmentNotFound_returns404() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);
    CancelAppointmentUseCase useCase = org.mockito.Mockito.mock(CancelAppointmentUseCase.class);
    doThrow(new IllegalStateException("Appointment not found: apt-1"))
        .when(useCase)
        .execute(anyString());

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::cancelAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(404, response.getStatus().value());
    }
  }

  @Test
  void run_appointmentNotPending_returns409() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);
    CancelAppointmentUseCase useCase = org.mockito.Mockito.mock(CancelAppointmentUseCase.class);
    doThrow(new IllegalStateException("Only a PENDING appointment can be cancelled"))
        .when(useCase)
        .execute(anyString());

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::cancelAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(409, response.getStatus().value());
    }
  }

  @Test
  void run_useCaseThrowsUnexpectedException_returns500() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(null);
    CancelAppointmentUseCase useCase = org.mockito.Mockito.mock(CancelAppointmentUseCase.class);
    doThrow(new RuntimeException("Service Bus unavailable")).when(useCase).execute(anyString());

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::cancelAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "apt-1", context);

      assertEquals(500, response.getStatus().value());
    }
  }
}
