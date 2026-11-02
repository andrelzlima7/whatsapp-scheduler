package com.whatsappscheduler.whatsapp_scheduler.agendamento;

import java.time.*;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.whatsappscheduler.whatsapp_scheduler.config.ApiException;
import com.whatsappscheduler.whatsapp_scheduler.grupo.*;
import com.whatsappscheduler.whatsapp_scheduler.execucao.*;

@Service
public class AgendamentoService {
    private final AgendamentoRepository repository;
    private final GrupoRepository groups;
    private final ExecucaoRepository executions;
    private final Recorrencia recurrence;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public AgendamentoService(AgendamentoRepository repository, GrupoRepository groups, ExecucaoRepository executions, Recorrencia recurrence, Clock clock) {
        this.repository = repository;
        this.groups = groups;
        this.executions = executions;
        this.recurrence = recurrence;
        this.clock = clock;
    }

    @Transactional
    public Agendamento create(AgendamentoRequest request) {
        var a = new Agendamento();
        apply(a, request);
        a.status = StatusAgendamento.ATIVO;
        a.criadoEm = clock.instant();
        a.atualizadoEm = a.criadoEm;
        a.proximaExecucaoEm = recurrence.proxima(a, clock.instant(), true);
        if (a.proximaExecucaoEm == null) throw ApiException.invalid("Não há ocorrência futura dentro da vigência.");
        return repository.save(a);
    }

    @Transactional
    public Agendamento update(Long id, AgendamentoRequest request) {
        var a = locked(id);
        if (a.status == StatusAgendamento.CANCELADO)
            throw new ApiException(409, "AGENDAMENTO_CANCELADO", "Agendamento cancelado não pode ser alterado.");
        if (executions.existsByAgendamentoIdAndStatusIn(id, List.of(StatusExecucao.PENDENTE, StatusExecucao.EM_EXECUCAO, StatusExecucao.AGUARDANDO_NOVA_TENTATIVA))) {
            throw new ApiException(409, "EXECUCAO_EM_ANDAMENTO", "Aguarde a conclusão da ocorrência antes de editar ou pause o agendamento.");
        }
        apply(a, request);
        Instant next = recurrence.proxima(a, clock.instant(), false);
        if (next == null) throw ApiException.invalid("Não há ocorrência futura dentro da vigência.");
        a.proximaExecucaoEm = a.status == StatusAgendamento.ATIVO ? next : null;
        a.atualizadoEm = clock.instant();
        return a;
    }

    @Transactional
    public Agendamento changeStatus(Long id, StatusAgendamento target) {
        var a = locked(id);
        if (a.status == target) return a;
        if (a.status == StatusAgendamento.CANCELADO)
            throw new ApiException(409, "AGENDAMENTO_CANCELADO", "Cancelamento é definitivo.");
        a.status = target;
        a.atualizadoEm = clock.instant();
        if (target == StatusAgendamento.ATIVO) {
            a.proximaExecucaoEm = recurrence.proxima(a, clock.instant(), false);
            if (a.proximaExecucaoEm == null) throw ApiException.invalid("Não há ocorrência futura dentro da vigência.");
        } else {
            a.proximaExecucaoEm = null;
            executions.cancelPending(id, clock.instant());
        }
        return a;
    }

    private Agendamento locked(Long id) {
        return repository.lockById(id).orElseThrow(ApiException::notFound);
    }

    private void apply(Agendamento a, AgendamentoRequest r) {
        Grupo group = groups.findById(r.grupoId()).orElseThrow(() -> ApiException.invalid("Grupo não cadastrado. Sincronize os grupos primeiro."));
        if (!group.ativo) throw ApiException.invalid("Grupo indisponível na última sincronização.");
        String zone = r.fusoHorario() == null ? "America/Sao_Paulo" : r.fusoHorario();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw ApiException.invalid("Fuso horário inválido.");
        }
        if (r.inicioVigencia().getYear() < 1 || r.inicioVigencia().getYear() > 9998 ||
                (r.fimVigencia() != null && (r.fimVigencia().getYear() > 9998 || r.fimVigencia().isBefore(r.inicioVigencia())))) {
            throw ApiException.invalid("Vigência inválida (anos entre 1 e 9998; fim não pode anteceder o início).");
        }
        if (r.horario().getSecond() != 0 || r.horario().getNano() != 0)
            throw ApiException.invalid("Informe horário com precisão de minutos: HH:mm.");
        boolean valid = switch (r.frequencia()) {
            case DIARIA -> r.diaSemana() == null && r.diaMes() == null && !r.ultimoDiaMes();
            case SEMANAL -> r.diaSemana() != null && r.diaMes() == null && !r.ultimoDiaMes();
            case MENSAL -> r.diaSemana() == null && (r.ultimoDiaMes() ? r.diaMes() == null : r.diaMes() != null);
        };
        if (!valid) throw ApiException.invalid("Campos de recorrência incompatíveis com a frequência.");
        int size = json.writeValueAsBytes(Map.of("executionId", "00000000-0000-0000-0000-000000000000", "groupId", group.whatsappId, "text", r.texto(), "sendBefore", "9999-12-31T23:59:59.999999Z")).length;
        if (size > 32 * 1024) throw ApiException.invalid("O conteúdo excede o limite de 32 KiB do serviço de envio.");
        a.grupoId = r.grupoId();
        a.texto = r.texto();
        a.frequencia = r.frequencia();
        a.horario = r.horario();
        a.diaSemana = r.diaSemana();
        a.diaMes = r.diaMes();
        a.ultimoDiaMes = r.ultimoDiaMes();
        a.inicioVigencia = r.inicioVigencia();
        a.fimVigencia = r.fimVigencia();
        a.fusoHorario = zone;
    }
}
