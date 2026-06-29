package com.clinic.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.time.Duration;

/**
 * Factory for Resilience4j Retry and CircuitBreaker instances used by
 * infrastructure adapters (Cosmos DB, Azure SQL, Service Bus).
 *
 * Retry: 3 attempts with exponential backoff (100 ms → 200 ms → 400 ms).
 * CircuitBreaker: COUNT_BASED window of 10, opens at 50% failure rate,
 *   stays open for 30 s, allows 3 probe calls in half-open state.
 *
 * CallNotPermittedException (circuit open) is NOT retried so the retry loop
 * fails fast when the circuit is open instead of burning through attempts.
 */
public final class ResilienceConfig {

    private ResilienceConfig() {}

    public static Retry exponentialRetry(String name) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(100, 2.0))
                .retryExceptions(RuntimeException.class)
                .ignoreExceptions(CallNotPermittedException.class)
                .build();
        return Retry.of(name, config);
    }

    public static CircuitBreaker circuitBreaker(String name) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        return CircuitBreaker.of(name, config);
    }
}
