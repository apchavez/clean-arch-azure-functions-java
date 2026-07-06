package com.clinic.infrastructure.auth;

import com.clinic.infrastructure.auth.JwtValidator.AuthenticatedUser;
import com.clinic.infrastructure.auth.JwtValidator.AuthenticationException;
import com.microsoft.azure.functions.HttpRequestMessage;
import java.util.Optional;

/**
 * Enforces Bearer JWT authentication at the Function entry point. Each HTTP-triggered handler calls
 * {@link #authenticate(HttpRequestMessage)} explicitly (the {@code authLevel = ANONYMOUS} on the
 * {@code @HttpTrigger} annotation only means "no Azure Functions key required" — this class is the
 * actual authorization gate, since API Management JWT validation is optional and off by default
 * (see infra/core.bicep's {@code enableApiManagementJwtValidation}).
 */
public final class AuthGuard {

  private static volatile String cachedSecret;

  private AuthGuard() {}

  public static AuthenticatedUser authenticate(HttpRequestMessage<Optional<String>> request) {
    String authHeader = request.getHeaders().get("authorization");
    if (authHeader == null) {
      authHeader = request.getHeaders().get("Authorization");
    }
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new AuthenticationException("Missing or malformed Authorization header");
    }
    String token = authHeader.substring("Bearer ".length());
    return JwtValidator.verify(token, secret());
  }

  private static String secret() {
    String secret = cachedSecret;
    if (secret == null) {
      secret = requireConfigured(System.getenv("JWT_SECRET"));
      cachedSecret = secret;
    }
    return secret;
  }

  /**
   * Extracted for testability: {@code cachedSecret} is a process-wide cache populated from the real
   * env var on first use, so the "missing/blank" failure path can't be re-triggered per test case
   * once some earlier test has already populated it (and the env var itself can't be unset/blanked
   * for a single test without JDK-internal reflection). This pure overload takes the candidate
   * value directly.
   */
  static String requireConfigured(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("JWT_SECRET is not configured");
    }
    return secret;
  }
}
