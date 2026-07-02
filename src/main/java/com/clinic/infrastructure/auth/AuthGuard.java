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
      secret = System.getenv("JWT_SECRET");
      if (secret == null || secret.isBlank()) {
        throw new IllegalStateException("JWT_SECRET is not configured");
      }
      cachedSecret = secret;
    }
    return secret;
  }
}
