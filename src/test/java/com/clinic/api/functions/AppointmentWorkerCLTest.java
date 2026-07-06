package com.clinic.api.functions;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clinic.application.usecases.ProcessAppointmentUseCase;
import com.clinic.infrastructure.config.AppContext;
import com.microsoft.azure.functions.ExecutionContext;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class AppointmentWorkerCLTest {

  private final AppointmentWorkerCL worker = new AppointmentWorkerCL();

  private static ExecutionContext mockWorkerContext() {
    ExecutionContext context = mock(ExecutionContext.class);
    when(context.getInvocationId()).thenReturn("test-invocation-id");
    when(context.getLogger()).thenReturn(Logger.getLogger("test"));
    return context;
  }

  @Test
  void run_validMessage_invokesProcessAppointmentUseCase() {
    ProcessAppointmentUseCase useCase = mock(ProcessAppointmentUseCase.class);
    String message = "{\"appointmentId\":\"apt-1\",\"correlationId\":\"corr-1\"}";

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::processAppointment).thenReturn(useCase);

      worker.run(message, mockWorkerContext());

      verify(useCase).execute("apt-1");
    }
  }

  @Test
  void run_useCaseThrows_wrapsInRuntimeException() {
    ProcessAppointmentUseCase useCase = mock(ProcessAppointmentUseCase.class);
    String message = "{\"appointmentId\":\"apt-1\"}";
    org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
        .when(useCase)
        .execute("apt-1");

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::processAppointment).thenReturn(useCase);

      assertThrows(RuntimeException.class, () -> worker.run(message, mockWorkerContext()));
    }
  }

  @Test
  void run_malformedJson_throwsRuntimeException() {
    assertThrows(RuntimeException.class, () -> worker.run("not-json", mockWorkerContext()));
  }
}
