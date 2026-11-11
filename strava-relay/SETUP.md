# Set up your own Strava connection

Prepared for your own Health Bridge installation. No cloud account, domain, server,
Strava application or subscription has been created or purchased by this setup.

## 1. Register the Strava app

Sign in at https://www.strava.com/settings/api . Strava currently requires a
subscription to create an API app. New apps start in single-player mode, which is
enough for your own account. The account must be yours; the app cannot register it
on your behalf without your signed-in session.

Suggested form values (adjust to the actual fields shown):

| Field | Value |
| --- | --- |
| Application name | Health Bridge |
| Category | Choose the available fitness/data integration category |
| Website | `https://github.com/Devasy/HealthBridge` (use a publicly accessible project page when registering) |
| Description | Personal Android bridge that uploads selected Samsung Health runs, walks and hikes, including recorded route and available workout measurements, to my Strava account. |
| Authorization callback domain | Your chosen relay hostname, e.g. `bridge.your-domain.example`; no scheme or path |

Use your own accurate application icon if Strava requests one. The existing Health
Bridge icon source is `../app/src/main/res/drawable/ic_bridge.xml`; the optimized
APK also displays it. This registration does not authorize uploads by itself.

The callback URL the software sends is exactly:
`https://YOUR_RELAY_HOST/oauth/callback`. The server returns the browser to
`healthbridge-strava://oauth/callback`, which is registered in the Android app.

Record the **Client ID** and **Client Secret** in your password manager. Enter the
secret only into your server's environment/secret manager, never chat, a committed
file or the phone. The dashboard's existing access token is not a substitute for
Health Bridge's consent flow: we request `activity:write` for uploads.

## 2. Prepare a host

The included deployment targets a Linux server with Docker Engine and Docker
Compose v2. Use an existing server if you have one; selecting or purchasing hosting
and a domain remains your choice. It does not require a separate database.

Choose a public hostname and point its DNS A record (and AAAA only if IPv6 works)
directly to that server. Allow incoming TCP ports 80 and 443. This configuration
expects direct public traffic, without a CDN or another proxy in front. Caddy
obtains and renews the HTTPS certificate automatically. No API port is published.

Copy this `strava-relay` directory to the server using your existing secure transfer
method. It contains no real secrets. From that directory:

```sh
cp .env.example .env
chmod 600 .env
```

Edit `.env` locally on the server and replace all three sample values:

```dotenv
RELAY_HOST=your-real-hostname
STRAVA_CLIENT_ID=your-numeric-client-id
STRAVA_CLIENT_SECRET=your-secret-from-strava
```

The real `.env` is ignored by Git and excluded from the Docker build context.
Docker administrators can inspect container environment variables; only trusted
administrators should have access to this host. Do not print the resolved Compose
configuration after adding real secrets.

Validate without displaying secrets, then start:

```sh
docker compose --env-file .env -f deploy/compose.yaml config --quiet
docker compose --env-file .env -f deploy/compose.yaml up --build -d
docker compose --env-file .env -f deploy/compose.yaml ps
curl --fail https://YOUR_RELAY_HOST/config
```

The last command should return only your numeric `client_id`. It must not return
tokens or the client secret. A request to `/` is intentionally not a dashboard and
returns 404; use `/config` to check readiness.

Caddy and Node share a container network namespace so Node's loopback-only listener
remains private. Caddy overwrites the client-IP header required by the relay. Access
logging is not enabled, and the HTTP request-error logger is excluded because it
can include callback query strings. Do not enable URL-query or request/response-body logs for
OAuth endpoints. Keep one relay instance: pending authorization state is held in
memory for ten minutes, and a restart requires starting connection again.

To stop the service without deleting certificate volumes:

```sh
docker compose --env-file .env -f deploy/compose.yaml down
```

## 3. Connect the phone later

After installing Health Bridge 0.6.0:

1. Open **Strava · sync workouts**.
2. Enter `https://YOUR_RELAY_HOST` as the authorization service URL.
3. Tap **Connect Strava**, sign in and allow activity uploads.
4. Allow Samsung workouts and exercise-location reads.
5. Choose a day and read its workouts. Start with a workout absent from Strava.
6. Confirm one upload. If processing is pending, use **Check upload status**.
7. Open **View on Strava** and check the route, HR, cadence and pace there.

The app will not bulk-upload old workouts or upload in the background. Known upload
IDs resume status checks; ambiguous submissions are blocked from automatic retry.
See README.md for duplicate, token handling and disconnect limitations.

## Preparation vs. verification

The app and relay have automated tests. The Compose configuration is a deployment
recipe, not proof of a running HTTPS service. Real deployment requires your chosen
host, DNS and Strava client credentials. Real OAuth/upload verification additionally
requires your phone and Strava consent; these have not happened yet.

Preparation check on 2026-11-01: `docker compose --env-file .env.example -f
deploy/compose.yaml config --quiet` passed using sample values. Docker's Linux
engine was not running on the development PC, so container startup, Caddy config
adaptation, public HTTPS certificates and hosted OAuth have not been tested here.

Official references:

- https://developers.strava.com/docs/getting-started/
- https://developers.strava.com/docs/authentication/
- https://docs.docker.com/reference/compose-file/services/#network_mode
- https://caddyserver.com/docs/automatic-https
