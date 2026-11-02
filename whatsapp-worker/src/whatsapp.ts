import makeWASocket, {
    DisconnectReason,
    useMultiFileAuthState,
    type WASocket,
    jidNormalizedUser
} from '@whiskeysockets/baileys';
import {mkdir} from 'node:fs/promises';
import {join} from 'node:path';
import pino from 'pino';
import {type ConnectionStatus, type Gateway, ServiceError} from './contracts.js';

export class WhatsAppGateway implements Gateway {
    private socket?: WASocket;
    private status: ConnectionStatus = 'disconnected';
    private currentQr?: string;
    private timer?: NodeJS.Timeout;
    private attempt = 0;
    private lastDisconnectCode?: number;
    private stopping = false;
    private saves: Promise<void> = Promise.resolve();
    private logger = pino({level: 'silent'});

    constructor(private dataDir: string, private report: (status: ConnectionStatus, disconnectCode?: number) => void) {
    }

    connection() {
        return {status: this.status, lastDisconnectCode: this.lastDisconnectCode};
    }

    qr() {
        return this.currentQr;
    }

    private setStatus(status: ConnectionStatus) {
        this.status = status;
        this.report(status, this.lastDisconnectCode);
    }

    async start() {
        if (this.stopping) return;
        this.setStatus('connecting');
        try {
            await this.saves;
            const authDir = join(this.dataDir, 'auth');
            await mkdir(authDir, {recursive: true, mode: 0o700});
            const {state, saveCreds} = await useMultiFileAuthState(authDir);
            if (this.stopping) return;
            const socket = makeWASocket({
                auth: state,
                logger: this.logger,
                syncFullHistory: false,
                shouldSyncHistoryMessage: () => false,
                markOnlineOnConnect: false,
                generateHighQualityLinkPreview: false,
                connectTimeoutMs: 20_000,
                defaultQueryTimeoutMs: 20_000,
            });
            this.socket = socket;
            socket.ev.on('creds.update', () => {
                this.saves = this.saves.then(saveCreds).catch(() => {
                    this.setStatus('blocked');
                    socket.end(new Error('Credential persistence failed'));
                });
            });
            socket.ev.on('connection.update', ({connection, qr, lastDisconnect}) => {
                if (this.stopping || this.socket !== socket) return;
                if (qr) {
                    this.currentQr = qr;
                    this.setStatus('awaiting_qr');
                }
                if (connection === 'open') {
                    this.currentQr = undefined;
                    this.attempt = 0;
                    this.lastDisconnectCode = undefined;
                    this.setStatus('connected');
                }
                if (connection === 'close') {
                    this.currentQr = undefined;
                    this.socket = undefined;
                    const code = (lastDisconnect?.error as {
                        output?: { statusCode?: number }
                    } | undefined)?.output?.statusCode;
                    this.lastDisconnectCode = typeof code === 'number' && Number.isFinite(code) ? code : undefined;
                    if (this.status === 'blocked') return;
                    if (code === DisconnectReason.loggedOut) {
                        this.setStatus('logged_out');
                        return;
                    }
                    if ([DisconnectReason.badSession, DisconnectReason.connectionReplaced, DisconnectReason.multideviceMismatch, DisconnectReason.forbidden].includes(code ?? 0)) {
                        this.setStatus('blocked');
                        return;
                    }
                    this.setStatus('disconnected');
                    this.scheduleReconnect(code === DisconnectReason.restartRequired);
                }
            });
        } catch {
            this.setStatus('disconnected');
            this.scheduleReconnect();
        }
    }

    private scheduleReconnect(immediate = false) {
        if (this.stopping || this.timer) return;
        const delay = immediate ? 1000 : Math.min(60_000, 2000 * 2 ** Math.min(this.attempt++, 5)) + Math.floor(Math.random() * 1000);
        this.timer = setTimeout(() => {
            this.timer = undefined;
            void this.start();
        }, delay);
    }

    private connected() {
        if (this.status !== 'connected' || !this.socket) throw new ServiceError(503, 'NOT_CONNECTED', 'WhatsApp desconectado.');
        return this.socket;
    }

    async groups() {
        const socket = this.connected();
        try {
            const groups = await socket.groupFetchAllParticipating();
            return Object.values(groups).map(group => ({
                id: group.id,
                name: group.subject,
                announcementOnly: !!group.announce
            }));
        } catch {
            throw new ServiceError(503, 'GROUPS_UNAVAILABLE', 'Não foi possível consultar os grupos.');
        }
    }

    async validateGroup(groupId: string) {
        const socket = this.connected();
        let group;
        try {
            group = await socket.groupMetadata(groupId);
        } catch {
            throw new ServiceError(503, 'GROUP_UNAVAILABLE', 'Não foi possível consultar o grupo.');
        }
        const ownIds = new Set([socket.user?.id, socket.user?.lid].filter((id): id is string => !!id).map(jidNormalizedUser));
        const self = group.participants.find(participant => [participant.id, participant.phoneNumber, participant.lid]
            .filter((id): id is string => !!id).some(id => ownIds.has(jidNormalizedUser(id))));
        if (!self) throw new ServiceError(403, 'NOT_GROUP_MEMBER', 'A conta não participa deste grupo.');
        if (group.announce && !self.admin) throw new ServiceError(403, 'GROUP_RESTRICTED', 'Somente administradores podem enviar neste grupo.');
    }

    async send(groupId: string, text: string) {
        const socket = this.connected();
        let timer: NodeJS.Timeout | undefined;
        try {
            const result = await Promise.race([
                socket.sendMessage(groupId, {text, linkPreview: null}),
                new Promise<never>((_, reject) => {
                    timer = setTimeout(() => {
                        socket.end(new Error('Send timeout'));
                        reject(new Error('Send timeout'));
                    }, 30_000);
                }),
            ]);
            if (!result?.key.id) throw new Error('Missing message ID');
            return result.key.id;
        } finally {
            if (timer) clearTimeout(timer);
        }
    }

    async stop() {
        this.stopping = true;
        if (this.timer) clearTimeout(this.timer);
        this.socket?.end(undefined);
        await this.saves;
        this.currentQr = undefined;
        this.setStatus('disconnected');
    }
}
