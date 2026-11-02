package com.whatsappscheduler.whatsapp_scheduler.execucao;

import java.time.*;
import java.util.UUID;

import jakarta.persistence.*;

@Entity
@Table(name = "execucao_agendamento")
public class Execucao {
    @Id
    public UUID id;

    @Column(name = "agendamento_id", nullable = false)
    public Long agendamentoId;

    @Column(name = "previsto_em", nullable = false)
    public Instant previstoEm;

    @Column(name = "grupo_whatsapp_id", nullable = false, length = 100)
    public String grupoWhatsappId;

    @Column(nullable = false, columnDefinition = "text")
    public String texto;

    @Column(name = "limite_envio_em", nullable = false)
    public Instant limiteEnvioEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    public StatusExecucao status;

    @Column(nullable = false)
    public int tentativas;

    @Column(name = "proxima_tentativa_em")
    public Instant proximaTentativaEm;

    @Column(name = "iniciado_em")
    public Instant iniciadoEm;

    @Column(name = "finalizado_em")
    public Instant finalizadoEm;

    @Column(name = "mensagem_whatsapp_id", length = 200)
    public String mensagemWhatsappId;

    @Column(name = "codigo_erro", length = 100)
    public String codigoErro;

    @Column(name = "resultado_indeterminado", nullable = false)
    public boolean resultadoIndeterminado;

    @Version
    @Column(nullable = false)
    public long versao;
}
