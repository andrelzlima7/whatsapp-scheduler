# API WhatsApp Scheduler

Java 21, Spring Boot 4.1.2 e PostgreSQL 16. Uma instância da API, um administrador
e uma conta WhatsApp por instalação. O Node/Baileys deve estar em execução.

## Preparar o banco local

O banco `whatsapp_scheduler` já existe no ambiente validado. A estrutura ainda
precisa ser aplicada pelo usuário. Na pasta desta API:

```sh
psql -h 127.0.0.1 -p 5432 -U postgres -d whatsapp_scheduler -W -v ON_ERROR_STOP=1 -f sql/01_estrutura.sql
```

Informe a senha local `postgres` quando solicitado. No pgAdmin, abra e execute
`src/main/resources/db/migration/V1__estrutura_inicial.sql` dentro do banco.
Execute a estrutura uma única vez. Os arquivos não apagam tabelas existentes.
Para uma instalação com banco ainda inexistente, veja [sql/README.md](sql/README.md).

O padrão é execução manual de SQL, com `FLYWAY_ENABLED=false` e Hibernate em
`validate`. O Flyway permanece disponível para instalações novas que prefiram
migração automática. Não misture os dois modos no mesmo banco sem registrar
corretamente a versão no Flyway.

## Executar localmente

```sh
cp .env.example .env
```

Preencha `DB_PASSWORD` com a senha do PostgreSQL local, `ADMIN_PASSWORD` (mínimo
12 caracteres, máximo 72 bytes) e `WHATSAPP_WORKER_TOKEN` (o mesmo `API_TOKEN` do
Node). O endereço padrão é `127.0.0.1:5432`, usuário `postgres`. A senha do banco
é obrigatória por variável de ambiente; não há senha padrão na aplicação.

```sh
set -a
source .env
set +a
./mvnw spring-boot:run
```

O Spring não carrega `.env` automaticamente. Na IDE, configure as mesmas
variáveis na execução. A API fica em `http://127.0.0.1:8080`. O administrador é
criado somente se a tabela `usuario` estiver vazia, com senha BCrypt. Alterar a
variável depois não muda a senha existente. Sessões ficam em memória e expiram
após 30 minutos de inatividade ou reinício da API.

## Autenticação e CSRF

Os exemplos usam `curl` e `jq`. Preserve o cookie e envie `X-CSRF-TOKEN` em
POST/PUT, incluindo login e logout. O login renova a sessão; obtenha outro token
CSRF depois de autenticar.

```sh
BASE=http://127.0.0.1:8080/api
CSRF=$(curl -fsS -c /tmp/scheduler-cookies "$BASE/auth/csrf" | jq -r .token)
curl -fsS -b /tmp/scheduler-cookies -c /tmp/scheduler-cookies \
  -H "X-CSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' \
  -d '{"login":"admin","senha":"SUA_SENHA"}' "$BASE/auth/login"
CSRF=$(curl -fsS -b /tmp/scheduler-cookies -c /tmp/scheduler-cookies "$BASE/auth/csrf" | jq -r .token)
curl -fsS -b /tmp/scheduler-cookies "$BASE/auth/me"
```

## Grupos e WhatsApp

```sh
curl -fsS -b /tmp/scheduler-cookies "$BASE/whatsapp/connection"
curl -fsS -b /tmp/scheduler-cookies "$BASE/whatsapp/qr"
curl -fsS -X POST -b /tmp/scheduler-cookies -H "X-CSRF-TOKEN: $CSRF" "$BASE/grupos/sincronizacao"
curl -fsS -b /tmp/scheduler-cookies "$BASE/grupos?pagina=0&tamanho=20"
```

Sincronização é explícita, sem consultar o WhatsApp em cada ciclo. Grupos que
desaparecerem são marcados como inativos, preservando referências e histórico.
Agendamentos existentes continuam sujeitos à validação real do worker no envio.

