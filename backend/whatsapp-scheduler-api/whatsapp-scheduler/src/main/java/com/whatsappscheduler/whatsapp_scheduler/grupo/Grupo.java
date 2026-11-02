package com.whatsappscheduler.whatsapp_scheduler.grupo;

import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "grupo_whatsapp")
public class Grupo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "whatsapp_id", nullable = false, unique = true, length = 100)
    public String whatsappId;

    @Column(nullable = false, length = 255)
    public String nome;

    @Column(name = "somente_administradores", nullable = false)
    public boolean somenteAdministradores;

    @Column(nullable = false)
    public boolean ativo;

    @Column(name = "sincronizado_em", nullable = false)
    public Instant sincronizadoEm;
}
