package com.whatsappscheduler.whatsapp_scheduler.execucao;

import java.time.*;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.whatsappscheduler.whatsapp_scheduler.agendamento.*;
import com.whatsappscheduler.whatsapp_scheduler.grupo.GrupoRepository;
import com.whatsappscheduler.whatsapp_scheduler.whatsapp.WorkerException;

@Service
public class ExecucaoService {
    private final AgendamentoRepository schedules;
    private final ExecucaoRepository executions;
    private final GrupoRepository groups;
    private final Recorrencia recurrence;
    private final Clock clock;
    private final Duration tolerance;

    public ExecucaoService(AgendamentoRepository schedules, ExecucaoRepository executions, GrupoRepository groups,
                           Recorrencia recurrence, Clock clock, @Value("${app.scheduler.tolerance-minutes}") long minutes) {
        if (minutes < 1 || minutes > 1440)
            throw new IllegalStateException("Tolerância deve estar entre 1 e 1440 minutos.");
        this.schedules = schedules;
        this.executions = executions;
        this.groups = groups;
        this.recurrence = recurrence;
        this.clock = clock;
        this.tolerance = Duration.ofMinutes(minutes);
    }

    @Transactional
    public int materialize() {
        Instant now = clock.instant();
        var due = schedules.due(now);
        for (var a : due) {
            var e = new Execucao();
            e.id = UUID.randomUUID();
            e.agendamentoId = a.id;
            e.previstoEm = a.proximaExecucaoEm;
            e.grupoWhatsappId = groups.findById(a.grupoId).orElseThrow().whatsappId;
            e.texto = a.texto;
            Instant limit = e.previstoEm.plus(tolerance);
            if (a.fimVigencia != null) {
                Instant endExclusive = a.fimVigencia.plusDays(1).atStartOfDay(ZoneId.of(a.fusoHorario)).toInstant();
                // Um microssegundo mantém a precisão do PostgreSQL sem permitir o dia seguinte.
                if (!limit.isBefore(endExclusive)) limit = endExclusive.minusNanos(1000);
            }
            e.limiteEnvioEm = limit;
            boolean expired = now.isAfter(limit);
            e.status = expired ? StatusExecucao.EXPIRADO : StatusExecucao.PENDENTE;
            e.codigoErro = expired ? "FORA_DA_JANELA" : null;
            e.finalizadoEm = expired ? now : null;
            e.proximaTentativaEm = expired ? null : now;
            executions.save(e);
            // Pula o acúmulo de ocorrências antigas em uma única operação.
            Instant cutoff = now.minus(tolerance);
            boolean skipBacklog = expired && cutoff.isAfter(e.previstoEm);
            a.proximaExecucaoEm = recurrence.proxima(a, skipBacklog ? cutoff : e.previstoEm, skipBacklog);
            a.atualizadoEm = now;
        }
        return due.size();
    }

    @Transactional
    public Execucao claim() {
        Instant now = clock.instant();
        var ready = executions.ready(now);
        if (ready.isEmpty()) return null;
        Execucao e = ready.getFirst();
        var schedule = schedules.findById(e.agendamentoId).orElseThrow();
        if (schedule.status != StatusAgendamento.ATIVO || now.isAfter(e.limiteEnvioEm)) {
            e.status = e.resultadoIndeterminado ? StatusExecucao.INCERTO :
                    (schedule.status == StatusAgendamento.ATIVO ? StatusExecucao.EXPIRADO : StatusExecucao.CANCELADO);
            e.codigoErro = schedule.status == StatusAgendamento.ATIVO ? "FORA_DA_JANELA" : "AGENDAMENTO_INATIVO";
            e.finalizadoEm = now;
            e.proximaTentativaEm = null;
            return e;
        }
        if (e.tentativas >= 5) {
            e.status = e.resultadoIndeterminado ? StatusExecucao.INCERTO : StatusExecucao.FALHOU;
            e.codigoErro = "LIMITE_TENTATIVAS";
            e.finalizadoEm = now;
            e.proximaTentativaEm = null;
            return e;
        }
        // Lease permite recuperar EM_EXECUCAO após interrupção do Java.
        e.status = StatusExecucao.EM_EXECUCAO;
        e.tentativas++;
        e.resultadoIndeterminado = true;
        if (e.iniciadoEm == null) e.iniciadoEm = now;
        e.proximaTentativaEm = now.plusSeconds(120);
        return e;
    }

    @Transactional
    public void success(UUID id, String messageId) {
        var e = executions.findById(id).orElseThrow();
        e.status = StatusExecucao.ENVIADO;
        e.mensagemWhatsappId = messageId;
        e.codigoErro = null;
        e.resultadoIndeterminado = false;
        e.finalizadoEm = clock.instant();
        e.proximaTentativaEm = null;
    }

    @Transactional
    public void failure(UUID id, WorkerException failure) {
        var e = executions.findById(id).orElseThrow();
        Instant now = clock.instant();
        e.codigoErro = failure.code;
        e.resultadoIndeterminado = failure.uncertain();
        Instant retryAt = now.plusSeconds(Math.min(300, 30L << Math.min(e.tentativas - 1, 4)));
        if (failure.retryable() && e.tentativas < 5 && !retryAt.isAfter(e.limiteEnvioEm)) {
            e.status = StatusExecucao.AGUARDANDO_NOVA_TENTATIVA;
            e.proximaTentativaEm = retryAt;
        } else {
            e.status = e.resultadoIndeterminado ? StatusExecucao.INCERTO :
                    (failure.code.equals("SEND_EXPIRED") ? StatusExecucao.EXPIRADO : StatusExecucao.FALHOU);
            e.finalizadoEm = now;
            e.proximaTentativaEm = null;
        }
    }
}
