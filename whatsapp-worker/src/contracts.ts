export type ConnectionStatus = 'disconnected' | 'connecting' | 'awaiting_qr' | 'connected' | 'logged_out' | 'blocked';

export interface Group {
    id: string;
    name: string;
    announcementOnly: boolean
}

export interface Gateway {
    connection(): { status: ConnectionStatus };

    qr(): string | undefined;

    groups(): Promise<Group[]>;

    validateGroup(groupId: string): Promise<void>;

    send(groupId: string, text: string): Promise<string>;
}

export class ServiceError extends Error {
    constructor(public statusCode: number, public code: string, message: string) {
        super(message);
    }
}
