package com.whatsappscheduler.whatsapp_scheduler.execucao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ExecucaoRepository extends JpaRepository<Execucao, UUID> {
    Page<Execucao> findByAgendamentoId(Long agendamentoId, Pageable pageable);

    boolean existsByAgendamentoIdAndStatusIn(Long id, List<StatusExecucao> statuses);

    @Query(value = "select * from execucao_agendamento where status in ('PENDENTE','AGUARDANDO_NOVA_TENTATIVA','EM_EXECUCAO') and proxima_tentativa_em <= :now order by proxima_tentativa_em, id limit 1 for update skip locked", nativeQuery = true)
    List<Execucao> ready(@Param("now") Instant now);

    @Modifying
    @Query("update Execucao e set e.status = case when e.resultadoIndeterminado = true then com.whatsappscheduler.whatsapp_scheduler.execucao.StatusExecucao.INCERTO else com.whatsappscheduler.whatsapp_scheduler.execucao.StatusExecucao.CANCELADO end, e.codigoErro = 'AGENDAMENTO_INATIVO', e.finalizadoEm = :now, e.proximaTentativaEm = null, e.versao = e.versao + 1 where e.agendamentoId = :id and e.status in (com.whatsappscheduler.whatsapp_scheduler.execucao.StatusExecucao.PENDENTE, com.whatsappscheduler.whatsapp_scheduler.execucao.StatusExecucao.AGUARDANDO_NOVA_TENTATIVA)")
    int cancelPending(@Param("id") Long id, @Param("now") Instant now);
}
