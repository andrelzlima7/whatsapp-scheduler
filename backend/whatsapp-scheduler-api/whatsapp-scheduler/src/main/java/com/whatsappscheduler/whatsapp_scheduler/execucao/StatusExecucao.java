package com.whatsappscheduler.whatsapp_scheduler.execucao;

public enum StatusExecucao {
    PENDENTE,
    EM_EXECUCAO,
    AGUARDANDO_NOVA_TENTATIVA,
    ENVIADO,
    FALHOU,
    INCERTO,
    EXPIRADO,
    CANCELADO
}
