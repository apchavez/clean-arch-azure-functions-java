package com.clinic.infrastructure.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal HS256 JWT verifier — mirrors the sibling AWS Lambda project's hand-rolled {@code
 * verifyJwt} ({@code src/infra/jwt.ts}) so both platforms accept the same token shape ({@code sub}
 * / {@code role} / {@code exp}) without pulling in a JWT library.
 */
public final class JwtValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JwtValidator() {}

  public record AuthenticatedUser(String sub, String role) {}

  public static class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
      super(message);
    }
  }

  public static AuthenticatedUser verify(String token, String secret) {
    if (token == null || token.isBlank()) {
      throw new AuthenticationException("Missing token");
    }
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new AuthenticationException("Malformed JWT");
    }

    byte[] expectedSig = hmacSha256(parts[0] + "." + parts[1], secret);
    byte[] actualSig = base64UrlDecode(parts[2]);
    // MessageDigest.isEqual is constant-time, preventing timing-based signature oracle attacks.
    if (!MessageDigest.isEqual(expectedSig, actualSig)) {
      throw new AuthenticationException("Invalid signature");
    }

    JsonNode payload;
    try {
      payload = MAPPER.readTree(base64UrlDecode(parts[1]));
    } catch (AuthenticationException e) {
      throw e;
    } catch (Exception e) {
      throw new AuthenticationException("Malformed JWT payload");
    }

    JsonNode expNode = payload.get("exp");
    if (expNode == null || !expNode.canConvertToLong()) {
      throw new AuthenticationException("Missing exp claim");
    }
    long nowSeconds = System.currentTimeMillis() / 1000;
    if (nowSeconds > expNode.asLong()) {
      throw new AuthenticationException("Token expired");
    }

    JsonNode subNode = payload.get("sub");
    JsonNode roleNode = payload.get("role");
    return new AuthenticatedUser(
        subNode != null ? subNode.asText() : null, roleNode != null ? roleNode.asText() : null);
  }

  private static byte[] hmacSha256(String data, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC computation failed", e);
    }
  }

  private static byte[] base64UrlDecode(String value) {
    try {
      int padding = (4 - value.length() % 4) % 4;
      String padded = value + "=".repeat(padding);
      return Base64.getUrlDecoder().decode(padded);
    } catch (IllegalArgumentException e) {
      throw new AuthenticationException("Malformed base64url segment");
    }
  }
}
