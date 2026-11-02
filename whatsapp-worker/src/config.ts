import {resolve} from 'node:path';

export function readConfig(env = process.env) {
    const token = env.API_TOKEN;
    if (!token || token.length < 32 || token.startsWith('substitua-')) {
        throw new Error('API_TOKEN deve conter pelo menos 32 caracteres e não pode ser o exemplo.');
    }
    const port = Number(env.PORT ?? 3001);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT inválida.');
    const logLevel = env.LOG_LEVEL ?? 'info';
    if (!['silent', 'fatal', 'error', 'warn', 'info'].includes(logLevel)) throw new Error('LOG_LEVEL inválido.');
    return {token, port, host: env.HOST ?? '127.0.0.1', dataDir: resolve(env.DATA_DIR ?? './data'), logLevel};
}
