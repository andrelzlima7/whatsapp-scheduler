package com.whatsappscheduler.whatsapp_scheduler.agendamento;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

import org.springframework.stereotype.Component;

@Component
public class Recorrencia {
    /**
     * Próxima ocorrência a partir do instante informado; null quando não houver na vigência.
     */
    public Instant proxima(Agendamento regra, Instant referencia, boolean inclusiva) {
        ZoneId zone = ZoneId.of(regra.fusoHorario);
        LocalDate date = referencia.atZone(zone).toLocalDate();
        if (date.isBefore(regra.inicioVigencia)) date = regra.inicioVigencia;
        while (true) {
            if (regra.fimVigencia != null && date.isAfter(regra.fimVigencia)) return null;
            LocalDate candidate;
            switch (regra.frequencia) {
                case DIARIA -> candidate = date;
                case SEMANAL ->
                        candidate = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(regra.diaSemana.ordinal() + 1)));
                case MENSAL -> {
                    YearMonth month = YearMonth.from(date);
                    if (!regra.ultimoDiaMes && regra.diaMes > month.lengthOfMonth()) {
                        if (month.getYear() >= 9999) return null;
                        date = month.plusMonths(1).atDay(1);
                        continue;
                    }
                    candidate = regra.ultimoDiaMes ? month.atEndOfMonth() : month.atDay(regra.diaMes);
                    if (candidate.isBefore(date)) {
                        if (month.getYear() >= 9999) return null;
                        date = month.plusMonths(1).atDay(1);
                        continue;
                    }
                }
                default -> throw new IllegalStateException("Frequência inválida");
            }
            if (candidate.getYear() > 9999 || (regra.fimVigencia != null && candidate.isAfter(regra.fimVigencia)))
                return null;
            // Em lacunas de horário de verão, Java avança pelo tamanho da lacuna;
            // em horários ambíguos, escolhe o primeiro offset. Uma ocorrência por data.
            Instant instant = candidate.atTime(regra.horario).atZone(zone).toInstant();
            if (instant.isAfter(referencia) || (inclusiva && instant.equals(referencia))) return instant;
            if (candidate.getYear() >= 9999 && candidate.getDayOfYear() == candidate.lengthOfYear()) return null;
            date = candidate.plusDays(1);
        }
    }
}
