package com.clinic.infrastructure.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clinic.infrastructure.auth.JwtValidator.AuthenticatedUser;
import com.clinic.infrastructure.auth.JwtValidator.AuthenticationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class JwtValidatorTest {

  private static final String SECRET = "unit-test-secret";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void verify_validToken_returnsSubAndRole() {
    String token = sign(Map.of("sub", "agent-001", "role", "agent", "exp", futureExp()), SECRET);

    AuthenticatedUser user = JwtValidator.verify(token, SECRET);

    assertEquals("agent-001", user.sub());
    assertEquals("agent", user.role());
  }

  @Test
  void verify_tamperedSignature_throws() {
    String token = sign(Map.of("sub", "agent-001", "role", "agent", "exp", futureExp()), SECRET);

    assertThrows(AuthenticationException.class, () -> JwtValidator.verify(token, "wrong-secret"));
  }

  @Test
  void verify_expiredToken_throws() {
    long pastExp = System.currentTimeMillis() / 1000 - 10;
    String token = sign(Map.of("sub", "agent-001", "role", "agent", "exp", pastExp), SECRET);

    assertThrows(AuthenticationException.class, () -> JwtValidator.verify(token, SECRET));
  }

  @Test
  void verify_missingExpClaim_throws() {
    String token = sign(Map.of("sub", "agent-001", "role", "agent"), SECRET);

    assertThrows(AuthenticationException.class, () -> JwtValidator.verify(token, SECRET));
  }

  @Test
  void verify_malformedToken_throws() {
    assertThrows(AuthenticationException.class, () -> JwtValidator.verify("not-a-jwt", SECRET));
  }

  @Test
  void verify_blankToken_throws() {
    assertThrows(AuthenticationException.class, () -> JwtValidator.verify("", SECRET));
  }

  @Test
  void verify_nullToken_throws() {
    assertThrows(AuthenticationException.class, () -> JwtValidator.verify(null, SECRET));
  }

  @Test
  void verify_malformedBase64Segment_throws() {
    assertThrows(
        AuthenticationException.class, () -> JwtValidator.verify("a.!!!not-base64!!!.c", SECRET));
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

  private static long futureExp() {
    return System.currentTimeMillis() / 1000 + 3600;
  }
}