## Agendamentos

```sh
curl -fsS -b /tmp/scheduler-cookies -H "X-CSRF-TOKEN: $CSRF" \
  -H 'Content-Type: application/json' "$BASE/agendamentos" \
  -d '{
    "grupoId":1,
    "texto":"Lembrete da programação!",
    "frequencia":"SEMANAL",
    "diaSemana":"SEXTA",
    "horario":"18:00",
    "inicioVigencia":"2026-11-01",
    "fimVigencia":"2026-11-30",
    "fusoHorario":"America/Sao_Paulo"
  }'
```

Use o ID interno retornado por `/grupos`, não o identificador `@g.us`.
Datas do exemplo devem ser ajustadas para haver uma ocorrência futura.

| Frequência | Campos específicos |
| --- | --- |
| `DIARIA` | Não informar dia da semana, dia do mês ou último dia |
| `SEMANAL` | `diaSemana`: `SEGUNDA`, `TERCA`, `QUARTA`, `QUINTA`, `SEXTA`, `SABADO` ou `DOMINGO` |
| `MENSAL` | `diaMes` de 1 a 31 **ou** `ultimoDiaMes: true`, sem `diaMes` |

`horario` tem precisão de minutos (`HH:mm`). Início obrigatório e fim opcional,
ambos inclusivos. Fuso padrão `America/Sao_Paulo`. Dia 31 pula meses que não
possuem esse dia; último dia calcula 28/29/30/31 corretamente. Sem fim, a regra
continua até ser pausada/cancelada. Horários de verão inexistentes avançam pela
lacuna; horários ambíguos usam o primeiro offset, sem duplicar a ocorrência.

Texto: obrigatório, até 10.000 caracteres e corpo total do envio até 32 KiB.
Cadastro e edição calculam a próxima ocorrência a partir de agora, sem enviar
retroativamente. Um agendamento sem próxima ocorrência dentro da vigência é
recusado.

| Método | Endpoint (prefixo `/api`) |
| --- | --- |
| GET | `/auth/csrf`, `/auth/me` |
| POST | `/auth/login`, `/auth/logout` |
| GET | `/whatsapp/connection`, `/whatsapp/qr` |
| GET | `/grupos` |
| POST | `/grupos/sincronizacao` |
| POST / GET | `/agendamentos` |
| GET / PUT | `/agendamentos/{id}` |
| POST | `/agendamentos/{id}/pausa` |
| POST | `/agendamentos/{id}/reativacao` |
| POST | `/agendamentos/{id}/cancelamento` |
| GET | `/agendamentos/{id}/execucoes` |

Listas usam `pagina` (a partir de zero) e `tamanho` (1–100). Agendamentos aceitam
filtros `status=ATIVO|PAUSADO|CANCELADO` e `grupoId`. Resposta paginada:
`content`, `number`, `size`, `totalElements`, `totalPages`.

PUT recebe os mesmos campos da criação. Edição é bloqueada enquanto há ocorrência
pendente/em processamento. Pause para cancelar as pendentes antes de editar;
uma execução já iniciada precisa terminar. Reativação segue a próxima data futura,
sem recuperar o período pausado. Cancelamento é definitivo. Regra ativa com
`proximaExecucaoEm: null` está esgotada pela vigência.

## Execução e falhas

Uma thread consulta o banco com intervalo de 30 segundos **após cada ciclo**,
configurável por `SCHEDULER_INTERVAL` em milissegundos. Cada ciclo materializa até
20 regras e processa até 20 execuções, sequencialmente. Consultas usam índices e
reservas transacionais curtas; HTTP fica fora da transação. O tempo do ciclo pode
acrescentar atraso quando houver envios lentos.

Cada ocorrência mantém grupo/texto congelados e um UUID persistido, usado como
`executionId` no Node. Uma restrição única impede duas execuções da mesma regra
no mesmo instante. O UUID e o conteúdo são preservados em toda repetição.

