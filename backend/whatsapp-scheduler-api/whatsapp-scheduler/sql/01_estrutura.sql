-- Para psql. Executar conectado ao banco whatsapp_scheduler.
-- No pgAdmin, executar diretamente o arquivo V1__estrutura_inicial.sql.
\set ON_ERROR_STOP on
BEGIN;
\ir ../src/main/resources/db/migration/V1__estrutura_inicial.sql
COMMIT;
