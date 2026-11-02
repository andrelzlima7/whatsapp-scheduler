# Serviço WhatsApp

Node.js 22.13+ com TypeScript, Fastify e Baileys. Uma conta e uma instância por instalação. Sem banco externo, histórico de conversas, agendamentos ou armazenamento de mensagens recebidas.

## Desenvolvimento

```sh
cd whatsapp-worker
cp .env.example .env
openssl rand -hex 32
# Coloque o token gerado em API_TOKEN no arquivo .env.
npm ci
npm run dev
```

```sh
npm run build
npm test
npm start
```

`HOST` é `127.0.0.1` por padrão, `PORT` é `3001` e `DATA_DIR` é `./data`, relativo ao diretório de execução. `LOG_LEVEL` aceita `silent`, `fatal`, `error`, `warn` e `info`. Todos os endpoints exigem `Authorization: Bearer <API_TOKEN>`, inclusive o health check. Não exponha a API diretamente à internet; a futura API Java fará a intermediação.

## API

Nos exemplos, exporte `API_TOKEN` no terminal com o mesmo valor configurado no serviço. O arquivo `.env` é carregado pelo processo Node, não pelo shell.

```sh
curl -H "Authorization: Bearer $API_TOKEN" http://127.0.0.1:3001/connection
curl -H "Authorization: Bearer $API_TOKEN" http://127.0.0.1:3001/connection/qr
curl -H "Authorization: Bearer $API_TOKEN" http://127.0.0.1:3001/groups
```

| Endpoint | Resposta |
| --- | --- |
| `GET /health` | `{ "status": "ok" }`: processo HTTP disponível, independente do WhatsApp |
| `GET /connection` | `{ "status": "connected" }` |
| `GET /connection/qr` | `{ "qrImage": "data:image/png;base64,..." }` |
| `GET /groups` | `{ "groups": [{ "id": "123@g.us", "name": "Grupo", "announcementOnly": false }] }` |
| `POST /messages` | Resultado do envio abaixo |

Abra a data URL do QR em uma imagem no navegador e use WhatsApp → Aparelhos conectados. O QR muda e expira: consulte novamente caso necessário. O QR e as credenciais não são escritos nos logs.

```sh
curl -X POST http://127.0.0.1:3001/messages \
  -H "Authorization: Bearer $API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"executionId":"execucao-001","groupId":"123@g.us","text":"Olá, grupo!"}'
```

```json
{"executionId":"execucao-001","status":"sent","messageId":"ID_WHATSAPP","replayed":false}
```

Use um grupo retornado por `/groups`. `executionId` aceita letras ASCII, números, `_` e `-`, até 128 caracteres; texto deve ter de 1 a 10.000 caracteres e não pode conter somente espaços. O corpo tem limite de 32 KiB. Envios em grupos restritos exigem que a conta seja administradora.

`sent` indica que a chamada de envio retornou um identificador; não confirma entrega ou leitura pelos participantes.

A API Spring envia também `sendBefore`, um instante ISO 8601 com fuso, opcional para clientes manuais. O worker verifica o prazo antes e depois de consultar o grupo e imediatamente antes do envio. Após o prazo, retorna `410 SEND_EXPIRED` sem iniciar novo envio. Uma execução já concluída continua retornando seu resultado salvo. O prazo limita o início da chamada de envio; a entrega pelo WhatsApp pode ocorrer depois.

## Falhas e repetições

Erros retornam `{ "code": "...", "message": "..." }`.

| HTTP / code | Como tratar |
| --- | --- |
| `400 INVALID_REQUEST` | Corrigir os dados |
| `401 UNAUTHORIZED` | Corrigir o token |
| `403 NOT_GROUP_MEMBER / GROUP_RESTRICTED` | Verificar participação/permissão |
| `409 QR_UNAVAILABLE` | Consultar o estado da conexão |
| `409 EXECUTION_CONFLICT` | O ID foi usado com outro conteúdo |
| `409 SEND_UNCERTAIN` | Conferir manualmente o grupo; não reenviar automaticamente |
| `429 BUSY` | Repetir mais tarde com o mesmo ID |
| `410 SEND_EXPIRED` | Prazo encerrado; não repetir |
| `503 NOT_CONNECTED / GROUP_UNAVAILABLE / GROUPS_UNAVAILABLE` | Aguardar conexão e repetir com o mesmo ID |

