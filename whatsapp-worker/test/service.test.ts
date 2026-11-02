import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {buildApp} from '../src/app.js';
import {Dispatcher} from '../src/dispatch.js';
import {type Gateway, ServiceError} from '../src/contracts.js';
import {readConfig} from '../src/config.js';

const token = 'test-token-with-at-least-32-characters';

async function fixture(t: { after: (fn: () => Promise<void>) => void }) {
    const directory = await mkdtemp(join(tmpdir(), 'whatsapp-worker-'));
    let sends = 0;
    const gateway: Gateway = {
        connection: () => ({status: 'connected'}), qr: () => 'test-qr',
        groups: async () => [{id: '123@g.us', name: 'Grupo', announcementOnly: false}],
        validateGroup: async () => {
        },
        send: async () => {
            sends++;
            return 'wa-message-1';
        },
    };
    const dispatcher = new Dispatcher(gateway, directory);
    const app = buildApp({token, gateway, dispatcher, logLevel: 'silent'});
    t.after(async () => {
        await app.close();
        await rm(directory, {recursive: true, force: true});
    });
    const headers = {authorization: `Bearer ${token}`};
    const payload = {executionId: 'execution-1', groupId: '123@g.us', text: 'Olá!'};
    return {app, headers, payload, gateway, directory, count: () => sends};
}

test('all endpoints require token, including QR and health', async t => {
    const {app} = await fixture(t);
    for (const url of ['/health', '/connection', '/connection/qr', '/groups', '/messages']) {
        assert.equal((await app.inject({method: url === '/messages' ? 'POST' : 'GET', url})).statusCode, 401);
    }
});
test('connection, groups and QR responses', async t => {
    const {app, headers} = await fixture(t);
    assert.equal((await app.inject({url: '/health', headers})).statusCode, 200);
    assert.equal((await app.inject({url: '/connection', headers})).json().status, 'connected');
    assert.equal((await app.inject({url: '/groups', headers})).json().groups[0].name, 'Grupo');
    const response = await app.inject({url: '/connection/qr', headers});
    assert.match(response.json().qrImage, /^data:image\/png;base64,/);
    assert.equal(response.headers['cache-control'], 'no-store');
});
test('validation prevents invalid messages from reaching gateway', async t => {
    const {app, headers, payload, count} = await fixture(t);
    for (const changes of [{text: '  '}, {groupId: '123@s.whatsapp.net'}, {executionId: '../x'}, {text: ''}, {extra: true}]) {
        const response = await app.inject({
            method: 'POST',
            url: '/messages',
            headers,
            payload: {...payload, ...changes}
        });
        assert.equal(response.statusCode, 400);
    }
    assert.equal(count(), 0);
});
test('successful execution survives dispatcher restart and rejects changed content', async t => {
    const {app, headers, payload, gateway, directory, count} = await fixture(t);
    const response = await app.inject({method: 'POST', url: '/messages', headers, payload});
    assert.equal(response.statusCode, 200);
    assert.equal(response.json().messageId, 'wa-message-1');
    const restarted = new Dispatcher(gateway, directory);
    assert.equal((await restarted.send(payload.executionId, payload.groupId, payload.text)).replayed, true);
    await assert.rejects(restarted.send(payload.executionId, payload.groupId, 'Outro'), {code: 'EXECUTION_CONFLICT'});
    assert.equal(count(), 1);
});
test('uncertain send cannot be retried after restart', async t => {
    const {gateway, directory} = await fixture(t);
    let calls = 0;
    gateway.send = async () => {
        calls++;
        throw new Error('timeout');
    };
    for (let i = 0; i < 2; i++) {
        await assert.rejects(new Dispatcher(gateway, directory).send('uncertain', '123@g.us', 'Olá'), {code: 'SEND_UNCERTAIN'});
    }
    assert.equal(calls, 1);
});
test('confirmed preflight failure allows later retry', async t => {
    const {gateway, directory, count} = await fixture(t);
    const dispatcher = new Dispatcher(gateway, directory);
    gateway.validateGroup = async () => {
        throw new ServiceError(503, 'NOT_CONNECTED', 'Desconectado');
    };
    await assert.rejects(dispatcher.send('retry', '123@g.us', 'Olá'), {code: 'NOT_CONNECTED'});
    gateway.validateGroup = async () => {
    };
    await dispatcher.send('retry', '123@g.us', 'Olá');
    assert.equal(count(), 1);
});
test('concurrent sends are rejected rather than building an unbounded queue', async t => {
    const {gateway, directory} = await fixture(t);
    let release!: () => void;
    const wait = new Promise<void>(resolve => {
        release = resolve;
    });
    gateway.send = async () => {
        await wait;
        return 'message';
    };
    const dispatcher = new Dispatcher(gateway, directory);
    const first = dispatcher.send('one', '123@g.us', 'Olá');
    await assert.rejects(dispatcher.send('two', '123@g.us', 'Olá'), {code: 'BUSY'});
    release();
    await first;
});
test('configuration rejects placeholder credentials and invalid ports', () => {
    assert.throws(() => readConfig({}), /API_TOKEN/);
    assert.throws(() => readConfig({API_TOKEN: token, PORT: '0'}), /PORT/);
    assert.equal(readConfig({API_TOKEN: token}).host, '127.0.0.1');
});

test('deadline prevents late dispatch and allows replay of already sent results', async t => {
    const {app, headers, payload, count} = await fixture(t);
    const expired = new Date(Date.now() - 60_000).toISOString();
    const rejected = await app.inject({
        method: 'POST',
        url: '/messages',
        headers,
        payload: {...payload, sendBefore: expired}
    });
    assert.equal(rejected.statusCode, 410);
    assert.equal(rejected.json().code, 'SEND_EXPIRED');
    assert.equal(count(), 0);
    const sent = await app.inject({
        method: 'POST',
        url: '/messages',
        headers,
        payload: {...payload, sendBefore: new Date(Date.now() + 60_000).toISOString()}
    });
    assert.equal(sent.statusCode, 200);
    const replay = await app.inject({
        method: 'POST',
        url: '/messages',
        headers,
        payload: {...payload, sendBefore: expired}
    });
    assert.equal(replay.json().replayed, true);
    assert.equal(count(), 1);
});

test('deadline is checked again after group lookup and invalid dates are rejected', async t => {
    const {app, headers, payload, gateway, count} = await fixture(t);
    const invalid = await app.inject({
        method: 'POST',
        url: '/messages',
        headers,
        payload: {...payload, sendBefore: 'invalid'}
    });
    assert.equal(invalid.statusCode, 400);
    // Use a date far in the future then replace Date.now only during the lookup,
    // avoiding wall-clock sleeps in this boundary test.
    const originalNow = Date.now;
    const deadline = originalNow() + 60_000;
    gateway.validateGroup = async () => {
        Date.now = () => deadline + 1;
    };
    try {
        const response = await app.inject({
            method: 'POST',
            url: '/messages',
            headers,
            payload: {...payload, sendBefore: new Date(deadline).toISOString()}
        });
        assert.equal(response.statusCode, 410);
        assert.equal(count(), 0);
    } finally {
        Date.now = originalNow;
    }
});
