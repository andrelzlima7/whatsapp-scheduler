package com.whatsappscheduler.whatsapp_scheduler.whatsapp;

public class WorkerException extends RuntimeException {
    public final String code;
    public final int status;

    public WorkerException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public boolean retryable() {
        return code.equals("TRANSPORT_ERROR") || code.equals("BUSY") || code.equals("NOT_CONNECTED") || code.equals("GROUP_UNAVAILABLE");
    }

    public boolean uncertain() {
        return code.equals("TRANSPORT_ERROR") || code.equals("SEND_UNCERTAIN") || code.equals("WORKER_ERROR");
    }
}
