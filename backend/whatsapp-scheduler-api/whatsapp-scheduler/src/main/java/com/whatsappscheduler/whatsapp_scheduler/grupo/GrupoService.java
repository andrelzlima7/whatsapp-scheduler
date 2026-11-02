package com.whatsappscheduler.whatsapp_scheduler.grupo;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.whatsappscheduler.whatsapp_scheduler.whatsapp.WorkerClient;

@Service
public class GrupoService {
    private final GrupoRepository repository;
    private final WorkerClient worker;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public GrupoService(GrupoRepository repository, WorkerClient worker, Clock clock, TransactionTemplate transactions) {
        this.repository = repository;
        this.worker = worker;
        this.clock = clock;
        this.transactions = transactions;
    }

    public int sync() {
        // HTTP fora da transação. Uma falha não desativa os grupos já salvos.
        List<WorkerClient.Group> groups = worker.groups();
        return transactions.execute(status -> {
            var existing = repository.findAll();
            existing.forEach(g -> g.ativo = false);
            var byId = new java.util.HashMap<String, Grupo>();
            existing.forEach(g -> byId.put(g.whatsappId, g));
            for (var data : groups) {
                var g = byId.computeIfAbsent(data.id(), id -> {
                    var value = new Grupo();
                    value.whatsappId = id;
                    return value;
                });
                g.nome = data.name().substring(0, Math.min(255, data.name().length()));
                g.somenteAdministradores = data.announcementOnly();
                g.ativo = true;
                g.sincronizadoEm = clock.instant();
                repository.save(g);
            }
            return groups.size();
        });
    }
}
