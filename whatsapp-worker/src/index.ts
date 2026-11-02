import {buildApp} from './app.js';
import {readConfig} from './config.js';
import {Dispatcher} from './dispatch.js';
import {WhatsAppGateway} from './whatsapp.js';

process.umask(0o077);
const config = readConfig();
const gateway = new WhatsAppGateway(config.dataDir, (status, disconnectCode) => app.log.info({
    status,
    disconnectCode
}, 'Estado da conexão WhatsApp'));
const dispatcher = new Dispatcher(gateway, config.dataDir);
const app = buildApp({token: config.token, gateway, dispatcher, logLevel: config.logLevel});
app.addHook('onClose', async () => gateway.stop());
let shuttingDown = false;

async function shutdown() {
    if (shuttingDown) return;
    shuttingDown = true;
    const deadline = setTimeout(() => process.exit(1), 30_000);
    deadline.unref();
    await app.close();
    clearTimeout(deadline);
}

process.on('SIGTERM', () => {
    void shutdown();
});
process.on('SIGINT', () => {
    void shutdown();
});
try {
    await app.listen({host: config.host, port: config.port});
    await gateway.start();
} catch {
    app.log.fatal('Falha ao iniciar o serviço');
    await app.close();
    process.exitCode = 1;
}
