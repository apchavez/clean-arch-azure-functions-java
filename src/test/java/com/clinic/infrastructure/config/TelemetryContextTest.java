package com.clinic.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

/**
 * APPLICATIONINSIGHTS_CONNECTION_STRING is not set in the test environment, so this exercises the
 * no-op fallback path (buildOpenTelemetry's early return) — the branch that matters for local dev
 * and CI, where App Insights is never configured.
 */
class TelemetryContextTest {

  @Test
  void tracer_returnsNonNullTracer() {
    assertNotNull(TelemetryContext.tracer());
  }

  @Test
  void openTelemetry_returnsNonNullAndIsStable() {
    assertNotNull(TelemetryContext.openTelemetry());
    // Static singleton — repeated calls must return the same instance.
    assertSame(TelemetryContext.openTelemetry(), TelemetryContext.openTelemetry());
  }

  @Test
  void tracer_isStableAcrossCalls() {
    assertSame(TelemetryContext.tracer(), TelemetryContext.tracer());
  }

  // --- buildOpenTelemetry(String): the real, one-arg-parameterized decision logic. The static
  // OPEN_TELEMETRY field above is built exactly once at class-load with the real env var (always
  // absent here), so these call the package-private overload directly with fake values to reach
  // the branches class-loading-order can't reach otherwise.

  @Test
  void buildOpenTelemetry_nullConnectionString_returnsNoop() {
    assertSame(OpenTelemetry.noop(), TelemetryContext.buildOpenTelemetry(null));
  }

  @Test
  void buildOpenTelemetry_blankConnectionString_returnsNoop() {
    assertSame(OpenTelemetry.noop(), TelemetryContext.buildOpenTelemetry("   "));
  }

  @Test
  void buildOpenTelemetry_realConnectionString_buildsRealExporter() {
    OpenTelemetry result =
        TelemetryContext.buildOpenTelemetry(
            "InstrumentationKey=00000000-0000-0000-0000-000000000000;"
                + "IngestionEndpoint=https://westus2-1.in.applicationinsights.azure.com/");

    assertNotNull(result);
    assertNotSame(OpenTelemetry.noop(), result);
  }
}
