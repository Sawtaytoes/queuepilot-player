# QueuePilot Player

Experimental native TV clients. Keep this repository suitable for publication: invented
fixtures only, no household names, endpoints, credentials, or private watch history.
Private deployment records belong to queuepilot-project-notes.

Read README.md before changing behavior. Preserve the existing QueuePilot engine as the
authority for queue eligibility, selection, profile binding, and queue history. Do not
turn the current /api/play endpoint into native playback: it controls external devices.

Use Google Android SDK and Media3 for the Android harness. Vet every new dependency's
provenance under the workspace software-origin preference. Plezy is a reference checkout,
not an adopted dependency. Roku needs a separate BrightScript/SceneGraph client.

The approved Android TV presentation is queue-first: a left rail, compact queue cards,
continuation, and artwork browsing below. The actual native UI must match the reviewed
presentation; a diagnostic screen is not a substitute. Keep inputs and dialogs bounded,
never screen-wide. Plex onboarding uses device-link authorization, Home profiles, and
automatic server discovery; do not expose token entry to viewers. Do not claim native
4K UI, verified offline Plex Home switching, faithful ASS rendering, native QueuePilot
starts, tone-mapping quality, or passthrough without actual device evidence.

Run Android lint, unit tests, assembleDebug, and emulator smoke checks. Model tests must
prove restart/resume and profile isolation. Do not store generated APKs or build caches in
Git. Register and merge verified PRs, run the workspace personal-detail scanner, and remove
merged worktrees. Do not install or change an active TV merely to run tests; use the emulator.
