package com.clinic.infrastructure.repos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mockStatic;

import com.clinic.infrastructure.config.ResilienceConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Exercises the "build HikariConfig from raw env-style strings" constructor directly — the one
 * AppContext wires up with real env vars, always passing "SqlPassword" as the default. That means
 * the {@code authMode} fallback (null/blank -> "SqlPassword") and the
 * ActiveDirectoryManagedIdentity branch of the JDBC URL builder were never exercised by any test.
 * Real {@code HikariDataSource}/{@code Flyway} construction is intercepted (as in
 * AppContextTest/CosmosAppointmentStateRepositoryTest) so this runs with no real network/DB.
 */
class AzureSqlAppointmentRepositoryUnitTest {

  private HikariConfig construct(String authentication) {
    List<HikariConfig> captured = new ArrayList<>();
    try (MockedConstruction<HikariDataSource> hikari =
            Mockito.mockConstruction(
                HikariDataSource.class,
                (mock, context) -> captured.add((HikariConfig) context.arguments().get(0)));
        MockedStatic<Flyway> flyway = mockStatic(Flyway.class, RETURNS_DEEP_STUBS)) {

      new AzureSqlAppointmentRepository(
          "sql-host",
          "clinicdb",
          authentication,
          "the-user",
          "the-password",
          ResilienceConfig.exponentialRetry("test-sql-auth"),
          ResilienceConfig.circuitBreaker("test-sql-auth"));
    }
    assertEquals(1, captured.size());
    return captured.get(0);
  }

  @Test
  void nullAuthentication_defaultsToSqlPasswordAndSetsCredentials() {
    HikariConfig cfg = construct(null);

    assertFalse(cfg.getJdbcUrl().contains("authentication=ActiveDirectoryManagedIdentity"));
    assertEquals("the-user", cfg.getUsername());
  }

  @Test
  void blankAuthentication_defaultsToSqlPasswordAndSetsCredentials() {
    HikariConfig cfg = construct("   ");

    assertFalse(cfg.getJdbcUrl().contains("authentication=ActiveDirectoryManagedIdentity"));
    assertEquals("the-user", cfg.getUsername());
  }

  @Test
  void explicitSqlPasswordAuthentication_setsCredentials() {
    HikariConfig cfg = construct("SqlPassword");

    assertFalse(cfg.getJdbcUrl().contains("authentication=ActiveDirectoryManagedIdentity"));
    assertEquals("the-user", cfg.getUsername());
  }

  @Test
  void activeDirectoryManagedIdentityAuthentication_appendsAuthModeAndSkipsCredentials() {
    HikariConfig cfg = construct("ActiveDirectoryManagedIdentity");

    assertTrue(cfg.getJdbcUrl().contains("authentication=ActiveDirectoryManagedIdentity;"));
    // Credentials must NOT be set on this path — managed identity authenticates without them.
    assertEquals(null, cfg.getUsername());
  }
}
