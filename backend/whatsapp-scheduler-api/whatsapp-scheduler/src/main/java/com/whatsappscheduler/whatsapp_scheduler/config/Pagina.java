package com.whatsappscheduler.whatsapp_scheduler.config;

import java.util.List;

import org.springframework.data.domain.Page;

public record Pagina<T>(List<T> content, int number, int size, long totalElements, int totalPages) {
    public static <T> Pagina<T> of(Page<T> page) {
        return new Pagina<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
