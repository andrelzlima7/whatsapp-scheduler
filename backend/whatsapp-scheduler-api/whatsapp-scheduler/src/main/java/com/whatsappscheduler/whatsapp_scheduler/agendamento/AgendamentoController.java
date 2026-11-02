package com.whatsappscheduler.whatsapp_scheduler.agendamento;

import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.whatsappscheduler.whatsapp_scheduler.config.ApiException;
import com.whatsappscheduler.whatsapp_scheduler.config.Pagina;
import com.whatsappscheduler.whatsapp_scheduler.execucao.*;

@RestController
@RequestMapping("/api/agendamentos")
public class AgendamentoController {
    private final AgendamentoRepository repository;
    private final AgendamentoService service;
    private final ExecucaoRepository executions;

    public AgendamentoController(AgendamentoRepository repository, AgendamentoService service, ExecucaoRepository executions) {
        this.repository = repository;
        this.service = service;
        this.executions = executions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Agendamento create(@Valid @RequestBody AgendamentoRequest request) {
        return service.create(request);
    }

    @GetMapping
    public Pagina<Agendamento> list(@RequestParam(required = false) StatusAgendamento status, @RequestParam(required = false) Long grupoId,
                                    @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "20") int tamanho) {
        return Pagina.of(repository.list(status, grupoId, page(pagina, tamanho, "id")));
    }

    @GetMapping("/{id}")
    public Agendamento get(@PathVariable Long id) {
        return repository.findById(id).orElseThrow(ApiException::notFound);
    }

    @PutMapping("/{id}")
    public Agendamento update(@PathVariable Long id, @Valid @RequestBody AgendamentoRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/pausa")
    public Agendamento pause(@PathVariable Long id) {
        return service.changeStatus(id, StatusAgendamento.PAUSADO);
    }

    @PostMapping("/{id}/reativacao")
    public Agendamento resume(@PathVariable Long id) {
        return service.changeStatus(id, StatusAgendamento.ATIVO);
    }

    @PostMapping("/{id}/cancelamento")
    public Agendamento cancel(@PathVariable Long id) {
        return service.changeStatus(id, StatusAgendamento.CANCELADO);
    }

    @GetMapping("/{id}/execucoes")
    public Pagina<Execucao> history(@PathVariable Long id,
                                    @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "20") int tamanho) {
        get(id);
        return Pagina.of(executions.findByAgendamentoId(id, page(pagina, tamanho, "previstoEm")));
    }

    private Pageable page(int number, int size, String sort) {
        if (number < 0 || size < 1 || size > 100)
            throw ApiException.invalid("Paginação inválida (tamanho entre 1 e 100).");
        return PageRequest.of(number, size, Sort.by(sort).descending());
    }
}
