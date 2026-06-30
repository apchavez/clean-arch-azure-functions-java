package com.clinic.infrastructure;

import com.clinic.domain.entities.Appointment;
import com.clinic.domain.entities.AppointmentStatus;
import com.clinic.domain.entities.CountryISO;
import com.clinic.infrastructure.config.ResilienceConfig;
import com.clinic.infrastructure.repos.AzureSqlAppointmentRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@Testcontainers
class AzureSqlAppointmentRepositoryIT {

    @Container
    static final MSSQLServerContainer<?> mssql =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    static AzureSqlAppointmentRepository repository;
    static HikariDataSource dataSource;

    @BeforeAll
    static void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(mssql.getJdbcUrl() + ";trustServerCertificate=true");
        cfg.setUsername(mssql.getUsername());
        cfg.setPassword(mssql.getPassword());
        cfg.setMaximumPoolSize(5);
        cfg.setPoolName("it-sql-pool");
        dataSource = new HikariDataSource(cfg);

        repository = new AzureSqlAppointmentRepository(
                dataSource,
                ResilienceConfig.exponentialRetry("it-retry"),
                ResilienceConfig.circuitBreaker("it-cb")
        );
    }

    @Test
    void persist_insertsNewAppointment() throws Exception {
        Appointment appt = new Appointment("APPT-001", "INS-100", 10, CountryISO.PE);
        appt.markCompleted();

        repository.persist(appt);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT appointment_id, insured_id, status FROM appointments WHERE appointment_id = ?")) {
            ps.setString(1, "APPT-001");
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "Row should exist after persist");
            assertEquals("APPT-001", rs.getString("appointment_id"));
            assertEquals("INS-100", rs.getString("insured_id"));
            assertEquals(AppointmentStatus.COMPLETED.name(), rs.getString("status"));
        }
    }

    @Test
    void persist_upsertUpdatesExistingStatus() throws Exception {
        Appointment appt = new Appointment("APPT-002", "INS-200", 20, CountryISO.CL);
        repository.persist(appt);

        appt.markCompleted();
        repository.persist(appt);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status, completed_at FROM appointments WHERE appointment_id = ?")) {
            ps.setString(1, "APPT-002");
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(AppointmentStatus.COMPLETED.name(), rs.getString("status"));
            assertNotNull(rs.getTimestamp("completed_at"), "completed_at should be set after upsert");
        }
    }

    @Test
    void ping_returnsUp() {
        assertEquals("UP", repository.ping());
    }
}
