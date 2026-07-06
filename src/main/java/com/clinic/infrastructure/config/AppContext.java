package com.clinic.infrastructure.config;

import com.clinic.application.usecases.CancelAppointmentUseCase;
import com.clinic.application.usecases.CreateAppointmentUseCase;
import com.clinic.application.usecases.GetAppointmentsUseCase;
import com.clinic.application.usecases.ProcessAppointmentUseCase;
import com.clinic.application.usecases.RescheduleAppointmentUseCase;
import com.clinic.domain.ports.AppointmentEventStore;
import com.clinic.domain.ports.AppointmentNotifier;
import com.clinic.infrastructure.messaging.ServiceBusEventPublisher;
import com.clinic.infrastructure.notifications.AcsAppointmentNotifier;
import com.clinic.infrastructure.notifications.NoOpAppointmentNotifier;
import com.clinic.infrastructure.repos.AzureSqlAppointmentRepository;
import com.clinic.infrastructure.repos.CosmosAppointmentEventStore;
import com.clinic.infrastructure.repos.CosmosAppointmentStateRepository;
import com.clinic.shared.HealthStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual composition root (no Spring). Works with the native Azure Functions Java worker. Adapters
 * and use cases are created once (lazy singletons) and reused across warm invocations, reading
 * configuration from environment variables (the Function App's app settings).
 */
public final class AppContext {

  private static final List<String> REQUIRED_ENV_VARS =
      List.of(
          "COSMOS_ENDPOINT",
          "SERVICEBUS__fullyQualifiedNamespace",
          "SQL_HOST",
          "SQL_USER",
          "SQL_PASSWORD",
          "JWT_SECRET");

  static {
    validateNoneMissing(findMissingEnvVars(REQUIRED_ENV_VARS, System::getenv));
  }

  /**
   * Extracted for testability: the static initializer above can't be re-run per test case (a class
   * is only loaded/initialized once per JVM), so the pure decision logic — which vars count as
   * "missing" — lives here where it can be exercised directly with both a real and a fake lookup
   * function.
   */
  static List<String> findMissingEnvVars(
      List<String> required, java.util.function.UnaryOperator<String> envLookup) {
    return required.stream()
        .filter(
            name -> {
              String val = envLookup.apply(name);
              return val == null || val.isBlank();
            })
        .toList();
  }

  /** Extracted for testability alongside {@link #findMissingEnvVars}. */
  static void validateNoneMissing(List<String> missing) {
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Missing required environment variables: " + missing + ". Application cannot start.");
    }
  }

  private static final AtomicReference<CosmosAppointmentStateRepository> stateRepoRef =
      new AtomicReference<>();
  private static final AtomicReference<ServiceBusEventPublisher> publisherRef =
      new AtomicReference<>();
  private static final AtomicReference<AzureSqlAppointmentRepository> relationalRepoRef =
      new AtomicReference<>();
  private static final AtomicReference<CreateAppointmentUseCase> createUseCaseRef =
      new AtomicReference<>();
  private static final AtomicReference<ProcessAppointmentUseCase> processUseCaseRef =
      new AtomicReference<>();
  private static final AtomicReference<GetAppointmentsUseCase> getUseCaseRef =
      new AtomicReference<>();
  private static final AtomicReference<CancelAppointmentUseCase> cancelUseCaseRef =
      new AtomicReference<>();
  private static final AtomicReference<RescheduleAppointmentUseCase> rescheduleUseCaseRef =
      new AtomicReference<>();
  private static final AtomicReference<AppointmentNotifier> notifierRef = new AtomicReference<>();
  private static final AtomicReference<CosmosAppointmentEventStore> cosmosEventStoreRef =
      new AtomicReference<>();

  private AppContext() {}

  // --- shared adapters (built once) ---

  private static CosmosAppointmentStateRepository stateRepository() {
    CosmosAppointmentStateRepository result = stateRepoRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = stateRepoRef.get();
        if (result == null) {
          result =
              new CosmosAppointmentStateRepository(
                  env("COSMOS_ENDPOINT", ""),
                  env("COSMOS_DATABASE", "clinicdb"),
                  env("COSMOS_CONTAINER", "appointments"),
                  ResilienceConfig.exponentialRetry("cosmos"),
                  ResilienceConfig.circuitBreaker("cosmos"));
          stateRepoRef.set(result);
        }
      }
    }
    return result;
  }

  private static ServiceBusEventPublisher eventPublisher() {
    ServiceBusEventPublisher result = publisherRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = publisherRef.get();
        if (result == null) {
          result =
              new ServiceBusEventPublisher(
                  env("SERVICEBUS__fullyQualifiedNamespace", ""),
                  env("SERVICEBUS_CREATED_TOPIC", "appointment-created"),
                  env("SERVICEBUS_COMPLETED_TOPIC", "appointment-completed"),
                  env("SERVICEBUS_CANCELLED_TOPIC", "appointment-cancelled"),
                  ResilienceConfig.exponentialRetry("servicebus"),
                  ResilienceConfig.circuitBreaker("servicebus"));
          publisherRef.set(result);
        }
      }
    }
    return result;
  }

  private static AzureSqlAppointmentRepository relationalRepository() {
    AzureSqlAppointmentRepository result = relationalRepoRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = relationalRepoRef.get();
        if (result == null) {
          result =
              new AzureSqlAppointmentRepository(
                  env("SQL_HOST", ""),
                  env("SQL_DATABASE", "clinicdb"),
                  env("SQL_AUTHENTICATION", "SqlPassword"),
                  env("SQL_USER", ""),
                  env("SQL_PASSWORD", ""),
                  ResilienceConfig.exponentialRetry("sql"),
                  ResilienceConfig.circuitBreaker("sql"));
          relationalRepoRef.set(result);
        }
      }
    }
    return result;
  }

  public static AppointmentEventStore eventStore() {
    CosmosAppointmentEventStore result = cosmosEventStoreRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = cosmosEventStoreRef.get();
        if (result == null) {
          result =
              new CosmosAppointmentEventStore(
                  env("COSMOS_ENDPOINT", ""),
                  env("COSMOS_DATABASE", "clinicdb"),
                  env("COSMOS_EVENTS_CONTAINER", "appointment-events"),
                  ResilienceConfig.exponentialRetry("cosmos-events"),
                  ResilienceConfig.circuitBreaker("cosmos-events"));
          cosmosEventStoreRef.set(result);
        }
      }
    }
    return result;
  }

  private static AppointmentNotifier notifier() {
    AppointmentNotifier result = notifierRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = notifierRef.get();
        if (result == null) {
          String endpoint = env("ACS_ENDPOINT", "");
          String sender = env("ACS_SENDER_ADDRESS", "");
          result =
              endpoint.isBlank()
                  ? new NoOpAppointmentNotifier()
                  : new AcsAppointmentNotifier(endpoint, sender);
          notifierRef.set(result);
        }
      }
    }
    return result;
  }

  // --- use cases ---

  public static CreateAppointmentUseCase createAppointment() {
    CreateAppointmentUseCase result = createUseCaseRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = createUseCaseRef.get();
        if (result == null) {
          result = new CreateAppointmentUseCase(stateRepository(), eventPublisher(), eventStore());
          createUseCaseRef.set(result);
        }
      }
    }
    return result;
  }

  public static ProcessAppointmentUseCase processAppointment() {
    ProcessAppointmentUseCase result = processUseCaseRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = processUseCaseRef.get();
        if (result == null) {
          result =
              new ProcessAppointmentUseCase(
                  stateRepository(),
                  relationalRepository(),
                  eventPublisher(),
                  notifier(),
                  eventStore());
          processUseCaseRef.set(result);
        }
      }
    }
    return result;
  }

  public static GetAppointmentsUseCase getAppointments() {
    GetAppointmentsUseCase result = getUseCaseRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = getUseCaseRef.get();
        if (result == null) {
          result = new GetAppointmentsUseCase(stateRepository());
          getUseCaseRef.set(result);
        }
      }
    }
    return result;
  }

  public static CancelAppointmentUseCase cancelAppointment() {
    CancelAppointmentUseCase result = cancelUseCaseRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = cancelUseCaseRef.get();
        if (result == null) {
          result =
              new CancelAppointmentUseCase(
                  stateRepository(), eventPublisher(), notifier(), eventStore());
          cancelUseCaseRef.set(result);
        }
      }
    }
    return result;
  }

  public static RescheduleAppointmentUseCase rescheduleAppointment() {
    RescheduleAppointmentUseCase result = rescheduleUseCaseRef.get();
    if (result == null) {
      synchronized (AppContext.class) {
        result = rescheduleUseCaseRef.get();
        if (result == null) {
          result =
              new RescheduleAppointmentUseCase(
                  stateRepository(), eventPublisher(), notifier(), eventStore());
          rescheduleUseCaseRef.set(result);
        }
      }
    }
    return result;
  }

  public static HealthStatus healthCheck() {
    Map<String, String> checks = new LinkedHashMap<>();
    checks.put("cosmosDb", stateRepository().ping());
    checks.put("azureSql", relationalRepository().ping());
    checks.put("serviceBus", eventPublisher().ping());
    boolean allUp = checks.values().stream().allMatch("UP"::equals);
    return new HealthStatus(allUp ? HealthStatus.UP : HealthStatus.DOWN, checks);
  }

  private static String env(String name, String defaultValue) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }
}
