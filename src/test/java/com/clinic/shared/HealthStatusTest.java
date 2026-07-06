package com.clinic.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthStatusTest {

  @Test
  void constructor_setsStatusChecksAndTimestamp() {
    Map<String, String> checks = Map.of("cosmosDb", "UP", "azureSql", "UP");

    HealthStatus status = new HealthStatus(HealthStatus.UP, checks);

    assertEquals(HealthStatus.UP, status.status);
    assertSame(checks, status.checks);
    assertNotNull(status.timestamp);
    // Must be a valid ISO-8601 instant string, not just any non-null value.
    Instant.parse(status.timestamp);
  }

  @Test
  void constants_haveExpectedValues() {
    assertEquals("UP", HealthStatus.UP);
    assertEquals("DOWN", HealthStatus.DOWN);
  }
}
