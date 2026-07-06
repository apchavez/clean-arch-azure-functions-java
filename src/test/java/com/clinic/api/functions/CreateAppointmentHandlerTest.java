package com.clinic.api.functions;

import static com.clinic.api.functions.HandlerTestSupport.mockAuthenticatedRequest;
import static com.clinic.api.functions.HandlerTestSupport.mockContext;
import static com.clinic.api.functions.HandlerTestSupport.mockRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.clinic.application.usecases.CreateAppointmentUseCase;
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

class CreateAppointmentHandlerTest {

  private final CreateAppointmentHandler handler = new CreateAppointmentHandler();
  private final ExecutionContext context = mockContext();

  @Test
  void run_missingToken_returns401() {
    HttpRequestMessage<Optional<String>> request =
        mockRequest(
            Map.of(), Map.of(), "{\"insuredId\":\"1\",\"scheduleId\":1,\"countryISO\":\"PE\"}");

    HttpResponseMessage response = handler.run(request, context);

    assertEquals(401, response.getStatus().value());
  }

  @Test
  void run_missingRequiredFields_returns400() {
    HttpRequestMessage<Optional<String>> request = mockAuthenticatedRequest("{}");

    HttpResponseMessage response = handler.run(request, context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_blankInsuredId_returns400() {
    // The validation guard is a chain of ORs; "{}" above only exercises insuredId == null. This
    // isolates insuredId.isBlank() by supplying every other field validly.
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"insuredId\":\"   \",\"scheduleId\":1,\"countryISO\":\"PE\"}");

    HttpResponseMessage response = handler.run(request, context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_scheduleIdBelowOne_returns400() {
    // Isolates the "scheduleId < 1" condition with every other field valid.
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest(
            "{\"insuredId\":\"12345\",\"scheduleId\":0,\"countryISO\":\"PE\"}");

    HttpResponseMessage response = handler.run(request, context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_missingCountryISO_returns400() {
    // Isolates "countryISO == null" (as opposed to present-but-unsupported, covered separately
    // below) with every other field valid.
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"insuredId\":\"12345\",\"scheduleId\":1}");

    HttpResponseMessage response = handler.run(request, context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_unsupportedCountryISO_returns400() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest("{\"insuredId\":\"1\",\"scheduleId\":1,\"countryISO\":\"AR\"}");

    HttpResponseMessage response = handler.run(request, context);

    assertEquals(400, response.getStatus().value());
  }

  @Test
  void run_validRequest_returns202WithAppointmentId() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest(
            "{\"insuredId\":\"12345\",\"scheduleId\":10,\"countryISO\":\"PE\"}");

    Appointment appointment = new Appointment("apt-1", "12345", 10, CountryISO.PE);
    CreateAppointmentUseCase useCase = org.mockito.Mockito.mock(CreateAppointmentUseCase.class);
    when(useCase.execute(anyString(), anyInt(), any(CountryISO.class), any()))
        .thenReturn(appointment);

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::createAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, context);

      assertEquals(202, response.getStatus().value());
    }
  }

  @Test
  void run_useCaseThrows_returns500() {
    HttpRequestMessage<Optional<String>> request =
        mockAuthenticatedRequest(
            "{\"insuredId\":\"12345\",\"scheduleId\":10,\"countryISO\":\"PE\"}");

    CreateAppointmentUseCase useCase = org.mockito.Mockito.mock(CreateAppointmentUseCase.class);
    when(useCase.execute(anyString(), anyInt(), any(CountryISO.class), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::createAppointment).thenReturn(useCase);

      HttpResponseMessage response = handler.run(request, context);

      assertEquals(500, response.getStatus().value());
    }
  }
}
