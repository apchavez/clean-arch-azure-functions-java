package com.clinic.api.functions;

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
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Functions HTTP entry point: DELETE /api/appointments/{appointmentId} Cancels a PENDING
 * appointment and publishes an APPOINTMENT_CANCELLED event.
 */
public class CancelAppointmentHandler {

  private static final Logger log = LoggerFactory.getLogger(CancelAppointmentHandler.class);

  @FunctionName("cancelAppointment")
  public HttpResponseMessage run(
      // authLevel is ANONYMOUS at the trigger level (no Azure Functions key required) — AuthGuard
      // enforces the actual auth check below.
      @HttpTrigger(
              name = "req",
              methods = {HttpMethod.DELETE},
              authLevel = AuthorizationLevel.ANONYMOUS,
              route = "appointments/{appointmentId}")
          HttpRequestMessage<Optional<String>> request,
      @BindingName("appointmentId") String appointmentId,
      final ExecutionContext context) {

    String correlationId =
        Optional.ofNullable(request.getHeaders().get("X-Correlation-Id"))
            .orElse(context.getInvocationId());
    CorrelationContext.set(correlationId);
    try {
      log.info(
          "cancelAppointment.received appointmentId={} correlationId={}",
          appointmentId,
          correlationId);
      AuthGuard.authenticate(request);
      if (appointmentId == null || appointmentId.isBlank()) {
        return ApiResponse.error(
            request, HttpStatus.BAD_REQUEST, "appointmentId path parameter is required");
      }
      AppContext.cancelAppointment().execute(appointmentId);
      log.info(
          "appointment.cancelled appointmentId={} correlationId={}", appointmentId, correlationId);
      return ApiResponse.ok(
          request, Map.of("message", "Appointment cancelled", "appointmentId", appointmentId));
    } catch (AuthenticationException e) {
      log.warn(
          "cancelAppointment.unauthorized reason={} correlationId={}",
          e.getMessage(),
          correlationId);
      return ApiResponse.error(request, HttpStatus.UNAUTHORIZED, "Unauthorized: " + e.getMessage());
    } catch (IllegalStateException e) {
      String msg = e.getMessage();
      if (msg != null && msg.startsWith("Appointment not found")) {
        return ApiResponse.error(request, HttpStatus.NOT_FOUND, msg);
      }
      return ApiResponse.error(request, HttpStatus.CONFLICT, msg);
    } catch (Exception e) {
      log.error(
          "Error cancelling appointment: {} correlationId={}", e.getMessage(), correlationId, e);
      return ApiResponse.error(
          request, HttpStatus.INTERNAL_SERVER_ERROR, "Internal error cancelling appointment");
    } finally {
      CorrelationContext.clear();
    }
  }
}
