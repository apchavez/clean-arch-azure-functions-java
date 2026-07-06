package com.clinic.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import java.util.Optional;

/** Centralized HTTP response builder to avoid raw JSON string construction in handlers. */
public final class ApiResponse {

  // JavaTimeModule is required: Appointment/AppointmentEvent expose java.time.Instant fields, and
  // a plain ObjectMapper throws InvalidDefinitionException trying to serialize them.
  // WRITE_DATES_AS_TIMESTAMPS is disabled so Instant fields serialize as ISO-8601 strings (e.g.
  // "2026-06-28T15:30:00Z"), matching what src/docs/openapi.yaml already documents — the default
  // (epoch seconds.nanos as a float) would silently break the OpenAPI contract.
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private ApiResponse() {}

  public static HttpResponseMessage error(
      HttpRequestMessage<Optional<String>> request, HttpStatus status, String message) {
    ObjectNode body = MAPPER.createObjectNode().put("error", message);
    try {
      return request
          .createResponseBuilder(status)
          .header("Content-Type", "application/json")
          .body(MAPPER.writeValueAsString(body))
          .build();
    } catch (Exception e) {
      return request
          .createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
          .header("Content-Type", "application/json")
          .body("{\"error\":\"Internal serialization failure\"}")
          .build();
    }
  }

  public static HttpResponseMessage ok(
      HttpRequestMessage<Optional<String>> request, Object payload) {
    try {
      return request
          .createResponseBuilder(HttpStatus.OK)
          .header("Content-Type", "application/json")
          .body(MAPPER.writeValueAsString(payload))
          .build();
    } catch (Exception e) {
      return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "Response serialization failed");
    }
  }

  public static HttpResponseMessage accepted(
      HttpRequestMessage<Optional<String>> request, Object payload) {
    try {
      return request
          .createResponseBuilder(HttpStatus.ACCEPTED)
          .header("Content-Type", "application/json")
          .body(MAPPER.writeValueAsString(payload))
          .build();
    } catch (Exception e) {
      return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "Response serialization failed");
    }
  }
}
