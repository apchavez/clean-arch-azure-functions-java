package com.clinic.api.functions;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared mocking helpers for the HTTP handler tests. Handlers call {@code
 * request.createResponseBuilder(status).header(...).body(...).build()}, so the fake {@link
 * HttpResponseMessage.Builder} below just records the status/body it was given instead of trying to
 * fully re-implement the SDK.
 */
final class HandlerTestSupport {

  /** Must match the value injected by maven-surefire-plugin's environmentVariables in pom.xml. */
  static final String TEST_JWT_SECRET = "test-only-secret-do-not-use-in-production";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HandlerTestSupport() {}

  @SuppressWarnings("unchecked")
  static HttpRequestMessage<Optional<String>> mockRequest(
      Map<String, String> headers, Map<String, String> query, String body) {
    HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);
    when(request.getHeaders()).thenReturn(new HashMap<>(headers));
    when(request.getQueryParameters()).thenReturn(new HashMap<>(query));
    when(request.getBody()).thenReturn(Optional.ofNullable(body));
    when(request.createResponseBuilder(org.mockito.ArgumentMatchers.any(HttpStatus.class)))
        .thenAnswer(invocation -> new FakeBuilder(invocation.getArgument(0)));
    return request;
  }

  static HttpRequestMessage<Optional<String>> mockAuthenticatedRequest(String body) {
    return mockRequest(Map.of("authorization", "Bearer " + validToken()), Map.of(), body);
  }

  static HttpRequestMessage<Optional<String>> mockAuthenticatedRequest(
      Map<String, String> query, String body) {
    return mockRequest(Map.of("authorization", "Bearer " + validToken()), query, body);
  }

  static ExecutionContext mockContext() {
    ExecutionContext context = mock(ExecutionContext.class);
    when(context.getInvocationId()).thenReturn("test-invocation-id");
    return context;
  }

  static String validToken() {
    long exp = System.currentTimeMillis() / 1000 + 3600;
    return sign(Map.of("sub", "agent-001", "role", "agent", "exp", exp), TEST_JWT_SECRET);
  }

  private static String sign(Map<String, Object> claims, String secret) {
    try {
      String header = base64Url(MAPPER.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
      String payload = base64Url(MAPPER.writeValueAsBytes(claims));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] sig = mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8));
      return header + "." + payload + "." + base64Url(sig);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String base64Url(byte[] data) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
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

  static final class FakeResponse implements HttpResponseMessage {
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
