import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRelay } from './server.mjs';

const verifier = 'v'.repeat(64);
const challenge = createHash('sha256').update(verifier).digest('base64url');
const state = 's'.repeat(48);
async function fixture(t, upstream = async () => Response.json({ access_token: 'access', refresh_token: 'refresh', expires_at: 1234, athlete: { id: 7 } }), options = {}) {
  const server = createRelay({ publicBase: 'https://bridge.example', clientId: '123', clientSecret: 'secret', fetchImpl: upstream, ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  return (path, options) => fetch(base + path, { redirect: 'manual', ...options });
}
test('public configuration never contains secret and rejects insecure deployment URL', async t => {
  assert.throws(() => createRelay({ publicBase: 'http://bridge.example', clientId: '123', clientSecret: 'secret' }));
  const request = await fixture(t);
  const response = await request('/config');
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { client_id: '123' });
});
test('callback requires a started unexpired flow', async t => {
  const request = await fixture(t);
  const response = await request(`/oauth/callback?state=${state}&code=stolen`);
  assert.equal(response.status, 400);
  assert.equal(response.headers.get('location'), null);
});
test('code exchange binds to phone verifier and consumes the code once', async t => {
  let calls = 0;
  const request = await fixture(t, async (url, options) => {
    calls++;
    assert.equal(url, 'https://www.strava.com/oauth/token');
    assert.equal(options.body.get('client_secret'), 'secret');
    return Response.json({ access_token: 'access', refresh_token: 'refresh', expires_at: 1234, athlete: { id: 7 } });
  });
  const started = await request(`/authorize?state=${state}&challenge=${challenge}`);
  assert.equal(started.status, 302);
  const authorization = new URL(started.headers.get('location'));
  assert.equal(authorization.hostname, 'www.strava.com');
  assert.equal(authorization.searchParams.get('redirect_uri'), 'https://bridge.example/oauth/callback');
  assert.equal(authorization.searchParams.get('scope'), 'activity:write');
  const callback = await request(`/oauth/callback?state=${state}&code=valid&scope=activity:write`);
  assert.equal(callback.status, 302);
  assert.equal(new URL(callback.headers.get('location')).protocol, 'healthbridge-strava:');
  const exchange = value => request('/token', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ grant_type: 'authorization_code', code: 'valid', state, verifier: value }) });
  assert.equal((await exchange('wrong')).status, 403);
  assert.equal(calls, 0);
  const exchanged = await exchange(verifier);
  assert.equal(exchanged.status, 200);
  assert.equal((await exchanged.json()).athlete.id, 7);
  assert.equal((await exchange(verifier)).status, 403);
  assert.equal(calls, 1);
});
test('denied scope is returned to app without exchanging credentials', async t => {
  const request = await fixture(t);
  await request(`/authorize?state=${state}&challenge=${challenge}`);
  const response = await request(`/oauth/callback?state=${state}&code=valid&scope=read`);
  assert.equal(new URL(response.headers.get('location')).searchParams.get('error'), 'access_denied');
});
test('upstream failures never leak response bodies or credentials', async t => {
  const request = await fixture(t, async () => new Response('secret upstream text', { status: 400 }));
  const response = await request('/token', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ grant_type: 'refresh_token', refresh_token: 'refresh' }) });
  assert.equal(response.status, 401);
  assert.equal((await response.text()).includes('secret'), false);
});
test('trusted proxy isolates client and endpoint rate budgets', async t => {
  const request = await fixture(t, undefined, { trustProxy: true });
  const options = { headers: { 'x-real-ip': '198.51.100.1' } };
  for (let i = 0; i < 60; i++) assert.equal((await request('/config', options)).status, 200);
  assert.equal((await request('/config', options)).status, 429);
  assert.equal((await request('/config', { headers: { 'x-real-ip': '198.51.100.2' } })).status, 200);
  assert.equal((await request(`/authorize?state=${state}&challenge=${challenge}`, options)).status, 302);
  assert.equal((await request('/config', { headers: { 'x-real-ip': 'forged, client' } })).status, 400);
});
