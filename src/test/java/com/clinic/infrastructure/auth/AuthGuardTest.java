package com.clinic.infrastructure.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clinic.infrastructure.auth.JwtValidator.AuthenticatedUser;
import com.clinic.infrastructure.auth.JwtValidator.AuthenticationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.HttpRequestMessage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The JWT_SECRET used here ({@code test-only-secret-do-not-use-in-production}) is injected by the
 * maven-surefire-plugin's {@code environmentVariables} config in pom.xml — it matches what
 * AuthGuard reads via {@code System.getenv}.
 */
class AuthGuardTest {

  private static final String TEST_SECRET = "test-only-secret-do-not-use-in-production";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void authenticate_missingHeader_throws() {
    HttpRequestMessage<Optional<String>> request = requestWithHeaders(Map.of());

    assertThrows(AuthenticationException.class, () -> AuthGuard.authenticate(request));
  }

  @Test
  void authenticate_headerWithoutBearerPrefix_throws() {
    HttpRequestMessage<Optional<String>> request =
        requestWithHeaders(Map.of("authorization", "Basic abc123"));

    assertThrows(AuthenticationException.class, () -> AuthGuard.authenticate(request));
  }

  @Test
  void authenticate_validBearerToken_returnsAuthenticatedUser() {
    String token = sign(Map.of("sub", "agent-001", "role", "agent", "exp", futureExp()));
    HttpRequestMessage<Optional<String>> request =
        requestWithHeaders(Map.of("authorization", "Bearer " + token));

    AuthenticatedUser user = AuthGuard.authenticate(request);

    assertEquals("agent-001", user.sub());
    assertEquals("agent", user.role());
  }

  @Test
  void authenticate_invalidSignature_throws() {
    String token =
        signWith(
            Map.of("sub", "agent-001", "role", "agent", "exp", futureExp()),
            "not-the-configured-secret");
    HttpRequestMessage<Optional<String>> request =
        requestWithHeaders(Map.of("authorization", "Bearer " + token));

    assertThrows(AuthenticationException.class, () -> AuthGuard.authenticate(request));
  }

  // --- requireConfigured(String): the pure JWT_SECRET-validation logic. `secret()`'s cache means
  // the missing/blank branch can only ever run once per JVM against the real env var (which is
  // always set here) — this calls the extracted, parameterized decision logic directly instead.

  @Test
  void requireConfigured_nullSecret_throws() {
    assertThrows(IllegalStateException.class, () -> AuthGuard.requireConfigured(null));
  }

  @Test
  void requireConfigured_blankSecret_throws() {
    assertThrows(IllegalStateException.class, () -> AuthGuard.requireConfigured("   "));
  }

  @Test
  void requireConfigured_validSecret_returnsIt() {
    assertEquals(TEST_SECRET, AuthGuard.requireConfigured(TEST_SECRET));
  }

  @SuppressWarnings("unchecked")
  private HttpRequestMessage<Optional<String>> requestWithHeaders(Map<String, String> headers) {
    HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);
    when(request.getHeaders()).thenReturn(new HashMap<>(headers));
    return request;
  }

  private static String sign(Map<String, Object> claims) {
    return signWith(claims, TEST_SECRET);
  }

  private static String signWith(Map<String, Object> claims, String secret) {
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

  private static long futureExp() {
    return System.currentTimeMillis() / 1000 + 3600;
  }
}
