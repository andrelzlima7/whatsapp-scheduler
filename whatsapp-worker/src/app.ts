import {timingSafeEqual, createHash} from 'node:crypto';
import Fastify, {LogController} from 'fastify';
import QRCode from 'qrcode';
import {type Gateway, ServiceError} from './contracts.js';
import {Dispatcher} from './dispatch.js';

export function buildApp(options: { token: string; gateway: Gateway; dispatcher: Dispatcher; logLevel?: string }) {
    const app = Fastify({
        bodyLimit: 32 * 1024,
        logController: new LogController({disableRequestLogging: true}),
        logger: {level: options.logLevel ?? 'info', redact: ['req.headers.authorization']},
        requestTimeout: 15_000,
        ajv: {customOptions: {removeAdditional: false, coerceTypes: false}},
    });
    const expected = createHash('sha256').update(`Bearer ${options.token}`).digest();
    app.addHook('onRequest', async (request, reply) => {
        reply.header('Cache-Control', 'no-store');
        const actual = createHash('sha256').update(request.headers.authorization ?? '').digest();
        if (!timingSafeEqual(expected, actual)) {
            return reply.code(401).send({code: 'UNAUTHORIZED', message: 'Token inválido ou ausente.'});
        }
    });
    app.setErrorHandler((error, request, reply) => {
        if (error instanceof ServiceError) {
            request.log.warn({code: error.code, requestId: request.id}, 'Operação recusada');
            return reply.code(error.statusCode).send({code: error.code, message: error.message});
        }
        const detail = error as { validation?: unknown; statusCode?: number };
        if (detail.validation) return reply.code(400).send({
            code: 'INVALID_REQUEST',
            message: 'Dados da requisição inválidos.'
        });
        if (detail.statusCode && detail.statusCode < 500) return reply.code(detail.statusCode).send({
            code: 'INVALID_REQUEST',
            message: 'Requisição inválida.'
        });
        request.log.error({requestId: request.id}, 'Erro interno na operação');
        return reply.code(500).send({code: 'INTERNAL_ERROR', message: 'Erro interno.'});
    });
    app.get('/health', async () => ({status: 'ok'}));
    app.get('/connection', async () => options.gateway.connection());
    app.get('/connection/qr', async () => {
        const qr = options.gateway.qr();
        if (!qr) throw new ServiceError(409, 'QR_UNAVAILABLE', 'Nenhum QR Code disponível no momento.');
        return {qrImage: await QRCode.toDataURL(qr, {width: 320})};
    });
    app.get('/groups', async () => ({groups: await options.gateway.groups()}));
    app.post<{ Body: { executionId: string; groupId: string; text: string; sendBefore?: string } }>('/messages', {
        schema: {
            body: {
                type: 'object', additionalProperties: false, required: ['executionId', 'groupId', 'text'],
                properties: {
                    executionId: {type: 'string', minLength: 1, maxLength: 128, pattern: '^[A-Za-z0-9_-]+$'},
                    groupId: {type: 'string', pattern: '^[0-9]+(-[0-9]+)?@g\\.us$'},
                    text: {type: 'string', minLength: 1, maxLength: 10000, pattern: '\\S'},
                    sendBefore: {type: 'string', format: 'date-time'},
                },
            }
        },
    }, async (request) => {
        const {executionId, groupId, text, sendBefore} = request.body;
        const result = await options.dispatcher.send(executionId, groupId, text, sendBefore);
        request.log.info({executionId, replayed: result.replayed}, 'Envio confirmado pelo serviço');
        return result;
    });
    return app;
}
