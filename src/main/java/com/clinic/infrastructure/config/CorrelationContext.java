package com.clinic.infrastructure.config;

/**
 * Thread-local holder for the correlation ID derived from the incoming
 * X-Correlation-Id header (or the Azure Functions invocationId as fallback).
 * Set at the HTTP handler entry point and cleared in a finally block.
 */
public final class CorrelationContext {

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private CorrelationContext() {}

    public static void set(String id) {
        CORRELATION_ID.set(id);
    }

    public static String get() {
        return CORRELATION_ID.get();
    }

    public static void clear() {
        CORRELATION_ID.remove();
    }
}
