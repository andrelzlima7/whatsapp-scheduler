package com.whatsappscheduler.whatsapp_scheduler.config;

import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).headers(e.getHeaders()).body(Map.of(
                "code", "METODO_NAO_PERMITIDO", "message", "Método HTTP não permitido para esta rota. Confira o cabeçalho Allow."));
    }

    @ExceptionHandler(com.whatsappscheduler.whatsapp_scheduler.whatsapp.WorkerException.class)
    ResponseEntity<?> worker(com.whatsappscheduler.whatsapp_scheduler.whatsapp.WorkerException e) {
        return response(e.status, e.code, "O serviço WhatsApp não concluiu a operação.");
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) {
        return response(e.status, e.code, e.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<?> auth() {
        return response(401, "CREDENCIAIS_INVALIDAS", "Login ou senha inválidos.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var fields = new java.util.TreeMap<String, String>();
        e.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(),
                error.getDefaultMessage() == null ? "Valor inválido." : error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of("code", "DADOS_INVALIDOS", "message", "Confira os campos indicados.", "fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable() {
        return response(400, "DADOS_INVALIDOS", "JSON inválido: confira os nomes, tipos e formatos dos campos (datas yyyy-MM-dd e horário HH:mm).");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> parameter(MethodArgumentTypeMismatchException e) {
        return response(400, "DADOS_INVALIDOS", "Parâmetro inválido: " + e.getName() + ".");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() {
        return response(409, "CONFLITO", "A operação conflita com os dados existentes.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        // Não incluir mensagem/stack trace: respostas do worker e SQL podem conter dados privados.
        LoggerFactory.getLogger(ApiErrors.class).error("Falha interna: {}", e.getClass().getSimpleName());
        return response(500, "ERRO_INTERNO", "Não foi possível concluir a operação.");
    }

    private ResponseEntity<?> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
