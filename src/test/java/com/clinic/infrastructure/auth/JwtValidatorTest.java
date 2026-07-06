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
  void verify_expClaimNotConvertibleToLong_throws() {
    // expNode == null || !expNode.canConvertToLong(): the missing-claim test above only exercises
    // the first half (expNode == null). This exercises the second half — the claim is present but
    // isn't numeric.
    String token =
        sign(Map.of("sub", "agent-001", "role", "agent", "exp", "not-a-timestamp"), SECRET);

    AuthenticationException ex =
        assertThrows(AuthenticationException.class, () -> JwtValidator.verify(token, SECRET));
    assertEquals("Missing exp claim", ex.getMessage());
  }

  @Test
  void verify_missingSubAndRoleClaims_returnsNullFields() {
    // subNode/roleNode != null ? ...asText() : null — every other test supplies both claims, so
    // the "absent claim" (null) side of both ternaries was never exercised.
    String token = sign(Map.of("exp", futureExp()), SECRET);

    AuthenticatedUser user = JwtValidator.verify(token, SECRET);

    assertEquals(null, user.sub());
    assertEquals(null, user.role());
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

  @Test
  void verify_validBase64ButNonJsonPayload_throwsMalformedPayload() {
    // Valid base64url segments (so the signature check passes) but the payload segment decodes to
    // plain text rather than JSON, so MAPPER.readTree() itself throws — a different failure mode
    // than an invalid base64 segment.
    String header = base64Url("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
    String payload = base64Url("this is not json".getBytes(StandardCharsets.UTF_8));
    String token = header + "." + payload + "." + signature(header + "." + payload, SECRET);

    AuthenticationException ex =
        assertThrows(AuthenticationException.class, () -> JwtValidator.verify(token, SECRET));
    assertEquals("Malformed JWT payload", ex.getMessage());
  }

  @Test
  void verify_malformedBase64PayloadSegment_rethrowsOriginalExceptionUnwrapped() {
    // The signature check must pass (so we reach the payload-decoding try block) even though the
    // payload segment itself contains invalid base64url characters. base64UrlDecode(parts[1])
    // throws its own AuthenticationException("Malformed base64url segment"), which the outer
    // try/catch's catch(AuthenticationException e) { throw e; } branch must rethrow as-is rather
    // than letting the generic catch(Exception) branch below it re-wrap it as "Malformed JWT
    // payload" -- a different, less specific message.
    String header = base64Url("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
    String payload = "not!!valid_base64@@@";
    String signingInput = header + "." + payload;
    String token = signingInput + "." + signature(signingInput, SECRET);

    AuthenticationException ex =
        assertThrows(AuthenticationException.class, () -> JwtValidator.verify(token, SECRET));
    assertEquals("Malformed base64url segment", ex.getMessage());
  }

  @Test
  void verify_emptySecret_throwsIllegalStateInsteadOfLeakingRawCryptoException() {
    // Bug found while covering hmacSha256's catch clause: SecretKeySpec throws a raw
    // IllegalArgumentException("Empty key") for a zero-length secret, before Mac.init ever runs,
    // so it previously wasn't caught by catch(NoSuchAlgorithmException | InvalidKeyException) and
    // leaked past JwtValidator's exception contract (every other failure path throws either
    // AuthenticationException or, for HMAC computation failures, IllegalStateException). Fixed by
    // adding IllegalArgumentException to that catch clause.
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> JwtValidator.verify("a.b.c", ""));
    assertEquals("HMAC computation failed", ex.getMessage());
  }

  private static String signature(String signingInput, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
