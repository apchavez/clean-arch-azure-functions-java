package com.clinic.infrastructure.config;

import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

/**
 * Static singleton for OpenTelemetry tracing.
 * Exports traces to Azure Monitor when APPLICATIONINSIGHTS_CONNECTION_STRING is set;
 * falls back to a no-op implementation when the variable is absent so local dev
 * and environments without App Insights are unaffected.
 *
 * Set APPLICATIONINSIGHTS_CONNECTION_STRING in the Function App settings for production.
 */
public final class TelemetryContext {

    private static final String INSTRUMENTATION_NAME = "com.clinic";
    private static final OpenTelemetry OPEN_TELEMETRY = buildOpenTelemetry();
    private static final Tracer TRACER = OPEN_TELEMETRY.getTracer(INSTRUMENTATION_NAME);

    private TelemetryContext() {}

    public static Tracer tracer() {
        return TRACER;
    }

    public static OpenTelemetry openTelemetry() {
        return OPEN_TELEMETRY;
    }

    private static OpenTelemetry buildOpenTelemetry() {
        String connectionString = System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING");
        if (connectionString == null || connectionString.isBlank()) {
            return OpenTelemetry.noop();
        }
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(
                        new AzureMonitorExporterBuilder()
                                .connectionString(connectionString)
                                .buildTraceExporter())
                        .build())
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }
}
