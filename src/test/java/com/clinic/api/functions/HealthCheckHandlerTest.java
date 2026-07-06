package com.clinic.api.functions;

import static com.clinic.api.functions.HandlerTestSupport.mockContext;
import static com.clinic.api.functions.HandlerTestSupport.mockRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import com.clinic.infrastructure.config.AppContext;
import com.clinic.shared.HealthStatus;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class HealthCheckHandlerTest {

  private final HealthCheckHandler handler = new HealthCheckHandler();
  private final ExecutionContext context = mockContext();

  @Test
  void run_allChecksUp_returns200() {
    HttpRequestMessage<Optional<String>> request = mockRequest(Map.of(), Map.of(), null);
    HealthStatus status =
        new HealthStatus(HealthStatus.UP, Map.of("cosmosDb", "UP", "azureSql", "UP"));

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::healthCheck).thenReturn(status);

      HttpResponseMessage response = handler.run(request, context);

      assertEquals(200, response.getStatus().value());
      assertTrue(String.valueOf(response.getBody()).contains("\"UP\""));
    }
  }

  @Test
  void run_someCheckDown_returns503() {
    HttpRequestMessage<Optional<String>> request = mockRequest(Map.of(), Map.of(), null);
    HealthStatus status =
        new HealthStatus(HealthStatus.DOWN, Map.of("cosmosDb", "UP", "azureSql", "DOWN: timeout"));

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::healthCheck).thenReturn(status);

      HttpResponseMessage response = handler.run(request, context);

      assertEquals(503, response.getStatus().value());
    }
  }

  @Test
  void run_healthCheckThrows_returns500() {
    HttpRequestMessage<Optional<String>> request = mockRequest(Map.of(), Map.of(), null);

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::healthCheck).thenThrow(new RuntimeException("boom"));

      HttpResponseMessage response = handler.run(request, context);

      assertEquals(500, response.getStatus().value());
      assertTrue(String.valueOf(response.getBody()).contains("Health check unavailable"));
    }
  }
}
