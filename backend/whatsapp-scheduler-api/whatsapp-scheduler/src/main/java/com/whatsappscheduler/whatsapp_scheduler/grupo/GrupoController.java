package com.whatsappscheduler.whatsapp_scheduler.grupo;

import java.util.Map;

import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;
import com.whatsappscheduler.whatsapp_scheduler.config.ApiException;
import com.whatsappscheduler.whatsapp_scheduler.config.Pagina;

@RestController
@RequestMapping("/api/grupos")
public class GrupoController {
    private final GrupoRepository repository;
    private final GrupoService service;

    public GrupoController(GrupoRepository repository, GrupoService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public Pagina<Grupo> list(@RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "20") int tamanho) {
        if (pagina < 0 || tamanho < 1 || tamanho > 100)
            throw ApiException.invalid("Paginação inválida (tamanho entre 1 e 100).");
        return Pagina.of(repository.findAll(PageRequest.of(pagina, tamanho, Sort.by("nome").ascending())));
    }

    @PostMapping("/sincronizacao")
    public Map<String, Integer> sync() {
        return Map.of("gruposSincronizados", service.sync());
    }
}
