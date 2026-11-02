package com.whatsappscheduler.whatsapp_scheduler.whatsapp;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppController {
    private final WorkerClient worker;

    public WhatsAppController(WorkerClient worker) {
        this.worker = worker;
    }

    @GetMapping("/connection")
    public WorkerClient.Connection connection() {
        return worker.connection();
    }

    @GetMapping("/qr")
    public WorkerClient.Qr qr() {
        return worker.qr();
    }
}
