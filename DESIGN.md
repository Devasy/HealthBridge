# Health Bridge interface

Mode: Operate. A small native utility inspired by Essentials' Material 3 Expressive UI.

Use wallpaper-derived dynamic colour on Android 12+, light and dark system themes.
Fallback palette: primary #326B55, primary container #B5F1D2, surface #F5FBF4,
surface container #E9EFE8, on-surface #181D19, dark surface #101510.
System Roboto through Material's expressive type scale; no bundled font assets.

One vertically scrolling dashboard capped at 600dp: large title, asymmetric rounded
tonal connection panel, prominent pill-shaped sync action, grouped connection rows,
category switches and sync preferences. Supporting instructions expand on demand.
No bottom navigation for a single destination. Respect edge-to-edge insets, system
font scaling, 48dp touch targets, system dark theme, TalkBack and reduced motion.

The main panel explains readiness rather than displaying invented statistics.
Record counts appear only after a real completed operation. Progress is indeterminate
until the number of source pages is known. Settings remain ordinary native controls.

Strava is a separate destination from the Connections section. Reuse native Material
surfaces, date picker and file picker. Show source metric counts before any upload,
an explicit upload confirmation, and persistent upload status. Keep connection setup
separate from day/workout selection, and enable local TCX export without an account.
Never imply that a Samsung read or a submitted upload proves successful Strava import.
