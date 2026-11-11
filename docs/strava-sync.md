# Strava sync implementation

Health Bridge owns Samsung-to-Strava export. RepForge has no upload, authorization or retry responsibilities. Existing Health Connect sync remains independent. Initial release is manual, selected-workout upload; automatic bulk upload is deliberately off.

## Design

- Read Samsung exercise sessions directly, one selected local day at a time (including historical days). Offer run, treadmill, walk, hike and backpacking sessions only initially.
- Export TCX v2 with time-aligned GPS, altitude, heart rate, speed, running cadence and power when present. Do not fill gaps or fabricate cumulative distances. TCX RunCadence is strides/minute, so Samsung steps/minute is halved. Preserve source totals and elapsed duration. Reject invalid sessions and unsupported sports.
- Use a configurable HTTPS OAuth relay, with a dependency-free Node service included for deployment. Strava client secret stays on that service. The service exchanges codes and refresh tokens, never receives workouts. The phone uploads files directly to Strava.
- OAuth uses the external browser, random expiring state, a fixed HTTPS callback on the relay, and a fixed Android callback scheme. Phone tokens and pending authorization are encrypted with Android Keystore; backups are disabled.
- Persist uploads by athlete + Samsung session UID. Persist SUBMITTING before the POST. Persist returned upload ID before polling. A lost response or process death during POST is UNKNOWN and is never automatically reposted. A known upload is polled again. Successful/duplicate records are terminal even if Samsung later edits the source.
- Strava's external_id is only a correlation identifier, not an idempotency guarantee. Other exporters may already have uploaded the same workout; show duplicate errors without claiming global duplicate prevention.
- Show setup, connection, date selection, actual metric counts, explicit selected-workout upload and durable status. Include local TCX export for use before OAuth deployment. Show a Strava link only after receiving an activity ID.
- No secrets checked in, no automatic publishing/deployment, no live upload without the user's Strava authorization and explicit upload action. INTERNET becomes necessary; privacy copy must explain the optional transfer.

## Implementation plan

- [x] Add failing JVM tests for valid TCX, sparse streams, filtering/units and XML escaping; implement dependency-free encoder.
- [x] Add failing coroutine tests for upload lifecycle, same-ID retry, pending resume, ambiguous response and athlete isolation; implement persistence-neutral engine and durable store.
- [x] Add relay security and token exchange tests, then implement the HTTPS deployment service and setup instructions.
- [x] Add Android encrypted credentials, HTTP adapters and OAuth state verification; wire a Material 3 Strava activity into Health Bridge.
- [x] Run JVM tests, Node tests, release build and lint. Review failure handling and privacy. Sign preview and check phone availability.
- [ ] Install/test on the Pixel when connected; verify real authorization and upload after relay configuration. No device appeared in `adb devices -l` during this implementation.

## Validation limits

No configured Strava API application or deployed relay is present at implementation start. Unit tests and a phone screen do not prove a successful Strava upload. Record actual validation separately.

2026-11-01: 67 JVM tests, six Node relay tests, release assembly and lint passed
(zero lint errors, 30 warnings, including existing warnings and KTX suggestions).
Fresh review findings about rejected-upload recovery and proxy rate isolation were
reproduced in tests and fixed. Signed preview is 3,979,745 bytes; SHA-256
`9B94E09075F1129E51F6ECD0DD6EC780600FC22549F83179F84528D46A9A6055`.
No live OAuth, Strava upload or phone installation has been verified for 0.6.0.

References: https://developers.strava.com/docs/authentication/ , https://developers.strava.com/docs/uploads/ , https://developers.strava.com/docs/reference/#api-Uploads-createUpload