- Até 30 minutos de atraso são aceitos; fim da vigência sempre prevalece.
- Ocorrências antigas expiram. Após indisponibilidade longa, registra-se a primeira
  ocorrência vencida e pula-se o restante do acúmulo, sem criar milhares de linhas.
- O Node recebe `sendBefore` e verifica o limite imediatamente antes de iniciar o
  envio. Atualize/reinicie o worker junto com esta API.
- Falhas seguras (desconexão, grupo temporariamente indisponível, serviço ocupado)
  e falhas de transporte admitem até cinco tentativas, com espera progressiva.
- `SEND_UNCERTAIN` e respostas inesperadas encerram em `INCERTO`, sem repetição
  automática. Erros de permissão encerram em `FALHOU`.
- Uma execução interrompida é recuperada após uma reserva de 120 segundos, com
  o mesmo UUID. Se o prazo já acabou e o resultado não é conhecido, fica `INCERTO`.
- Pausa/cancelamento bloqueiam novos envios. Uma chamada já iniciada pode concluir.
  Pendentes com resultado indeterminado passam a `INCERTO` ao serem canceladas.
- `ENVIADO` é confirmação do serviço, não confirmação de entrega/leitura. Mesmo
  um envio iniciado no prazo pode ser entregue depois pelo WhatsApp.

Estados no histórico: `PENDENTE`, `EM_EXECUCAO`, `AGUARDANDO_NOVA_TENTATIVA`,
`ENVIADO`, `FALHOU`, `INCERTO`, `EXPIRADO` e `CANCELADO`.

## Testes

```sh
./mvnw test
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/postgres ./mvnw test
```

O primeiro comando executa testes unitários e HTTP do worker simulado. O segundo
inclui integração PostgreSQL, autenticação, CRUD e execução. Usa schema aleatório
temporário e o remove no final; não utiliza tabelas do banco da aplicação.
Credenciais de teste padrão: `postgres`/`postgres`, sobrescritas por
`TEST_POSTGRES_USERNAME` e `TEST_POSTGRES_PASSWORD`. Nenhum teste envia WhatsApp real.

## Implantação na mesma VPS

Na raiz do repositório, crie `.env` com `API_TOKEN`, `DB_USERNAME`, `DB_PASSWORD`,
`ADMIN_LOGIN`, `ADMIN_PASSWORD` e `SESSION_COOKIE_SECURE=true`. O token deve ser o
mesmo utilizado pelo worker. A composição adicional cria um PostgreSQL próprio,
separado do banco local da máquina, sem publicar sua porta.

```sh
docker compose -f compose.yaml -f compose.api.yaml up -d --build postgres whatsapp-worker
docker compose -f compose.yaml -f compose.api.yaml exec -T postgres \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 --single-transaction' \
  < backend/whatsapp-scheduler-api/whatsapp-scheduler/src/main/resources/db/migration/V1__estrutura_inicial.sql
docker compose -f compose.yaml -f compose.api.yaml up -d --build api
```

Execute o SQL apenas na primeira instalação. Publique o painel/API com proxy HTTPS
na mesma origem; configure `SERVER_FORWARD_HEADERS_STRATEGY=framework` na API
quando o proxy enviar os cabeçalhos de encaminhamento. Em testes Docker por HTTP
local, use `SESSION_COOKIE_SECURE=false` para o navegador aceitar o cookie.

A imagem executa com usuário sem privilégios. A API tem limite inicial de 512 MiB,
heap proporcional e GC serial; pool PostgreSQL de quatro conexões e logs com
rotação. Esse limite é uma configuração inicial, não uma medição com carga real.
Faça backup tanto do volume PostgreSQL quanto do volume de sessão/execuções do
Node. Não execute réplicas ou dois agendadores para a mesma instalação.

Imagens, múltiplos usuários/contas e interface React ficam fora desta entrega.
