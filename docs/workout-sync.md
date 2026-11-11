# Recorded workouts and future Strava sync

Implemented in 0.5.0: import completed Samsung workouts, not live GPS tracking.

| Samsung recording | Health Connect representation |
| --- | --- |
| Session/type/title/start/end | ExerciseSessionRecord; previous session ID retained |
| Recorded GPS coordinates/time/altitude/accuracy | ExerciseRoute attached to its session |
| Log speed in m/s | SpeedRecord samples |
| Log heart rate in bpm | HeartRateRecord samples |
| Running/walking/hiking cadence | StepsCadenceRecord samples |
| Cycling cadence | CyclingPedalingCadenceRecord samples |
| Log power in W | PowerRecord samples |
| Pace | Explorer field derived as 1000 / (60 × speed) min/km; absent for zero/invalid speed |

Series keep their actual timestamps. Missing logs are not replaced by average/max
summaries. Generic cadence from rowing/strength/other activities is not treated as
steps or pedal revolutions. Cadence is not silently halved or doubled.

Workout reads use unpaged UTC-day windows from 0.5.6 because Samsung 7.1 repeated
the one-parent continuation page during physical-device testing, including after
using token-only continuation requests. Each window is processed independently;
the whole selected year is not loaded at once. A busy day can still contain
several large parent records. Series use fixed five-minute UTC
buckets, split at 500 samples, and one record per Health Connect insert call to
bound IPC payloads. The route retains its valid source coordinates, without
simplifying or interpolating it. A very large single route may still hit platform
transaction limits; hardware verification is required. Series and GPS at/after the
session's exclusive end are omitted to satisfy the bundled Health Connect client.
Series record envelopes extend one millisecond beyond their last sample (bounded
by the session end); nanosecond-only envelopes collapse when Health Connect
serializes times to milliseconds. Actual sample timestamps are unchanged.

All source permissions include Samsung EXERCISE_LOCATION. Health Connect separately
requests WRITE_EXERCISE_ROUTE, WRITE_SPEED, WRITE_HEART_RATE, WRITE_POWER,
WRITE_STEPS (step cadence) and WRITE_EXERCISE (sessions/cycling cadence). No Android
GPS sensor permission, foreground service or network permission is added.

Partial permissions export permitted records. Without Health Connect route-write
access, session writes omit routes; Health Connect preserves the existing route.
If route-write access exists but Samsung route access is missing, session updates
are skipped to avoid deleting a previously exported route; streams can still sync.
The exercise cursor advances only when both sides have the complete requested
permissions. Upgrading resets the exercise cursor to backfill the chosen history,
and increments session record versions beyond the old shell mapping.

Known correction limit: deleted/moved sample timestamps and removed chunks are not
reconciled through a deletion/change feed. Extra samples within a replaced bucket
are updated, but a completely removed bucket can remain until deleted in Health
Connect. No full change-feed reconciliation is claimed.

## Optional Strava connector (0.6.0)

Health Bridge reads a selected local day's Samsung workouts and exports the typed
session model directly. It does not reconstruct streams from explorer statistics
or depend on RepForge. Runs, treadmill runs, walks, hikes and backpacking are
supported initially. Available GPS, altitude, HR, speed, cadence and power are
written at their own timestamps into TCX. Samsung steps/minute is converted to TCX
strides/minute. Missing measurements stay missing; distance and calorie lap totals
use source totals (TCX requires these fields, so unavailable totals default to zero).

The user can save a TCX file locally or connect Strava through a configured HTTPS
authorization service, then explicitly confirm a selected upload. Files are gzipped
and sent directly to Strava. Returned upload and activity IDs are stored per athlete
and Samsung session. Known pending uploads resume polling. An uncertain POST is
blocked from blind retry; check the Strava account manually. external_id identifies
the export but does not guarantee idempotency. Another exporter, changed Samsung
UID, app-data clearing or reinstallation can still lead to duplicates.

A registered Strava app and activity:write authorization are required for direct
uploads. The included relay handles token exchange/refresh without receiving
workouts or embedding a client secret in the APK. Phone tokens use Android Keystore.
See [deployment and setup](../strava-relay/README.md). No live OAuth/upload is claimed
until an actual account and deployed service have been tested.

Primary references:

- https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes
- https://developer.android.com/health-and-fitness/health-connect/experiences/workouts
- https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-exercise-log/index.html
- https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-exercise-location/index.html
- https://developers.strava.com/docs/uploads/
- https://developers.strava.com/docs/authentication/
