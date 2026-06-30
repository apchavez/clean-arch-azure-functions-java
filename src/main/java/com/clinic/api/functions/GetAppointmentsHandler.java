package com.clinic.api.functions;

import com.clinic.application.usecases.GetAppointmentsUseCase;
import com.clinic.domain.entities.Appointment;
import com.clinic.domain.shared.Page;
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
 * Azure Functions HTTP entry point: GET /api/appointments/{insuredId} Mirrors the AWS "list
 * appointments by insured" query endpoint.
 *
 * <p>Supports cursor-based pagination via query parameters: ?pageSize=20 — number of items per page
 * (default 20, max 100) ?cursor=... — continuation token from a previous response's nextCursor
 * field
 */
public class GetAppointmentsHandler {

  private static final Logger log = LoggerFactory.getLogger(GetAppointmentsHandler.class);

  @FunctionName("getAppointments")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "req",
              methods = {HttpMethod.GET},
              authLevel = AuthorizationLevel.ANONYMOUS,
              route = "appointments/{insuredId}")
          HttpRequestMessage<Optional<String>> request,
      @BindingName("insuredId") String insuredId,
      final ExecutionContext context) {

    String correlationId =
        Optional.ofNullable(request.getHeaders().get("X-Correlation-Id"))
            .orElse(context.getInvocationId());
    CorrelationContext.set(correlationId);
    try {
      log.info("getAppointments.received insuredId={} correlationId={}", insuredId, correlationId);
      if (insuredId == null || insuredId.isBlank()) {
        return ApiResponse.error(
            request, HttpStatus.BAD_REQUEST, "insuredId path parameter is required");
      }

      Map<String, String> params = request.getQueryParameters();
      int pageSize = parsePageSize(params.get("pageSize"));
      String cursor = params.get("cursor");

      Page<Appointment> page = AppContext.getAppointments().byInsured(insuredId, pageSize, cursor);

      log.info(
          "appointments.queried insuredId={} count={} hasNextPage={} correlationId={}",
          insuredId,
          page.items.size(),
          page.nextCursor != null,
          correlationId);

      return ApiResponse.ok(request, page);
    } catch (Exception e) {
      log.error(
          "Error querying appointments: {} correlationId={}", e.getMessage(), correlationId, e);
      return ApiResponse.error(
          request, HttpStatus.INTERNAL_SERVER_ERROR, "Internal error querying appointments");
    } finally {
      CorrelationContext.clear();
    }
  }

  private int parsePageSize(String raw) {
    if (raw == null || raw.isBlank()) {
      return GetAppointmentsUseCase.DEFAULT_PAGE_SIZE;
    }
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      return GetAppointmentsUseCase.DEFAULT_PAGE_SIZE;
    }
  }
}
