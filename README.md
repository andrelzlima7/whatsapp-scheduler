# WhatsApp Scheduler

Ferramenta self-hosted para agendar mensagens em grupos, com [serviço Node.js + Baileys](whatsapp-worker/README.md) e [API Spring Boot](backend/whatsapp-scheduler-api/whatsapp-scheduler/README.md). O painel React será implementado depois.

A API oferece recorrência diária, semanal e mensal, vigência, autenticação e histórico. Consulte seu README para aplicar os scripts SQL e executar localmente ou na mesma VPS com `compose.api.yaml`.

## Executar com Docker Compose

Crie `.env` na raiz com `API_TOKEN=` seguido de um token gerado por `openssl rand -hex 32`.

```sh
docker compose up -d --build
docker compose logs --tail=50 whatsapp-worker
```

A API fica em `127.0.0.1:3001`. O volume `whatsapp-data` preserva a sessão e os registros de execução. Use apenas uma instância do worker por volume/conta. Não use `docker compose down -v` se quiser preservar esses dados.

O projeto ainda não tem uma licença própria definida. Escolha e adicione uma licença antes da publicação como open source.
