package com.clinic.infrastructure.config;

import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;

/**
 * Static singleton for OpenTelemetry tracing. Exports traces to Azure Monitor when
 * APPLICATIONINSIGHTS_CONNECTION_STRING is set; falls back to a no-op implementation when the
 * variable is absent so local dev and environments without App Insights are unaffected.
 *
 * <p>Set APPLICATIONINSIGHTS_CONNECTION_STRING in the Function App settings for production.
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
    return buildOpenTelemetry(System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING"));
  }

  /**
   * Extracted for testability: {@code OPEN_TELEMETRY} is built exactly once, in a static field
   * initializer, so the real one-arg overload above can't be re-run per test case with a different
   * env value. This pure overload takes the (potentially env-sourced) connection string directly,
   * so both the no-op and real-exporter paths can be exercised independently.
   */
  static OpenTelemetry buildOpenTelemetry(String connectionString) {
    if (connectionString == null || connectionString.isBlank()) {
      return OpenTelemetry.noop();
    }
    AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder();
    AzureMonitorExporter.customize(builder, connectionString);
    return builder.build().getOpenTelemetrySdk();
  }
}
