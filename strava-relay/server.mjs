import { createServer } from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { isIP } from 'node:net';

const callback = 'healthbridge-strava://oauth/callback';
const opaque = /^[A-Za-z0-9_-]{32,128}$/;
export function createRelay({ publicBase, clientId, clientSecret, fetchImpl = fetch, trustProxy = false }) {
  const base = new URL(publicBase);
  if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash || base.pathname !== '/') throw new Error('PUBLIC_BASE_URL must be an HTTPS origin');
  if (!/^\d+$/.test(clientId) || !clientSecret) throw new Error('Strava client configuration is required');
  const sessions = new Map();
  const buckets = new Map();
  return createServer(async (req, res) => {
    const json = (status, data) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(data)); };
    const redirect = url => { res.writeHead(302, { location: url.toString() }); res.end(); };
    res.setHeader('cache-control', 'no-store');
    res.setHeader('referrer-policy', 'no-referrer');
    res.setHeader('x-content-type-options', 'nosniff');
    res.setHeader('content-security-policy', "default-src 'none'; frame-ancestors 'none'");
    res.setHeader('strict-transport-security', 'max-age=31536000');
    try {
      const now = Date.now();
      for (const [key, value] of sessions) if (value.expires < now) sessions.delete(key);
      for (const [key, value] of buckets) if (value.expires < now) buckets.delete(key);
      const url = new URL(req.url, base);
      let ip = req.socket.remoteAddress;
      if (trustProxy) {
        const forwarded = req.headers['x-real-ip'];
        if (!['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(ip) || typeof forwarded !== 'string' || !isIP(forwarded)) return json(400, { error: 'invalid_proxy' });
        ip = forwarded;
      }
      const budget = ({ '/config': 60, '/authorize': 10, '/oauth/callback': 20, '/token': 30 })[url.pathname];
      const bucket = `${ip}:${budget ? url.pathname : 'other'}`;
      if (!buckets.has(bucket)) {
        if (buckets.size >= 10000) return json(503, { error: 'busy' });
        buckets.set(bucket, { count: 0, expires: now + 60000 });
      }
      if (++buckets.get(bucket).count > (budget ?? 20)) return json(429, { error: 'rate_limited' });
      if (req.method === 'GET' && url.pathname === '/config') return json(200, { client_id: clientId });
      if (req.method === 'GET' && url.pathname === '/authorize') {
        const state = url.searchParams.get('state');
        const challenge = url.searchParams.get('challenge');
        if (!opaque.test(state ?? '') || !/^[A-Za-z0-9_-]{43}$/.test(challenge ?? '')) return json(400, { error: 'invalid_request' });
        if (sessions.size >= 10000 || sessions.has(state)) return json(409, { error: 'restart_authorization' });
        sessions.set(state, { challenge, expires: now + 600000 });
        const target = new URL('https://www.strava.com/oauth/mobile/authorize');
        target.search = new URLSearchParams({ client_id: clientId, redirect_uri: new URL('/oauth/callback', base).href,
          response_type: 'code', approval_prompt: 'force', scope: 'activity:write', state }).toString();
        return redirect(target);
      }
      if (req.method === 'GET' && url.pathname === '/oauth/callback') {
        const state = url.searchParams.get('state');
        const session = sessions.get(state);
        if (!session || session.code) return json(400, { error: 'expired_authorization' });
        const target = new URL(callback);
        target.searchParams.set('state', state);
        const code = url.searchParams.get('code');
        if (url.searchParams.has('error') || !code || code.length > 2048 || !url.searchParams.get('scope')?.split(',').includes('activity:write')) {
          sessions.delete(state);
          target.searchParams.set('error', 'access_denied');
        } else {
          session.code = code;
          target.searchParams.set('code', code);
        }
        return redirect(target);
      }
      if (req.method !== 'POST' || url.pathname !== '/token') return json(404, { error: 'not_found' });
      if (!req.headers['content-type']?.startsWith('application/json')) return json(415, { error: 'json_required' });
      let body = '';
      for await (const chunk of req) {
        body += chunk.toString('utf8');
        if (Buffer.byteLength(body) > 8192) return json(413, { error: 'too_large' });
      }
      let data;
      try { data = JSON.parse(body); } catch { return json(400, { error: 'invalid_json' }); }
      if (!data || typeof data !== 'object') return json(400, { error: 'invalid_request' });
      const fields = { client_id: clientId, client_secret: clientSecret };
      if (data.grant_type === 'authorization_code') {
        const session = sessions.get(data.state);
        const verifier = typeof data.verifier === 'string' ? data.verifier : '';
        const digest = createHash('sha256').update(verifier).digest('base64url');
        if (!session?.code || session.code !== data.code || !opaque.test(verifier) ||
            !timingSafeEqual(Buffer.from(digest), Buffer.from(session.challenge))) return json(403, { error: 'invalid_authorization' });
        sessions.delete(data.state);
        Object.assign(fields, { grant_type: 'authorization_code', code: data.code });
      } else if (data.grant_type === 'refresh_token' && typeof data.refresh_token === 'string' && data.refresh_token.length > 0 && data.refresh_token.length <= 2048) {
        Object.assign(fields, { grant_type: 'refresh_token', refresh_token: data.refresh_token });
      } else return json(400, { error: 'invalid_grant' });
      const response = await fetchImpl('https://www.strava.com/oauth/token', {
        method: 'POST', body: new URLSearchParams(fields), signal: AbortSignal.timeout(15000), redirect: 'error'
      });
      if (!response.ok) return json(response.status === 429 ? 429 : response.status >= 500 ? 502 : 401, { error: 'strava_token_failed' });
      const result = await response.json();
      if (typeof result.access_token !== 'string' || typeof result.refresh_token !== 'string' || !Number.isFinite(result.expires_at)) return json(502, { error: 'invalid_token_response' });
      return json(200, { access_token: result.access_token, refresh_token: result.refresh_token, expires_at: result.expires_at,
        ...(result.athlete?.id ? { athlete: { id: result.athlete.id } } : {}) });
    } catch {
      if (!res.headersSent) json(502, { error: 'service_unavailable' }); else res.end();
    }
  }).on('clientError', (_error, socket) => socket.end('HTTP/1.1 400 Bad Request\r\n\r\n'));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createRelay({ publicBase: process.env.PUBLIC_BASE_URL, clientId: process.env.STRAVA_CLIENT_ID,
    clientSecret: process.env.STRAVA_CLIENT_SECRET, trustProxy: true });
  server.requestTimeout = 20000;
  server.headersTimeout = 10000;
  server.listen(Number(process.env.PORT || 8080), '127.0.0.1');
}
