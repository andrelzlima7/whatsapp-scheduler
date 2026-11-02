package com.whatsappscheduler.whatsapp_scheduler.autenticacao;

import jakarta.persistence.*;

@Entity
@Table(name = "usuario")
public class Usuario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true, length = 100)
    public String login;
    @Column(name = "senha_hash", nullable = false, length = 100)
    public String senhaHash;
}
