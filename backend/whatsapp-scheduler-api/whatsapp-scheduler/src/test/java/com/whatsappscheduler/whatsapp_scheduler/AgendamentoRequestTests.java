package com.whatsappscheduler.whatsapp_scheduler;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.whatsappscheduler.whatsapp_scheduler.agendamento.AgendamentoRequest;

import static org.junit.jupiter.api.Assertions.*;

class AgendamentoRequestTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void weeklyExampleAllowsOmittedMonthlyFlag() {
        var request = json.readValue("""
                {"grupoId":6,"texto":"Lembrete da programação!","frequencia":"SEMANAL",
                 "diaSemana":"SEXTA","horario":"18:00","inicioVigencia":"2026-11-01",
                 "fimVigencia":"2026-11-30","fusoHorario":"America/Sao_Paulo"}
                """, AgendamentoRequest.class);
        assertFalse(request.ultimoDiaMes());
    }

    @Test
    void explicitNullDefaultsToFalseAndTrueIsPreserved() {
        assertFalse(json.readValue("{\"ultimoDiaMes\":null}", AgendamentoRequest.class).ultimoDiaMes());
        assertTrue(json.readValue("{\"ultimoDiaMes\":true}", AgendamentoRequest.class).ultimoDiaMes());
    }
}
