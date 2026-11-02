package com.whatsappscheduler.whatsapp_scheduler.autenticacao;

import java.security.Principal;
import java.util.Map;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;

    public AuthController(AuthenticationManager manager, SecurityContextRepository contexts) {
        this.manager = manager;
        this.contexts = contexts;
    }

    public record Login(@NotBlank @Size(max = 100) String login, @NotBlank @Size(max = 72) String senha) {
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public Map<String, String> login(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        var authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.login(), body.senha()));
        var old = request.getSession(false);
        if (old != null) old.invalidate();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return Map.of("login", authentication.getName());
    }

    @GetMapping("/me")
    public Map<String, String> me(Principal user) {
        return Map.of("login", user.getName());
    }
}
