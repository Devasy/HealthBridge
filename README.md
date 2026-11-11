# Health Bridge

Standalone native Kotlin companion for broad Samsung Health access and export.
Standard measurements flow **Samsung Health → Health Connect → RepForge**.
Samsung-only scores and full body details are displayed in the companion's private
on-device history; RepForge ingestion of these local extras is not implemented yet.
Material 3 Expressive with dynamic colour, system light/dark themes, grouped settings
and one primary sync action. UI reference: [Essentials](https://github.com/sameerasw/essentials).
The app is independent of RepForge's Flutter build and has its own package,
`com.repforge.healthbridge`.

Strava is an optional, unconfigured integration. No Strava account, token, API app
registration or hosted authorization service is needed for Samsung → Health Connect
sync. Proprietary SDK binaries, signing keys, device data and local credentials
are excluded from this repository; provide your own Samsung SDK for builds.

## Build

Requires Java 17+, Android SDK platform 37, and the licensed Samsung Health Data SDK
1.1.0 AAR. Gradle 9.3.1 is checksum-pinned; dependencies are pinned in `app/build.gradle.kts`.

From this directory on Windows:

```powershell
./scripts/prepare-sdk.ps1
# Or provide the locally downloaded SDK AAR:
./scripts/prepare-sdk.ps1 -SdkPath C:/path/to/samsung-health-data-api-1.1.0.aar
# Set ANDROID_HOME or create local.properties containing sdk.dir=C\:/Android/SDK
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew.bat :app:assembleRelease
# Optional installable, optimized development preview (standard Android debug key):
./scripts/sign-preview.ps1
# After installing the optimized preview on a USB-debugging-enabled phone:
./scripts/smoke-test-device.ps1 -Serial YOUR_DEVICE_SERIAL
```

On other platforms, place the same AAR in `app/libs/` and run `./gradlew`.
The AAR and machine-specific configuration are ignored by Git. SDK download and
usage terms: [Samsung Health Data SDK](https://developer.samsung.com/health/data/overview.html).

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Release APK:
`app/build/outputs/apk/release/app-release-unsigned.apk`. Release builds enable R8
and resource shrinking and deliberately require your own stable signing key.
Do not use RepForge's signing key for this companion. Standard debug builds use
the Android debug key; changing signing keys may require re-registering with Samsung.
The preview signing helper writes `app/build/outputs/apk/release/health-bridge-preview.apk`.
It is for local testing and is not a production-signed release.

## First run

1. Install Samsung Health and make sure your watch has synced to it.
2. Enable **Developer Mode for Data Read** in Samsung Health. Open Settings → About
   Samsung Health, tap the version line at least 10 times, then open Developer mode
   (Samsung Health Data SDK). In-app setup help can copy the package and SHA-256
   fingerprint if your installed version requires registration.
3. Connect Samsung Health to request all supported read scopes (25 SDK data types,
   including exercise-location permission). Samsung may group several SDK types into
   one permission row. Choose which measurements to export with the dashboard switches,
   then allow their Health Connect write permissions. Reads and export choices are separate.
4. Tap **Sync now**. Start with seven days; choose 30 or 90 days for a larger backfill.
5. Allow RepForge to read the corresponding categories in Health Connect. Exported
   records are attributed to Health Bridge, not Samsung Health's package.
6. Optionally enable background sync. Android schedules it roughly every six hours
   when the battery isn't low; execution time isn't exact and OEM battery controls
   can defer it. There is no foreground service or persistent notification.

Public distribution needs Samsung partner approval; developer mode is for testing.
See [Samsung's setup guide](https://developer.samsung.com/health/data/guide/developer-mode.html).

## Implemented data

| Category | Health Connect output | Default |
| --- | --- | --- |
| Galaxy Energy Score | Private on-device score history; no invented Health Connect record | On |
| Samsung Sleep Score | Private on-device score history; no sleep-session export | On |
| Body composition | Weight, body-fat percentage, fat-free/lean mass, body-water mass, BMR | On |
| Skin temperature | Baseline and timestamped deltas; gated by device feature support | On |
| Blood oxygen | Timestamped saturation records | On |
| Blood pressure | Complete systolic/diastolic pairs | On |
| Blood glucose | Spot readings or continuous samples in mmol/L; meal/specimen context where equivalent | On |
| Body temperature | Measured body temperature in Celsius | On |
| Floors climbed | Recorded floor counts | On |
| Water intake | Hydration volume in milliliters | On |
| Nutrition | Calories, macros and available micronutrients, preserving g/mg/µg | On |
| Exercise | Session/type/title/times plus recorded GPS, speed, workout HR, power and running/walking or cycling cadence | On |
| Steps | Daily totals, not invented individual steps | On |
| Daily activity | Daily active calories, total calories and distance | On |
| Heart rate | Timestamped samples; optional because Samsung may already export them | Off |
| Sleep sessions | Sessions and stages; optional because Samsung may already export them | Off |
| Goals, sleep-apnea signs, irregular-rhythm notifications, profile, other fields | Private explorer cache; not misrepresented as Health Connect measurements | Read when allowed |

Body composition additionally preserves skeletal muscle mass/percentage, fat mass,
BMI, fat-free mass, water and BMR for the dated insights display. These distinct
quantities aren't squeezed into unrelated Health Connect record types.
Heart rate and ordinary sleep sessions are available as explicit export choices in
0.4.0 and remain off unless selected. The 0.4.0 migration enables the eight newly
implemented export categories plus oxygen and blood pressure, matching the broader
requested scope. Other existing selections are preserved. Previously
exported Health Connect data is not deleted. From 0.5.0, recorded workout routes and
supported log streams are exported too; other summaries remain in the explorer.
Profile fields have no measurement timestamp: they are shown as a current snapshot,
not written as fabricated historical height/weight measurements.

Keep categories already exported by Samsung disabled to avoid cross-app duplicates.
Bridge IDs only deduplicate this app's records; they cannot deduplicate Samsung's
own Health Connect writes. Unsupported skin-temperature devices show setup guidance;
turn that category off to use the remaining categories.

Corrections to the parked architecture:

- Skeletal muscle mass is **not** lean body mass. Only Samsung's fat-free mass is
  exported as `LeanBodyMassRecord`.
- Skin temperature is **not** basal body temperature. A Samsung absolute summary
  temperature (or the mean of available source samples) is used as an explicit
  baseline; sample-minus-baseline deltas preserve the original absolute values.
  This derived baseline does not claim to be Samsung's physiological baseline.
- Energy Score and Sleep Score use Samsung's actual SDK fields and are stored
  locally because Health Connect has no equivalent standard score records.
- No invented HRV, stress, resting heart rate or respiratory rate readings: the
  available Samsung SDK doesn't expose equivalent fields for this integration.
- Recording method is unknown rather than falsely marking imported data as manual.

## Resource and data handling

### Recorded running workouts: 0.5.0

Exercise sync includes Samsung's recorded GPS route and timestamped speed, workout
HR, power and supported cadence streams. Pace is derived from positive recorded
speed and appears as a labelled min/km explorer field; stopped/missing speed has
no invented pace. Samsung exercise-location and Health Connect route-write access
must be granted separately. General all-day HR remains an independent export choice.
Avoid duplicate workout/HR writers if Samsung already exports the same data.

Routes are attached to ExerciseSessionRecord; streams are associated by source and
time range. Workout reads use bounded UTC-day windows without SDK pagination;
Samsung 7.1 repeated the one-parent continuation page on the physical test phone.
Series are capped at 500
samples per record, and workout records are written individually. No GPS tracking
service or map library is added. Route-preservation behavior is documented in
[workout sync](docs/workout-sync.md). Optional manual Strava upload is available
under **Strava · sync workouts**. See [Strava setup](strava-relay/README.md).

- No analytics, ads, bundled fonts, images, Flutter runtime or always-running
  service. INTERNET is used for optional Strava authorization and selected uploads;
  standard Health Connect sync does not require a network connection.
- At most 100 SDK data points per page; insert batches contain at most 100 records.
  One source point's series is mapped at a time. Very large series still occupy
  the SDK point's memory; they aren't streamed individually from the vendor SDK.
- Settings, per-category cursors and last-run summaries use private preferences.
  Selected Samsung scores and body-composition values use private Android SQLite
  storage, bounded to 90 days, for the insight history. Health values aren't logged
  or exposed through an exported provider. Only explicitly confirmed workouts are
  uploaded to Strava. Backup and device transfer are
  disabled. Clearing this app's storage deletes its private history.
- IDs include Samsung UID, category and field/sample timestamp. Source update time
  becomes the record version, so retries reuse IDs and edits can replace old readings.
- Each category's cursor advances only after every page and write succeeds. Failed
  categories don't prevent other permitted categories from completing. Cancellation
  propagates; manual and periodic workers share a mutex.
- Samsung-only scores work even without Health Connect. Body details can also be
  saved locally without Health Connect write permission; only permitted standard
  records are exported. Missing Samsung permissions are reported per category.
- Incremental reads overlap the last successful run by 48 hours, bounded by the
  selected history window. Choosing a history chip resets cursors and requests that
  full history on the next sync, including choosing the currently selected chip.
- Source deletions and edits older than the overlap aren't automatically reconciled.
  Changing a selection or uninstalling does not remove previously exported records;
  remove those in Health Connect. Full change-feed reconciliation is future work.
- Samsung's nested sleep sessions have no stable session UID. Their export IDs use
  the parent UID plus session start. Editing sleep/nap boundaries can therefore
  leave the previous export alongside the corrected one. Delete the old Health
  Bridge sleep records in Health Connect before rescanning edited sleep history.
  Equivalent timestamp changes to individual oxygen samples have the same limitation.

## Validation and remaining device checks

JVM tests exercise unit conversions, invalid/missing readings, source corrections,
stable IDs, series ordering, sleep-stage clipping, pagination, write failures,
per-category cursors, cancellation, stable continuation handles, repeated result
pages and bounded insert batches.

The implementation must still be checked on a Samsung Health-enabled device:
developer-mode setup, source permission revocation, partial Health Connect permissions,
real 7/30/90-day reads, background execution under OEM battery restrictions, and
RepForge ingestion. Verify light/dark UI, large system fonts, TalkBack and actual
memory/battery usage on that device. No device performance claims are made from
the source code alone.

`SPECIFICATION.md` is the historical parked architecture; this README describes
the implemented scope and the semantic corrections above.

### Startup fix verified: 0.1.1 (27 October 2026)

The first optimized preview crashed in `androidx.startup.InitializationProvider`:
`NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []`.
R8 retained Room's generated class name but removed the no-argument constructor
used by WorkManager's reflective database initialization. The targeted keep rule
in `app/proguard-rules.pro` preserves Room database constructors without disabling shrinking.

Version 0.1.1 was installed as an update on a Pixel 8 Pro running Android 17.
Two consecutive cold starts passed and the dashboard remained resumed. The
optimized development-signed APK is approximately 2.79 MB. All 20 JVM tests pass;
Android lint reports 0 errors and 16 non-blocking warnings. The device smoke test
above covers the optimized artifact's startup, which JVM mapping tests cannot.
Live Samsung/Health Connect transfer and background operation still require testing.

## Data explorer: version 0.3.0

Open **Explore all data & charts** from the dashboard. This is a read-only discovery
screen, separate from the bridge's Health Connect writes. It enumerates the actual
public `DataTypes` constants and their public fields from the bundled Samsung SDK.
It does not infer that a supported type has records: each source shows its permission,
read result, empty result or failure after a scan. Current profile fields are a
labelled snapshot. Routes are nested within Exercise, not a separate record list.

1. Choose **Allow Samsung reads** and grant whichever categories you want to inspect.
2. Choose a date range and tap **Scan all types**, or select one type and use
   **Scan this source**. Changing range/date reuses cached data until you scan again.
3. Choose a field from the field dropdown. Numeric fields get a chart; recorded
   strings and categorical values appear in the recent-record list.
4. **1 day** plots every cached numeric point in that local calendar day. **7 days**,
   **30 days**, and **1 year** compute exact daily min, max, median, mean and count.
   Tap a chart point/day for its statistics. Missing days are not filled with zero.
5. Aggregate-only SDK types such as steps/activity goals are marked **Samsung daily
   aggregate**. They are not presented as raw intraday measurements. SDK series,
   recorded scalar values and export summaries remain separate fields.

### Respiratory rate

The previous Flutter inspector reads respiratory rate from **Health Connect**. Its
optional demo-preview mode can generate a curve for sparse data; the new explorer
has no demo readings. Select **Respiratory rate · Health Connect**, allow respiratory
read access (and older-history access where available), then scan. Records retain
their actual writer package in the record list, so another app's values are not
mislabelled as Samsung SDK data.

Samsung's public Data SDK 1.1.0 has no respiratory-rate field. If Health Connect has
no real records, use Samsung Health **Settings → Download personal data**, then
**Import respiratory ZIP / CSV**. Samsung's respiratory summary CSV has `average`,
`lower_limit`, `upper_limit` and UTC start/end timestamps; binning JSON can contain
timestamped `respiratory_rate` readings. Imports keep raw samples separate from
session summaries. Importing a summary does not invent minute-level samples.

Imports are streamed without extracting archive paths. Relevant ZIP entries are
limited to 16 MB each and the complete ZIP to 512 MB uncompressed; choose an individual
respiratory CSV/JSON for larger exports. Cancel is checked while reading input,
including unrelated ZIP entries. Corrections with the same field/time/source ID
replace previous imported values instead of creating another point.

### Explorer cache and limits

The private SQLite chart cache keeps at most 366 days of observations, pruned when
opened; backups/device transfers remain disabled. Year scans can take longer and
use more disk space than short scans. From 0.4.0, Sync now and opt-in background sync
also refresh all permitted Samsung chart sources. Each source has an independent
48-hour overlap cursor within the chosen 7/30/90-day backfill. Changing the history
chip resets both export and chart cursors. Per-day statistics hold one day's numeric values in memory at a
time. All raw one-day points are retained in the plot; the visible record list
shows the latest 100 rows. Unknown units are labelled as unspecified.

Successful rescans replace the relevant cached range, including empty results.
If a paginated scan fails or is canceled, that source explicitly says cached data
may be partial; retry before interpreting statistics as a complete time range.
Samsung-only score ingestion into RepForge remains a separate integration task.
