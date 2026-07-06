package com.clinic.api.functions;

import com.clinic.domain.entities.AppointmentEvent;
import com.clinic.infrastructure.auth.AuthGuard;
import com.clinic.infrastructure.auth.JwtValidator.AuthenticationException;
import com.clinic.infrastructure.config.AppContext;
import com.clinic.infrastructure.config.CorrelationContext;
import com.clinic.shared.ApiResponse;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Functions HTTP entry point: GET /api/appointments/{appointmentId}/history Returns the full
 * ordered event log for a single appointment — every state transition recorded by the lightweight
 * event sourcing layer.
 */
public class GetAppointmentHistoryHandler {

  private static final Logger log = LoggerFactory.getLogger(GetAppointmentHistoryHandler.class);

  @FunctionName("getAppointmentHistory")
  public HttpResponseMessage run(
      // authLevel is ANONYMOUS at the trigger level (no Azure Functions key required) — AuthGuard
      // enforces the actual auth check below.
      @HttpTrigger(
              name = "req",
              methods = {HttpMethod.GET},
              authLevel = AuthorizationLevel.ANONYMOUS,
              route = "appointments/{appointmentId}/history")
          HttpRequestMessage<Optional<String>> request,
      @BindingName("appointmentId") String appointmentId,
      final ExecutionContext context) {

    String correlationId =
        Optional.ofNullable(request.getHeaders().get("X-Correlation-Id"))
            .orElse(context.getInvocationId());
    CorrelationContext.set(correlationId);
    try {
      log.info(
          "getAppointmentHistory.received appointmentId={} correlationId={}",
          appointmentId,
          correlationId);
      AuthGuard.authenticate(request);
      if (appointmentId == null || appointmentId.isBlank()) {
        return ApiResponse.error(
            request, HttpStatus.BAD_REQUEST, "appointmentId path parameter is required");
      }

      List<AppointmentEvent> events = AppContext.eventStore().findByAppointmentId(appointmentId);

      log.info(
          "appointment.history appointmentId={} eventCount={} correlationId={}",
          appointmentId,
          events.size(),
          correlationId);

      return ApiResponse.ok(request, events);
    } catch (AuthenticationException e) {
      log.warn(
          "getAppointmentHistory.unauthorized reason={} correlationId={}",
          e.getMessage(),
          correlationId);
      return ApiResponse.error(request, HttpStatus.UNAUTHORIZED, "Unauthorized: " + e.getMessage());
    } catch (Exception e) {
      log.error(
          "Error fetching appointment history: {} correlationId={}",
          e.getMessage(),
          correlationId,
          e);
      return ApiResponse.error(
          request, HttpStatus.INTERNAL_SERVER_ERROR, "Internal error fetching appointment history");
    } finally {
      CorrelationContext.clear();
    }
  }
}
