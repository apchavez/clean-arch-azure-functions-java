package com.clinic.infrastructure;

import com.clinic.infrastructure.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResilienceConfigTest {

    @Test
    void retryExecutesMaxAttemptsOnPersistentFailure() {
        Retry retry = ResilienceConfig.exponentialRetry("test-retry");
        CircuitBreaker cb = ResilienceConfig.circuitBreaker("test-cb-retry");
        AtomicInteger calls = new AtomicInteger();

        assertThrows(RuntimeException.class, () ->
                Retry.decorateSupplier(retry, cb.decorateSupplier(() -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("transient");
                })).get()
        );

        assertEquals(3, calls.get());
    }

    @Test
    void retrySucceedsOnSecondAttempt() {
        Retry retry = ResilienceConfig.exponentialRetry("test-retry-2");
        CircuitBreaker cb = ResilienceConfig.circuitBreaker("test-cb-retry-2");
        AtomicInteger calls = new AtomicInteger();

        String result = Retry.decorateSupplier(retry, cb.decorateSupplier(() -> {
            if (calls.incrementAndGet() < 2) throw new RuntimeException("first attempt fails");
            return "ok";
        })).get();

        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    void circuitBreakerOpensAfterFailureThreshold() {
        CircuitBreaker cb = ResilienceConfig.circuitBreaker("test-cb-open");

        // Fill the 10-call sliding window with failures to cross the 50% threshold
        for (int i = 0; i < 10; i++) {
            try {
                cb.executeSupplier(() -> { throw new RuntimeException("fail"); });
            } catch (Exception ignored) {}
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void callNotPermittedIsNotRetried() {
        Retry retry = ResilienceConfig.exponentialRetry("test-retry-cb-open");
        CircuitBreaker cb = ResilienceConfig.circuitBreaker("test-cb-not-permitted");

        // Force circuit open
        for (int i = 0; i < 10; i++) {
            try { cb.executeSupplier(() -> { throw new RuntimeException("fail"); }); }
            catch (Exception ignored) {}
        }

        AtomicInteger calls = new AtomicInteger();
        assertThrows(CallNotPermittedException.class, () ->
                Retry.decorateSupplier(retry, cb.decorateSupplier(() -> {
                    calls.incrementAndGet();
                    return "should not reach here";
                })).get()
        );

        // No actual calls made — circuit was open, retry did not re-attempt
        assertEquals(0, calls.get());
    }
}
