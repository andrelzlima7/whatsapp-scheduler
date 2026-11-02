package com.whatsappscheduler.whatsapp_scheduler;

import java.time.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import com.whatsappscheduler.whatsapp_scheduler.agendamento.*;

class RecorrenciaTests {
    private final Recorrencia calculator = new Recorrencia();

    private Agendamento rule(Frequencia f) {
        var a = new Agendamento();
        a.frequencia = f;
        a.horario = LocalTime.of(9, 0);
        a.inicioVigencia = LocalDate.of(2026, 1, 1);
        a.fusoHorario = "America/Sao_Paulo";
        return a;
    }

    @Test
    void dailyRespectsStartAndConvertsToUtc() {
        var a = rule(Frequencia.DIARIA);
        a.inicioVigencia = LocalDate.of(2026, 11, 10);
        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), calculator.proxima(a, Instant.parse("2026-11-01T00:00:00Z"), true));
    }

    @Test
    void weeklyWaitsForFriday() {
        var a = rule(Frequencia.SEMANAL);
        a.diaSemana = DiaSemana.SEXTA;
        a.inicioVigencia = LocalDate.of(2026, 11, 3);
        assertEquals(Instant.parse("2026-11-06T12:00:00Z"), calculator.proxima(a, Instant.parse("2026-11-01T00:00:00Z"), true));
    }

    @Test
    void day31SkipsFebruary() {
        var a = rule(Frequencia.MENSAL);
        a.diaMes = 31;
        assertEquals(Instant.parse("2026-03-31T12:00:00Z"), calculator.proxima(a, Instant.parse("2026-02-01T00:00:00Z"), true));
    }

    @Test
    void lastDayHandlesLeapYear() {
        var a = rule(Frequencia.MENSAL);
        a.ultimoDiaMes = true;
        assertEquals(Instant.parse("2028-02-29T12:00:00Z"), calculator.proxima(a, Instant.parse("2028-02-01T00:00:00Z"), true));
    }

    @Test
    void endIsInclusiveAndNoOccurrenceFollowsIt() {
        var a = rule(Frequencia.DIARIA);
        a.fimVigencia = LocalDate.of(2026, 11, 2);
        var last = Instant.parse("2026-11-02T12:00:00Z");
        assertEquals(last, calculator.proxima(a, last, true));
        assertNull(calculator.proxima(a, last, false));
    }

    @Test
    void noEndKeepsRecurring() {
        var a = rule(Frequencia.DIARIA);
        assertEquals(Instant.parse("2030-01-02T12:00:00Z"), calculator.proxima(a, Instant.parse("2030-01-01T12:00:00Z"), false));
    }

    @Test
    void weeklyOutsideValidityReturnsNull() {
        var a = rule(Frequencia.SEMANAL);
        a.diaSemana = DiaSemana.SEXTA;
        a.inicioVigencia = LocalDate.of(2026, 11, 3);
        a.fimVigencia = LocalDate.of(2026, 11, 5);
        assertNull(calculator.proxima(a, Instant.parse("2026-11-01T00:00:00Z"), true));
    }

    @Test
    void dstGapMovesForwardAndOverlapDoesNotDuplicate() {
        var a = rule(Frequencia.DIARIA);
        a.fusoHorario = "America/New_York";
        a.horario = LocalTime.of(2, 30);
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), calculator.proxima(a, Instant.parse("2026-03-08T00:00:00Z"), true));
        a.horario = LocalTime.of(1, 30);
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), calculator.proxima(a, Instant.parse("2026-11-01T05:30:00Z"), false));
    }
}
