package com.clinic.infrastructure.repos;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.CountryISO;
import com.clinic.infrastructure.config.ResilienceConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Exercises persist()'s and ping()'s catch(Exception) branches, which the Testcontainers-backed
 * AzureSqlAppointmentRepositoryIT never triggers since its SQL Server container is always healthy.
 * Uses a mocked HikariDataSource (real Flyway migration intercepted, as in
 * AzureSqlAppointmentRepositoryUnitTest) so a broken connection can be simulated with no real DB.
 */
class AzureSqlAppointmentRepositoryErrorPathTest {

  private final HikariDataSource dataSource = mock(HikariDataSource.class);
  private AzureSqlAppointmentRepository repository;

  @BeforeEach
  void setUp() {
    try (MockedStatic<Flyway> flyway = mockStatic(Flyway.class, RETURNS_DEEP_STUBS)) {
      repository =
          new AzureSqlAppointmentRepository(
              dataSource,
              ResilienceConfig.exponentialRetry("test-sql-error-path"),
              ResilienceConfig.circuitBreaker("test-sql-error-path"));
    }
  }

  @Test
  void persist_connectionFailure_wrapsInRuntimeExceptionWithOriginalMessage() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
    Appointment appointment = new Appointment("apt-1", "insured-1", 42, CountryISO.PE);

    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> repository.persist(appointment));

    assertTrue(ex.getMessage().contains("Failed to persist appointment to Azure SQL"));
    assertTrue(ex.getMessage().contains("connection refused"));
    assertTrue(ex.getCause() instanceof SQLException);
  }

  @Test
  void ping_connectionFailure_returnsDownWithMessage() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("login timeout expired"));

    String status = repository.ping();

    assertTrue(status.startsWith("DOWN: "));
    assertTrue(status.contains("login timeout expired"));
  }
}
