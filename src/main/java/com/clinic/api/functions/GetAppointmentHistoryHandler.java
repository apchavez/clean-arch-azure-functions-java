package com.clinic.api.functions;

import com.clinic.domain.entities.AppointmentEvent;
import com.clinic.infrastructure.config.AppContext;
import com.clinic.shared.ApiResponse;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Azure Functions HTTP entry point: GET /api/appointments/{appointmentId}/history
 * Returns the full ordered event log for a single appointment — every state
 * transition recorded by the lightweight event sourcing layer.
 */
public class GetAppointmentHistoryHandler {

    private static final Logger log = LoggerFactory.getLogger(GetAppointmentHistoryHandler.class);

    @FunctionName("getAppointmentHistory")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "appointments/{appointmentId}/history"
            ) HttpRequestMessage<Optional<String>> request,
            @BindingName("appointmentId") String appointmentId,
            final ExecutionContext context) {

        try {
            if (appointmentId == null || appointmentId.isBlank()) {
                return ApiResponse.error(request, HttpStatus.BAD_REQUEST,
                        "appointmentId path parameter is required");
            }

            List<AppointmentEvent> events = AppContext.eventStore()
                    .findByAppointmentId(appointmentId);

            log.info("appointment.history appointmentId={} eventCount={} invocationId={}",
                    appointmentId, events.size(), context.getInvocationId());

            return ApiResponse.ok(request, events);
        } catch (Exception e) {
            log.error("Error fetching appointment history: {}", e.getMessage(), e);
            return ApiResponse.error(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Internal error fetching appointment history");
        }
    }
}
