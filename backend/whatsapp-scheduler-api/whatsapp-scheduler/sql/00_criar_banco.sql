-- Execute conectado ao banco administrativo postgres, fora de uma transacao.
-- Execute apenas se whatsapp_scheduler ainda nao existir.
-- Se necessario, ajuste o proprietario para o usuario da implantacao.
CREATE DATABASE whatsapp_scheduler
    WITH OWNER = postgres
         ENCODING = 'UTF8'
         TEMPLATE = template0;
