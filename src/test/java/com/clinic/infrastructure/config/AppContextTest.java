package com.clinic.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstructionWithAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.azure.cosmos.CosmosClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.administration.ServiceBusAdministrationClientBuilder;
import com.clinic.application.usecases.CancelAppointmentUseCase;
import com.clinic.application.usecases.CreateAppointmentUseCase;
import com.clinic.application.usecases.GetAppointmentsUseCase;
import com.clinic.application.usecases.ProcessAppointmentUseCase;
import com.clinic.application.usecases.RescheduleAppointmentUseCase;
import com.clinic.domain.ports.AppointmentEventStore;
import com.clinic.infrastructure.repos.CosmosAppointmentStateRepository;
import com.clinic.shared.HealthStatus;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Exercises AppContext's REAL composition-root logic (lazy singleton construction, env var wiring,
 * health aggregation) instead of stubbing it out via {@code mockStatic(AppContext.class)} like the
 * handler tests do.
 *
 * <p>AppContext's private factory methods build real Azure SDK clients (CosmosClientBuilder,
 * ServiceBusClientBuilder, HikariDataSource + Flyway). Those clients perform real network/auth
 * calls on construction, so we intercept construction of the SDK builder types with Mockito's
 * {@code mockConstruction}/{@code mockStatic} (deep-stubbed so every chained builder call and
 * client method returns a harmless mock) rather than letting them touch the network. This lets
 * AppContext's own wiring code run for real: which adapters get built, in what order, and that the
 * lazy singleton caches return the same instance across repeated calls.
 *
 * <p>Required env vars (COSMOS_ENDPOINT, SERVICEBUS__fullyQualifiedNamespace, SQL_HOST, SQL_USER,
 * SQL_PASSWORD, JWT_SECRET) are supplied by maven-surefire-plugin's environmentVariables (pom.xml)
 * so AppContext's static fail-fast initializer doesn't throw when this class loads.
 *
 * <p>ACS_ENDPOINT is left unset (surefire default), so {@code notifier()} takes its
 * NoOpAppointmentNotifier branch; the AcsAppointmentNotifier branch would require mutating process
 * env vars at runtime, which the JDK does not support without reflective module access — not
 * exercised here.
 */
class AppContextTest {

