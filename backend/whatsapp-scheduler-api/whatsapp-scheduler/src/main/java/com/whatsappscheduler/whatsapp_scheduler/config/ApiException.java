package com.whatsappscheduler.whatsapp_scheduler.config;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound() {
        return new ApiException(404, "NAO_ENCONTRADO", "Registro não encontrado.");
    }

    public static ApiException invalid(String message) {
        return new ApiException(400, "DADOS_INVALIDOS", message);
    }
}
