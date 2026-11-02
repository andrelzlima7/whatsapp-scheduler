package com.whatsappscheduler.whatsapp_scheduler.grupo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GrupoRepository extends JpaRepository<Grupo, Long> {
    Optional<Grupo> findByWhatsappId(String id);
}
