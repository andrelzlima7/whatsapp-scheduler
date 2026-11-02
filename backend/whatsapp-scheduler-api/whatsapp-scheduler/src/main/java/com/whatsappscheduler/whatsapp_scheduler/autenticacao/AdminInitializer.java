package com.whatsappscheduler.whatsapp_scheduler.autenticacao;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminInitializer implements ApplicationRunner {
    private final UsuarioRepository repository;
    private final PasswordEncoder encoder;
    private final String login;
    private final String password;

    public AdminInitializer(UsuarioRepository repository, PasswordEncoder encoder,
                            @Value("${app.admin.login}") String login, @Value("${app.admin.password}") String password) {
        this.repository = repository;
        this.encoder = encoder;
        this.login = login;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) return;
        if (login.isBlank() || login.length() > 100 || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Configure ADMIN_LOGIN e ADMIN_PASSWORD (mínimo 12 caracteres, máximo 72 bytes) para criar o administrador inicial.");
        }
        var user = new Usuario();
        user.login = login;
        user.senhaHash = encoder.encode(password);
        repository.save(user);
    }
}
