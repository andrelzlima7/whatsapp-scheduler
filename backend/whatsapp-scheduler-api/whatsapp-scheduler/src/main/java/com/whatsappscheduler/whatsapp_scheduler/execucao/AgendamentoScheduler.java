package com.whatsappscheduler.whatsapp_scheduler.execucao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.whatsappscheduler.whatsapp_scheduler.whatsapp.*;

@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AgendamentoScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AgendamentoScheduler.class);
    private final ExecucaoService service;
    private final WorkerClient worker;

    public AgendamentoScheduler(ExecucaoService service, WorkerClient worker) {
        this.service = service;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.interval}", initialDelay = 10000)
    public void tick() {
        try {
            service.materialize();
            for (int i = 0; i < 20; i++) {
                Execucao e = service.claim();
                if (e == null) break;
                if (e.status != StatusExecucao.EM_EXECUCAO) continue;
                try {
                    var result = worker.send(e.id.toString(), e.grupoWhatsappId, e.texto, e.limiteEnvioEm);
                    service.success(e.id, result.messageId());
                    LOG.info("Execução {} confirmada", e.id);
                } catch (WorkerException failure) {
                    service.failure(e.id, failure);
                    LOG.warn("Execução {}: {}", e.id, failure.code);
                }
            }
        } catch (Exception e) {
            // Se a gravação do resultado falhar, a lease preserva a recuperação com o mesmo ID.
            LOG.error("Falha no ciclo do agendador: {}", e.getClass().getSimpleName());
        }
    }
}
