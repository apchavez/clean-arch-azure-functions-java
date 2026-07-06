package com.clinic.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ApiResponse had no dedicated unit tests — it was only exercised indirectly through handler tests,
 * whose fake {@code HttpResponseMessage.Builder} never fails serialization, so the catch(Exception)
 * fallback branches in error()/ok()/accepted() were never actually executed.
 */
class ApiResponseTest {

  @SuppressWarnings("unchecked")
  private HttpRequestMessage<Optional<String>> mockRequest() {
    HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);
    when(request.createResponseBuilder(org.mockito.ArgumentMatchers.any(HttpStatus.class)))
        .thenAnswer(invocation -> new FakeBuilder(invocation.getArgument(0)));
    return request;
  }

  @Test
  void error_buildsJsonErrorBodyWithGivenStatus() {
    HttpResponseMessage response =
        ApiResponse.error(mockRequest(), HttpStatus.BAD_REQUEST, "insuredId is required");

    assertEquals(400, response.getStatus().value());
    assertEquals("{\"error\":\"insuredId is required\"}", response.getBody());
  }

  @Test
  void ok_serializesInstantFieldsAsIso8601NotEpoch() {
    record Payload(String id, Instant createdAt) {}
    Instant fixed = Instant.parse("2026-06-28T15:30:00Z");

    HttpResponseMessage response = ApiResponse.ok(mockRequest(), new Payload("apt-1", fixed));

    assertEquals(200, response.getStatus().value());
    // WRITE_DATES_AS_TIMESTAMPS is disabled — must serialize as an ISO-8601 string, not a
    // epoch-seconds float, so it matches the documented OpenAPI contract.
    assertTrue(
        response.getBody().toString().contains("\"createdAt\":\"2026-06-28T15:30:00Z\""),
        "expected ISO-8601 string, got: " + response.getBody());
  }

  @Test
  void accepted_returns202WithSerializedPayload() {
    HttpResponseMessage response = ApiResponse.accepted(mockRequest(), new Object[] {"apt-1"});

    assertEquals(202, response.getStatus().value());
    assertTrue(response.getBody().toString().contains("apt-1"));
  }

  @Test
  void ok_serializationFailure_fallsBackToError500() {
    HttpResponseMessage response = ApiResponse.ok(mockRequest(), new Unserializable());

    assertEquals(500, response.getStatus().value());
    assertEquals("{\"error\":\"Response serialization failed\"}", response.getBody());
  }

  @Test
  void accepted_serializationFailure_fallsBackToError500() {
    HttpResponseMessage response = ApiResponse.accepted(mockRequest(), new Unserializable());

    assertEquals(500, response.getStatus().value());
    assertEquals("{\"error\":\"Response serialization failed\"}", response.getBody());
  }

  @Test
  void error_buildingOwnResponseFails_fallsBackToHardcodedInternalErrorBody() {
    // error()'s own try/catch: when building the FIRST response (the one for the caller's
    // requested status) itself throws, error() must still produce a response rather than
    // propagating -- by retrying with a hardcoded body against INTERNAL_SERVER_ERROR. Every other
    // test's FakeBuilder always succeeds, so this catch(Exception) fallback was never exercised.
    @SuppressWarnings("unchecked")
    HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);
    when(request.createResponseBuilder(HttpStatus.BAD_REQUEST))
        .thenThrow(new RuntimeException("builder unavailable"));
    when(request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR))
        .thenAnswer(invocation -> new FakeBuilder(invocation.getArgument(0)));

    HttpResponseMessage response =
        ApiResponse.error(request, HttpStatus.BAD_REQUEST, "insuredId is required");

    assertEquals(500, response.getStatus().value());
    assertEquals("{\"error\":\"Internal serialization failure\"}", response.getBody());
  }

  /** A getter that throws makes Jackson raise a JsonMappingException during serialization. */
  static final class Unserializable {
    public String getValue() {
      throw new RuntimeException("boom");
    }
  }

  private static final class FakeBuilder implements HttpResponseMessage.Builder {
    private final HttpStatusType status;
    private Object body;

    FakeBuilder(HttpStatusType status) {
      this.status = status;
    }

    @Override
    public HttpResponseMessage.Builder status(HttpStatusType status) {
      return this;
    }

    @Override
    public HttpResponseMessage.Builder header(String key, String value) {
      return this;
    }

    @Override
    public HttpResponseMessage.Builder body(Object body) {
      this.body = body;
      return this;
    }

    @Override
    public HttpResponseMessage build() {
      return new FakeResponse(status, body);
    }
  }

  private static final class FakeResponse implements HttpResponseMessage {
    private final HttpStatusType status;
    private final Object body;

    FakeResponse(HttpStatusType status, Object body) {
      this.status = status;
      this.body = body;
    }

    @Override
    public HttpStatusType getStatus() {
      return status;
    }

    @Override
    public String getHeader(String key) {
      return null;
    }

    @Override
    public Object getBody() {
      return body;
    }
  }
}
