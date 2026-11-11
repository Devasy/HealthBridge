# Health Bridge
<!-- impeccable:product-schema 1 -->
## Platform
android
## Stack
Standalone native Kotlin, Jetpack Compose, Samsung Health Data SDK, Health Connect.
## Product Purpose
Transfer Samsung Health measurements into Health Connect so RepForge can read them.
## Users
Samsung Health users who want their wearable measurements available to RepForge.
## Capabilities and Constraints
Explicit per-category selection and permissions, manual sync, optional battery-conscious background sync.
Health Connect sync stays on the phone. Optional, explicitly confirmed workout
uploads go directly to Strava; OAuth codes/tokens pass through a configured HTTPS
authorization service. Lightweight binary, bounded memory, no always-running service.
Keep the proprietary Samsung dependency separate from RepForge.
## Brand Commitments
Material 3 Expressive UI inspired by Essentials, as requested by the user.
