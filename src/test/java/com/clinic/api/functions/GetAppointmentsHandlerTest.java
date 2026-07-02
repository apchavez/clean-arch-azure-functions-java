package com.clinic.api.functions;

import static com.clinic.api.functions.HandlerTestSupport.mockAuthenticatedRequest;
import static com.clinic.api.functions.HandlerTestSupport.mockContext;
import static com.clinic.api.functions.HandlerTestSupport.mockRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.clinic.application.usecases.GetAppointmentsUseCase;
import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.CountryISO;
import com.clinic.domain.shared.Page;
import com.clinic.infrastructure.config.AppContext;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class GetAppointmentsHandlerTest {

  private final GetAppointmentsHandler handler = new GetAppointmentsHandler();
  private final ExecutionContext context = mockContext();

  @Test
  void run_missingToken_returns401() {
    HttpRequestMessage<Optional<String>> request = mockRequest(Map.of(), Map.of(), null);

    HttpResponseMessage response = handler.run(request, "12345", context);

    assertEquals(401, response.getStatus().value());
  }

  @Test
  void run_blankInsuredId_returns400() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(Map.of(), null);

    HttpResponseMessage response = handler.run(request, "", context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_validRequest_returns200WithPage() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(Map.of(), null);
    Appointment appointment = new Appointment("apt-1", "12345", 10, CountryISO.PE);
    Page<Appointment> page = new Page<>(List.of(appointment), null);

    GetAppointmentsUseCase useCase = org.mockito.Mockito.mock(GetAppointmentsUseCase.class);
    when(useCase.byInsured(anyString(), anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(page);

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::getAppointments).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "12345", context);

      assertEquals(200, response.getStatus().value());
      assertTrue(response.getBody().toString().contains("\"appointmentId\":\"apt-1\""));
    }
  }

  @Test
  void run_invalidPageSize_fallsBackToDefault() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest(Map.of("pageSize", "not-a-number"), null);
    Page<Appointment> page = new Page<>(List.of(), null);

    GetAppointmentsUseCase useCase = org.mockito.Mockito.mock(GetAppointmentsUseCase.class);
    when(useCase.byInsured(anyString(), anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(page);

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::getAppointments).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "12345", context);

      assertEquals(200, response.getStatus().value());
    }
  }

  @Test
  void run_useCaseThrows_returns500() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest(Map.of(), null);

    GetAppointmentsUseCase useCase = org.mockito.Mockito.mock(GetAppointmentsUseCase.class);
    when(useCase.byInsured(anyString(), anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::getAppointments).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, "12345", context);

      assertEquals(500, response.getStatus().value());
    }
  }
}
