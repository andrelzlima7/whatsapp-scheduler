import {createHash} from 'node:crypto';
import {mkdir, readFile, open, rename} from 'node:fs/promises';
import {join} from 'node:path';
import {type Gateway, ServiceError} from './contracts.js';

interface RecordEntry {
    fingerprint: string;
    status: 'uncertain' | 'sent' | 'expired';
    messageId?: string
}

export class Dispatcher {
    private busy = false;
    private directory: string;

    constructor(private gateway: Gateway, dataDir: string) {
        this.directory = join(dataDir, 'executions');
    }

    private async save(path: string, record: RecordEntry) {
        const file = await open(`${path}.tmp`, 'w', 0o600);
        try {
            await file.writeFile(JSON.stringify(record));
            await file.sync();
        } finally {
            await file.close();
        }
        await rename(`${path}.tmp`, path);
        const directory = await open(this.directory, 'r');
        try {
            await directory.sync();
        } finally {
            await directory.close();
        }
    }

    async send(executionId: string, groupId: string, text: string, sendBefore?: string) {
        if (this.busy) throw new ServiceError(429, 'BUSY', 'Há uma operação em andamento; tente novamente mais tarde.');
        this.busy = true;
        try {
            await mkdir(this.directory, {recursive: true, mode: 0o700});
            const key = createHash('sha256').update(executionId).digest('hex');
            const path = join(this.directory, `${key}.json`);
            const fingerprint = createHash('sha256').update(JSON.stringify([groupId, text])).digest('hex');
            let existing: RecordEntry | undefined;
            try {
                existing = JSON.parse(await readFile(path, 'utf8')) as RecordEntry;
            } catch (error) {
                if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
            }
            if (existing) {
                if (existing.fingerprint !== fingerprint) throw new ServiceError(409, 'EXECUTION_CONFLICT', 'Identificador já utilizado com outro conteúdo.');
                if (existing.status === 'sent') return {
                    executionId,
                    status: 'sent' as const,
                    messageId: existing.messageId,
                    replayed: true
                };
                if (existing.status === 'expired') throw new ServiceError(410, 'SEND_EXPIRED', 'Prazo de envio encerrado.');
                throw new ServiceError(409, 'SEND_UNCERTAIN', 'Resultado incerto. Não reenvie automaticamente nem use outro identificador.');
            }
            const expired = () => sendBefore !== undefined && Date.now() > Date.parse(sendBefore);
            if (expired()) throw new ServiceError(410, 'SEND_EXPIRED', 'Prazo de envio encerrado.');
            await this.gateway.validateGroup(groupId);
            if (expired()) throw new ServiceError(410, 'SEND_EXPIRED', 'Prazo de envio encerrado.');
            // Persist before contacting WhatsApp: interruption or timeout must never cause an automatic resend.
            await this.save(path, {fingerprint, status: 'uncertain'});
            if (expired()) {
                await this.save(path, {fingerprint, status: 'expired'});
                throw new ServiceError(410, 'SEND_EXPIRED', 'Prazo de envio encerrado.');
            }
            try {
                const messageId = await this.gateway.send(groupId, text);
                await this.save(path, {fingerprint, status: 'sent', messageId});
                return {executionId, status: 'sent' as const, messageId, replayed: false};
            } catch {
                throw new ServiceError(409, 'SEND_UNCERTAIN', 'Não foi possível confirmar o envio. Confira o grupo antes de qualquer nova tentativa.');
            }
        } finally {
            this.busy = false;
        }
    }
}