Somente um envio é processado por vez; chamadas concorrentes recebem `429`, sem fila em memória. Cada execução é registrada em arquivo antes do envio, contendo apenas hash do conteúdo, situação e identificador da mensagem. Repetir um ID concluído com o mesmo conteúdo retorna o resultado salvo, sem outro envio. Um resultado incerto continua bloqueado após reinícios. Se a conexão HTTP se perder, consulte novamente usando o mesmo ID e conteúdo.

O limite de espera do envio é 30 segundos. Timeout ou erro após o início do envio são considerados incertos. Uma queda entre registrar a intenção e enviar também pode produzir um resultado incerto sem mensagem enviada: escolhemos evitar duplicações. Isso não é garantia de entrega exatamente uma vez.

Não apague os registros de `data/executions` para liberar tentativas: isso elimina a proteção contra repetição. Eles são pequenos e não são carregados em memória; acompanhe o uso de disco. A proteção pressupõe uma única instância e o mesmo volume persistente, inclusive após restaurações.

## Operação em VPS

O serviço mantém um socket, desabilita sincronização de histórico e prévias de links e reconecta com espera progressiva até aproximadamente um minuto. A biblioteca Baileys usa logger silencioso para não vazar mensagens ou credenciais; o serviço registra apenas estados e IDs das execuções. No Compose, logs têm rotação de 5 MB com três arquivos.

Estados: `connecting`, `awaiting_qr`, `connected`, `disconnected`, `logged_out` e `blocked`. Após logout ou falha permanente, não há loop de reconexão. Para parear novamente: pare o serviço, mova apenas `data/auth` para um backup protegido e reinicie. No Docker, esse diretório está no volume em `/app/data/auth`. Preserve `executions`. Em `blocked`, investigue primeiro se há outra instância usando a conta ou falha de disco/permissão.

Se o celular recusar o pareamento, obtenha um QR novo em `/connection/qr` e escaneie imediatamente: a imagem copiada não se atualiza e expira. Consulte `/connection` após a tentativa; `lastDisconnectCode`, quando disponível, contém o último código de desconexão e também aparece nos logs como `disconnectCode`. Uma conexão bem-sucedida limpa esse código. O código sozinho não prova a causa. Por exemplo, `515` pode indicar o reinício normal solicitado pelo WhatsApp durante o pareamento; `401` exige novo pareamento. Compare também com um pareamento no WhatsApp Web oficial antes de atribuir o problema à biblioteca.

As credenciais usam o armazenamento multifile do Baileys, adequado a este protótipo de uma conta e baixo volume. Não escale réplicas compartilhando o diretório. Proteja os backups: os arquivos permitem acesso à sessão. Encerramento por SIGTERM/SIGINT aguarda requisições em andamento e salva credenciais; o Compose concede 40 segundos.

O consumo real precisa ser medido com uma conta conectada antes de dimensionar a VPS. Os testes automatizados simulam o WhatsApp e não fazem envios reais.

## Verificação manual

1. Iniciar e obter QR autenticado, sem QR ou credenciais nos logs.
2. Parear uma conta e verificar `connected`.
3. Listar grupos e enviar para um grupo de teste autorizado.
4. Repetir o mesmo `executionId` e confirmar que nenhuma outra mensagem aparece.
5. Reiniciar e confirmar recuperação da sessão e da proteção contra repetição.
6. Interromper a rede e observar a reconexão progressiva.

## Dependência

Baileys está fixado em `7.0.0-rc14`, versão de pré-lançamento disponível no início da implementação. Atualizações exigem revisar migrações e testar o pareamento/envio. A integração não é oficial do WhatsApp. Referências: [repositório oficial](https://github.com/WhiskeySockets/Baileys) e [exemplo oficial de conexão](https://github.com/WhiskeySockets/Baileys/blob/master/Example/example.ts).
