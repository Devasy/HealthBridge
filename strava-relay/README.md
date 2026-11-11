# Health Bridge Strava authorization service

This small Node 22+ service keeps the Strava **client secret off the APK**. It only
exchanges OAuth codes and refresh tokens with Strava. Workout TCX files go directly
from Health Bridge to Strava. No npm dependencies are installed.

## Setup

For registration form values and the included Docker + automatic HTTPS deployment,
follow [the step-by-step setup](SETUP.md). It keeps Node private on loopback behind
Caddy and overwrites the client-IP header used by the relay's rate limits.

1. Register an API application at https://www.strava.com/settings/api . Use an
   accurate app name and description. Follow Strava's review and athlete-capacity
   requirements before sharing it beyond your own account.
2. Choose an HTTPS origin, for example `https://bridge.your-domain.example`. Set
   Strava's Authorization Callback Domain to that hostname (without a path).
3. Configure secrets in your hosting provider's secret manager/environment:
   - `PUBLIC_BASE_URL`: that exact HTTPS origin, with no path or query.
   - `STRAVA_CLIENT_ID`: API application's numeric client ID.
   - `STRAVA_CLIENT_SECRET`: API application's secret. Never put it in source,
     Android resources, Gradle properties or the app's URL field.
   - `PORT`: optional, defaults to 8080.
4. Run `node server.mjs` behind an HTTPS reverse proxy on the same machine. The
   service binds **127.0.0.1**. Proxy `/config`, `/authorize`, `/oauth/callback` and
   `/token` without changing the path. Supply a valid public TLS certificate.
   The proxy must **overwrite** `X-Real-IP` with the actual client IP (for nginx:
   `proxy_set_header X-Real-IP $remote_addr;`). Do not pass through a client-supplied
   header. The service trusts that header only from loopback and keeps independent
   per-client, per-endpoint budgets. Disable access-log query strings and request/response-body logging for these
   endpoints: OAuth codes and tokens are sensitive. Add perimeter rate limits.
   Use one service instance initially; OAuth transactions are held in memory for
   ten minutes. Restarting it requires starting authorization again.
5. In Health Bridge → **Strava · sync workouts**, enter the HTTPS origin and tap
   **Connect Strava**. Allow activity uploads in Strava. No API secret is entered
   on the phone. The external browser/Strava app returns to Health Bridge.
6. Allow Samsung workouts and routes, choose a date, read that day's workouts,
   then explicitly confirm the workout to upload. Tap **Check upload status**
   while Strava processes it; **View on Strava** appears after an activity ID is
   returned. Automatic/bulk/background Strava upload is not enabled.

An origin entered in the phone is trusted with OAuth tokens. Use only your own
service. It must present the same Strava client configuration throughout a
connection. Changing it requires disconnecting and reconnecting. The phone uses
an expiring state and a separate verifier/challenge binding, so intercepting the
custom-scheme callback alone cannot redeem the code through this service. This is
an application-level binding, not a claim that Strava supports OAuth PKCE.

## Duplicate and disconnect behavior

The persistent ledger is keyed by Strava athlete ID and Samsung session UID. A
synced session is never reposted even if the source is later edited. A pending
upload ID resumes polling. Losing the POST response or dying during submission
leaves the outcome unknown: automatic reposting is blocked to avoid duplication.
Definitively rejected submissions or processing errors allow an explicitly
confirmed retry; read the source again first if the workout was corrected.
Check Strava manually before exporting/re-uploading that workout yourself.

Strava may report a duplicate from another exporter. This is shown as terminal;
it does not give Health Bridge a reliable activity link. The connector cannot
guarantee global deduplication across other apps, changed source UIDs, cleared
storage or reinstallations. It never deletes activities or automatically replaces
an existing Strava activity. Strava may recalculate pace, distance and other metrics
when processing a file; live verification is needed to check the rendered result.

Disconnect removes phone tokens and pending OAuth state but retains the upload
ledger. Revoke the application's access in Strava settings to disconnect it there.
Already uploaded activities remain on Strava until you delete them there.

## Tests and references

Run `node --test`. Tests use loopback HTTP with mocked Strava responses; production
configuration requires HTTPS. No real accounts are used by these tests.

- https://developers.strava.com/docs/authentication/
- https://developers.strava.com/docs/uploads/
- https://developers.strava.com/docs/reference/#api-Uploads-createUpload
