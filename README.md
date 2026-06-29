[![CI](https://github.com/apchavez/clinic-scheduling-azure/actions/workflows/ci.yml/badge.svg)](https://github.com/apchavez/clinic-scheduling-azure/actions/workflows/ci.yml)

# Clinic Scheduling Platform — Azure (Java 21)

Migración a **Azure** de la plataforma de citas médicas originalmente construida en **AWS** ([clinic-scheduling-platform](https://github.com/apchavez/clinic-scheduling-platform), TypeScript). Misma lógica de negocio y misma Clean Architecture, reescrita en Java 21 sobre Azure Functions.

> El dominio no conoce Azure. Lo que cambia entre nubes es únicamente la capa de infraestructura; los casos de uso y entidades permanecen intactos.

> **Sin costos en reposo** — el CI solo compila y ejecuta tests. Ningún recurso Azure se aprovisiona hasta ejecutar el workflow de deploy manualmente.

---

## Tecnologías

| Categoría | Tecnología |
|---|---|
| Lenguaje / Runtime | Java 21, Azure Functions v4 |
| Estado (NoSQL) | Cosmos DB serverless (Managed Identity) |
| Persistencia relacional | Azure SQL Database (HikariCP, Flyway) |
| Mensajería | Service Bus topics + subscriptions (Managed Identity) |
| Notificaciones | Azure Communication Services Email |
| Resiliencia | Resilience4j — circuit breaker + retry exponencial |
| IaC | Bicep (suscripción-level deployment) |
| Seguridad | Managed Identity, Key Vault references, HTTPS-only |
| Observabilidad | Application Insights, correlation IDs, logs estructurados |
| Documentación API | OpenAPI 3.0 (validado en CI con Redocly) |
| Build / Tests | Maven, JUnit 5, JaCoCo (gate 80 % en domain + application) |
| CI/CD | GitHub Actions (CI automático, deploy/destroy manuales) |

---

## Mapeo AWS → Azure

| AWS (proyecto original) | Azure (este proyecto) |
|---|---|
| AWS Lambda | Azure Functions v4 |
| API Gateway | HTTP trigger (+ APIM opcional) |
| DynamoDB | Cosmos DB |
| MySQL / RDS | Azure SQL Database |
| SNS topic | Service Bus topic |
| SQS queue | Service Bus subscription |
| EventBridge | Service Bus topic `appointment-completed` |
| Serverless Framework | Bicep |
| CloudWatch | Application Insights + Log Analytics |

---

## Arquitectura

Clean Architecture con cuatro capas bien delimitadas:

```
src/main/java/com/clinic/
├── domain/
│   ├── entities/        Appointment, AppointmentEvent, AppointmentStatus, CountryISO
│   ├── ports/           AppointmentStateRepository, AppointmentRelationalRepository,
│   │                    AppointmentEventPublisher, AppointmentEventStore, AppointmentNotifier
│   └── shared/          Page<T>
├── application/
│   └── usecases/        CreateAppointmentUseCase, GetAppointmentsUseCase,
│                        ProcessAppointmentUseCase, CancelAppointmentUseCase,
│                        RescheduleAppointmentUseCase
├── infrastructure/
│   ├── config/          AppContext (composition root), ResilienceConfig
│   ├── messaging/       ServiceBusEventPublisher
│   ├── notifications/   AcsAppointmentNotifier, NoOpAppointmentNotifier
│   └── repos/           CosmosAppointmentStateRepository, CosmosAppointmentEventStore,
│                        AzureSqlAppointmentRepository
└── api/
    └── functions/       HTTP triggers y Service Bus triggers
```

**Regla de dependencias:** `api` / `infrastructure` → `application` → `domain`  
El dominio no importa ninguna clase de Azure. Los tests corren completamente en memoria, sin nube.

---

## Flujo end-to-end

```
POST /api/appointments
  → CreateAppointmentUseCase
      → Cosmos DB (estado PENDING) + evento APPOINTMENT_CREATED
      → Service Bus topic "appointment-created"
          → AppointmentWorkerPE / AppointmentWorkerCL
              → ProcessAppointmentUseCase
                  → Cosmos DB (COMPLETED) + evento APPOINTMENT_COMPLETED
                  → Azure SQL (persistencia final)
                  → ACS Email (notificación al asegurado)
                  → Service Bus topic "appointment-completed"

DELETE /api/appointments/{id}   → CancelAppointmentUseCase  → CANCELLED
PATCH  /api/appointments/{id}/reschedule → RescheduleAppointmentUseCase → RESCHEDULED + nueva cita
GET    /api/appointments/{id}/history    → log de eventos inmutables desde Cosmos DB
```

---

## Ejecutar localmente

Requiere [Azure Functions Core Tools v4](https://learn.microsoft.com/azure/azure-functions/functions-run-local) y una cuenta de Cosmos DB o el emulador.

```bash
# 1. Compilar
mvn clean package

# 2. Configurar variables (copia y edita)
cp local.settings.json.example local.settings.json

# 3. Iniciar
mvn azure-functions:run
```

La función queda disponible en `http://localhost:7071/api`.

Para ejecutar solo los tests (sin nube, sin variables de entorno):

```bash
mvn clean verify
```

---

## Despliegue

El deploy es **exclusivamente manual** vía GitHub Actions (`workflow_dispatch`). El CI nunca aprovisiona recursos Azure.

```
.github/workflows/
├── ci.yml          Push/PR → build, tests, validación OpenAPI   (sin costo Azure)
├── deploy.yml      Manual  → infra Bicep + Function App         (genera costo)
├── destroy.yml     Manual  → elimina el resource group          (detiene costo)
└── integration.yml Manual  → tests Postman contra entorno vivo
```

Para desplegar en Azure es necesario configurar las variables OIDC del entorno (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`) y el secret `SQL_ADMIN_PASSWORD` en el repositorio.

---

## Endpoints

Base path: `/api`

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/appointments` | Crear cita (PENDING → Service Bus) |
| `GET` | `/appointments/{insuredId}` | Listar citas paginadas (cursor-based) |
| `DELETE` | `/appointments/{appointmentId}` | Cancelar cita PENDING |
| `PATCH` | `/appointments/{appointmentId}/reschedule` | Reagendar cita PENDING |
| `GET` | `/appointments/{appointmentId}/history` | Log de eventos de una cita |
| `GET` | `/health` | Estado de Cosmos DB, SQL y Service Bus |

Contrato completo: [`src/docs/openapi.yaml`](src/docs/openapi.yaml)
