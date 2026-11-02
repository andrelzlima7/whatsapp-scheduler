# Scripts PostgreSQL

## Preparação

O banco local `whatsapp_scheduler` já existe. Não execute `00_criar_banco.sql` nele.
Para uma instalação nova, execute esse arquivo conectado ao banco administrativo
`postgres`, fora de uma transação. Ele não contém comandos de exclusão nem senha.

```sh
psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -W -v ON_ERROR_STOP=1 -f sql/00_criar_banco.sql
```

## Estrutura da aplicação

Tabelas, índices e restrições estão em
`src/main/resources/db/migration/V1__estrutura_inicial.sql`, fonte única da estrutura.
Para executar pelo psql, na pasta da API:

```sh
psql -h 127.0.0.1 -p 5432 -U postgres -d whatsapp_scheduler -W -v ON_ERROR_STOP=1 -f sql/01_estrutura.sql
```

O arquivo `01_estrutura.sql` inclui a migração dentro de uma transação. No pgAdmin,
abra o arquivo `V1__estrutura_inicial.sql` e execute seu conteúdo dentro de uma
transação. Execute somente uma vez; o script não usa `DROP` nem mascara diferenças
de estrutura com `IF NOT EXISTS`.

O modo padrão é manual, com `FLYWAY_ENABLED=false`. Hibernate usa `validate` e não
cria/altera tabelas. Em banco novo e vazio, é possível optar por
`FLYWAY_ENABLED=true` e deixar o Flyway aplicar as migrações, sem executar SQL
manualmente. Não ative esse modo em um banco já preparado manualmente sem antes
registrar sua versão com o procedimento de baseline do Flyway.

O usuário administrador é criado pela aplicação na primeira inicialização, usando
`ADMIN_LOGIN` e `ADMIN_PASSWORD`; a senha é armazenada como hash BCrypt.

As credenciais e o endereço do banco serão configuráveis por ambiente. A senha
local informada não deve ser incorporada aos scripts SQL de implantação.
