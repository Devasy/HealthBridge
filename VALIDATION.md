# Validation

## Automated verification

The 0.6.0 development build passed 67 JVM tests, release assembly and Android lint
on 2026-11-01. Lint reported zero errors and 30 warnings (dependency and KTX
suggestions). The optional Strava relay passed six tests using mocked responses.
No live Strava account, API application or hosted service was configured.

Tests cover Health Connect conversions, stable retry identities, partial failures,
workout routes and stream bounds, pagination progress, explorer statistics,
respiratory import parsing, TCX export, upload state transitions and OAuth checks.
Test workouts use synthetic values; personal exports and phone captures are not
included in this repository.

## Device evidence and limits

Prior device checks confirmed Samsung SDK retrieval and the native explorer.
Exercise reads use bounded UTC-day windows because continuation requests repeated
results on the tested Samsung Health version. Database access is serialized to
avoid overlapping helper lifetimes.

Source retrieval does not by itself prove successful Health Connect write/readback.
Complete workout export/readback, all supported data types, permission revocation,
long history ranges and battery behavior still need broader device verification.
Version 0.6.0 installation and live Strava OAuth/upload were not verified during
its implementation. Respiratory availability depends on actual Health Connect
records or a supported user-selected export; the bundled Samsung SDK does not
provide a direct respiratory-rate field.

Personal diagnostic notes, screenshots, health readings, device identifiers, local
configuration, SDK AARs, APKs and signing keys stay outside version control.
