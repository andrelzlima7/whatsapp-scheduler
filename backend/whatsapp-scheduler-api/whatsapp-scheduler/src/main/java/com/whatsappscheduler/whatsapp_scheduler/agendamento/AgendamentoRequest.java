package com.whatsappscheduler.whatsapp_scheduler.agendamento;

import java.time.*;

import jakarta.validation.constraints.*;

public record AgendamentoRequest(
        @NotNull @Positive Long grupoId,
        @NotBlank @Size(max = 10000) String texto,
        @NotNull Frequencia frequencia,
        @NotNull LocalTime horario,
        DiaSemana diaSemana,
        @Min(1) @Max(31) Integer diaMes,
        Boolean ultimoDiaMes,
        @NotNull LocalDate inicioVigencia,
        LocalDate fimVigencia,
        @Size(max = 100) String fusoHorario
) {
    public AgendamentoRequest {
        // Campo mensal opcional: ausência ou null equivale a false.
        ultimoDiaMes = Boolean.TRUE.equals(ultimoDiaMes);
    }
}