  @Test
  void wiresRealUseCasesAndAdaptersUsingSingletonCaching() {
    try (MockedConstruction<CosmosClientBuilder> cosmos =
            mockConstructionWithAnswer(CosmosClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<ServiceBusClientBuilder> serviceBus =
            mockConstructionWithAnswer(ServiceBusClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<ServiceBusAdministrationClientBuilder> serviceBusAdmin =
            mockConstructionWithAnswer(
                ServiceBusAdministrationClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<HikariDataSource> hikari =
            mockConstructionWithAnswer(HikariDataSource.class, RETURNS_DEEP_STUBS);
        MockedStatic<Flyway> flyway = mockStatic(Flyway.class, RETURNS_DEEP_STUBS)) {

      // --- use-case wiring: each getter builds its full dependency graph on first call ---
      CreateAppointmentUseCase createUseCase = AppContext.createAppointment();
      assertNotNull(createUseCase);
      assertSame(
          createUseCase,
          AppContext.createAppointment(),
          "createAppointment() must return the cached singleton on subsequent calls");

      ProcessAppointmentUseCase processUseCase = AppContext.processAppointment();
      assertNotNull(processUseCase);
      assertSame(processUseCase, AppContext.processAppointment());

      GetAppointmentsUseCase getUseCase = AppContext.getAppointments();
      assertNotNull(getUseCase);
      assertSame(getUseCase, AppContext.getAppointments());

      CancelAppointmentUseCase cancelUseCase = AppContext.cancelAppointment();
      assertNotNull(cancelUseCase);
      assertSame(cancelUseCase, AppContext.cancelAppointment());

      RescheduleAppointmentUseCase rescheduleUseCase = AppContext.rescheduleAppointment();
      assertNotNull(rescheduleUseCase);
      assertSame(rescheduleUseCase, AppContext.rescheduleAppointment());

      AppointmentEventStore eventStore = AppContext.eventStore();
      assertNotNull(eventStore);
      assertSame(eventStore, AppContext.eventStore());

      // --- health check aggregation: pings every adapter and rolls up UP/DOWN ---
      HealthStatus health = AppContext.healthCheck();
      assertEquals(HealthStatus.UP, health.status);
      assertEquals("UP", health.checks.get("cosmosDb"));
      assertEquals("UP", health.checks.get("azureSql"));
      assertEquals("UP", health.checks.get("serviceBus"));
    }
  }

  /**
   * {@code healthCheck()}'s {@code allMatch("UP"::equals)} rollup only ever saw the all-UP branch
   * above (every adapter's real {@code ping()} succeeds against the deep-stubbed SDK mocks). This
   * forces one dependency down by swapping the cached singleton for a mock directly — bypassing
   * construction entirely, since the singleton is already primed by the previous test — to exercise
   * the DOWN rollup branch.
   */
  @Test
  void healthCheckReportsDownWhenAnyDependencyPingFails() throws Exception {
    try (MockedConstruction<CosmosClientBuilder> cosmos =
            mockConstructionWithAnswer(CosmosClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<ServiceBusClientBuilder> serviceBus =
            mockConstructionWithAnswer(ServiceBusClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<ServiceBusAdministrationClientBuilder> serviceBusAdmin =
            mockConstructionWithAnswer(
                ServiceBusAdministrationClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<HikariDataSource> hikari =
            mockConstructionWithAnswer(HikariDataSource.class, RETURNS_DEEP_STUBS);
        MockedStatic<Flyway> flyway = mockStatic(Flyway.class, RETURNS_DEEP_STUBS)) {

      // Warm up all three real singletons first (all-UP path), then force cosmos down.
      AppContext.healthCheck();

      CosmosAppointmentStateRepository failingRepo = mock(CosmosAppointmentStateRepository.class);
      when(failingRepo.ping()).thenReturn("DOWN: cosmos unreachable");
      setRef("stateRepoRef", failingRepo);
      // Note: reset by resetAllSingletonCaches() in @AfterEach — no manual cleanup needed here.

      HealthStatus health = AppContext.healthCheck();
      assertEquals(HealthStatus.DOWN, health.status);
      assertEquals("DOWN: cosmos unreachable", health.checks.get("cosmosDb"));
      assertEquals("UP", health.checks.get("azureSql"));
      assertEquals("UP", health.checks.get("serviceBus"));
    }
  }

  /**
   * Every lazy singleton getter in AppContext uses double-checked locking: an unsynchronized outer
   * {@code if (result == null)} check, then a synchronized re-check before constructing. The outer
   * check's both branches (build vs. cached-return) are already covered by the sequential test
   * above. The synchronized re-check's "someone else already built it while I was waiting for the
   * lock" branch can only be reached by genuine concurrent contention — multiple threads passing
   * the outer null check before the winner finishes construction.
   *
   * <p>This is only safe to force for real here for the six accessors below, whose construction is
   * pure Java (no SDK client, no network): {@code notifier()} (with {@code ACS_ENDPOINT} unset,
   * it's just {@code new NoOpAppointmentNotifier()}) and the five use-case getters (plain
   * constructors over already-resolved dependencies). We deliberately do NOT attempt this for the
   * four SDK-backed singletons ({@code stateRepository}, {@code eventPublisher}, {@code
   * relationalRepository}, {@code eventStore}): a first attempt at real concurrent access to those
   * caused actual Cosmos SDK client construction and real network calls, because Mockito's {@code
   * mockConstruction} is documented as unreliable across worker threads — the interception can
   * silently miss a construction that happens on a thread other than the one that registered it. A
   * follow-up attempt to fake the race deterministically via reflectively swapping the backing
   * {@code AtomicReference} field for a stubbed mock (bypassing {@code Field.set}'s final-field
   * rejection via {@code sun.misc.Unsafe}) was also unreliable in practice — the swap wasn't
   * consistently observed by the already-compiled production code path. Those four inner-recheck
   * branches are left uncovered: they're the standard, behaviorally-inert half of a correct
   * double-checked-locking idiom (same outcome, cached instance, either way), and forcing them
   * further isn't worth chasing.
   */
  @Test
  void doubleCheckedLockingReturnsSameSingletonUnderConcurrentFirstAccess() throws Exception {
    try (MockedConstruction<CosmosClientBuilder> cosmos =
            mockConstructionWithAnswer(CosmosClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<ServiceBusClientBuilder> serviceBus =
            mockConstructionWithAnswer(ServiceBusClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<ServiceBusAdministrationClientBuilder> serviceBusAdmin =
            mockConstructionWithAnswer(
                ServiceBusAdministrationClientBuilder.class, RETURNS_DEEP_STUBS);
        MockedConstruction<HikariDataSource> hikari =
            mockConstructionWithAnswer(HikariDataSource.class, RETURNS_DEEP_STUBS);
        MockedStatic<Flyway> flyway = mockStatic(Flyway.class, RETURNS_DEEP_STUBS)) {

      // Pre-warm the SDK-backed singletons single-threaded (safe: construction is guarded by
      // this try's MockedConstruction scope). The six accessors raced below only ever need these
      // already-cached dependencies, so no SDK construction happens during the race itself.
      AppContext.eventStore();
      invokePrivate("stateRepository");
      invokePrivate("eventPublisher");
      invokePrivate("relationalRepository");

      assertNotNull(raceToConstruct("notifier"));
      assertNotNull(raceToConstruct("createAppointment"));
      assertNotNull(raceToConstruct("processAppointment"));
      assertNotNull(raceToConstruct("getAppointments"));
      assertNotNull(raceToConstruct("cancelAppointment"));
      assertNotNull(raceToConstruct("rescheduleAppointment"));
    }
  }

  private static Object invokePrivate(String methodName) throws Exception {
    Method m = AppContext.class.getDeclaredMethod(methodName);
    m.setAccessible(true);
    return m.invoke(null);
  }

  /**
   * Fires many threads at the given no-arg private static AppContext method simultaneously
   * (released together via a latch after all have parked waiting on it) to force real contention on
   * its double-checked-locking singleton, and asserts every thread observed the exact same
   * instance.
   */
  private static Object raceToConstruct(String methodName) throws Exception {
    Method m = AppContext.class.getDeclaredMethod(methodName);
    m.setAccessible(true);
    int threadCount = 32;
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    java.util.concurrent.CountDownLatch ready =
        new java.util.concurrent.CountDownLatch(threadCount);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return m.invoke(null);
                }));
      }
      ready.await();
      go.countDown();
      java.util.Set<Object> results = new java.util.HashSet<>();
      for (java.util.concurrent.Future<Object> f : futures) {
        results.add(f.get());
      }
      assertEquals(
          1,
          results.size(),
          methodName + "() must return one singleton to every concurrent caller");
      return results.iterator().next();
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void findMissingEnvVarsFlagsNullAndBlankValues() {
    java.util.Map<String, String> env =
        java.util.Map.of(
            "PRESENT", "value",
            "BLANK", "   ");
    List<String> missing =
        AppContext.findMissingEnvVars(List.of("PRESENT", "BLANK", "ABSENT"), name -> env.get(name));
    assertEquals(List.of("BLANK", "ABSENT"), missing);
  }

  @Test
  void findMissingEnvVarsReturnsEmptyWhenAllPresent() {
    List<String> missing = AppContext.findMissingEnvVars(List.of("A", "B"), name -> "set-" + name);
    assertTrue(missing.isEmpty());
  }

  @Test
  void validateNoneMissingThrowsWhenVarsAreMissing() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> AppContext.validateNoneMissing(List.of("SOME_VAR")));
    assertTrue(ex.getMessage().contains("SOME_VAR"));
  }

  @Test
  void validateNoneMissingDoesNothingWhenNoVarsAreMissing() {
    AppContext.validateNoneMissing(List.of());
  }

  private static final List<String> ALL_REF_FIELDS =
      List.of(
          "stateRepoRef",
          "publisherRef",
          "relationalRepoRef",
          "createUseCaseRef",
          "processUseCaseRef",
          "getUseCaseRef",
          "cancelUseCaseRef",
          "rescheduleUseCaseRef",
          "notifierRef",
          "cosmosEventStoreRef");

  /**
   * Every AtomicReference singleton cache is cleared back to {@code null} after each test —
   * regardless of which test ran or what it did to the field — so tests never leak singleton state
   * into one another irrespective of execution order.
   */
  @org.junit.jupiter.api.AfterEach
  void resetAllSingletonCaches() throws Exception {
    for (String fieldName : ALL_REF_FIELDS) {
      setRef(fieldName, null);
    }
  }

  /** Directly seeds the named singleton cache field's current value (bypassing construction). */
  @SuppressWarnings("unchecked")
  private static void setRef(String fieldName, Object value) throws Exception {
    Field f = AppContext.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    ((AtomicReference<Object>) f.get(null)).set(value);
  }
}
