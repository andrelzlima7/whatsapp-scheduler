package com.whatsappscheduler.whatsapp_scheduler.agendamento;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AgendamentoRepository extends JpaRepository<Agendamento, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agendamento a where a.id = :id")
    Optional<Agendamento> lockById(@Param("id") Long id);

    @Query("select a from Agendamento a where (:status is null or a.status = :status) and (:grupoId is null or a.grupoId = :grupoId)")
    Page<Agendamento> list(@Param("status") StatusAgendamento status, @Param("grupoId") Long grupoId, Pageable pageable);

    @Query(value = "select * from agendamento where status = 'ATIVO' and proxima_execucao_em <= :now order by proxima_execucao_em, id limit 20 for update skip locked", nativeQuery = true)
    List<Agendamento> due(@Param("now") Instant now);
}
