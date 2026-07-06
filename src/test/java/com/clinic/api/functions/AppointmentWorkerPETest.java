package com.clinic.api.functions;

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

class AppointmentWorkerPETest {

  private final AppointmentWorkerPE worker = new AppointmentWorkerPE();

  @Test
  void run_validMessage_invokesProcessAppointmentUseCase() {
    ExecutionContext context = mock(ExecutionContext.class);
    when(context.getInvocationId()).thenReturn("test-invocation-id");
    when(context.getLogger()).thenReturn(Logger.getLogger("test"));

    ProcessAppointmentUseCase useCase = mock(ProcessAppointmentUseCase.class);
    String message = "{\"appointmentId\":\"apt-2\",\"correlationId\":\"corr-2\"}";

    try (MockedStatic<AppContext> appContext = mockStatic(AppContext.class)) {
      appContext.when(AppContext::processAppointment).thenReturn(useCase);

      worker.run(message, context);

      verify(useCase).execute("apt-2");
    }
  }
}
