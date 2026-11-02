package com.whatsappscheduler.whatsapp_scheduler.agendamento;

import java.time.*;

import jakarta.persistence.*;

@Entity
@Table(name = "agendamento")
public class Agendamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "grupo_id", nullable = false)
    public Long grupoId;

    @Column(nullable = false, columnDefinition = "text")
    public String texto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Frequencia frequencia;

    @Column(nullable = false)
    public LocalTime horario;

    @Enumerated(EnumType.STRING)
    @Column(name = "dia_semana", length = 10)
    public DiaSemana diaSemana;

    @Column(name = "dia_mes")
    public Integer diaMes;

    @Column(name = "ultimo_dia_mes", nullable = false)
    public boolean ultimoDiaMes;

    @Column(name = "inicio_vigencia", nullable = false)
    public LocalDate inicioVigencia;

    @Column(name = "fim_vigencia")
    public LocalDate fimVigencia;

    @Column(name = "fuso_horario", nullable = false, length = 100)
    public String fusoHorario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public StatusAgendamento status;

    @Column(name = "proxima_execucao_em")
    public Instant proximaExecucaoEm;

    @Column(name = "criado_em", nullable = false)
    public Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    public Instant atualizadoEm;

    @Version
    @Column(nullable = false)
    public long versao;
}
