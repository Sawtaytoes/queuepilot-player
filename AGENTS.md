# QueuePilot Player

## Prefer MCP, APIs, and CLIs over computer use

For service access and routine reads or writes, prefer an available MCP tool, a
supported HTTP API, or the service's CLI over browser or desktop interaction.
Check the project's runbooks, existing tools, and documented endpoints first.
The presence of browser tools is not a reason to choose computer use. A missing
MCP tool or browser login does not prove that an API or CLI is unavailable.

Use computer interaction when the task needs a visual check, a browser test, a
UI-only operation, or authentication that the available programmatic routes
cannot perform. Keep that interaction narrow, then return to MCP/API/CLI calls
for the rest. If no documented route exists, check the app's existing HTTP
requests before assuming repeated clicks are necessary; do not bypass access
controls. State the specific limitation when computer use is required.

This preference does not grant new access, widen credentials or permissions,
replace a more specific service route, or remove required visual verification.

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
never screen-wide. Browsing/settings options use app-styled side panels. Playback menus
anchor to their launching controls (above the bottom bar when launched there), never an
unrelated browsing sidebar. Keep the controls visible and restore focus on dismissal. Plex onboarding uses device-link authorization, Home profiles, and
automatic server discovery; do not expose token entry to viewers. Do not claim native
4K UI, verified offline Plex Home switching, faithful ASS rendering, native QueuePilot
starts, tone-mapping quality, or passthrough without actual device evidence.

Keep every source file at 400 lines or fewer (`tools/size_check.py`, enforced in CI). Add code
to a new focused file rather than growing a large one; a file listed in
`tools/size-exceptions.txt` may shrink but never pass its ceiling. New and redesigned screens are
Kotlin with Compose for TV; moving existing Java is a pure move, and converting it to Kotlin is
a separate commit. `python3 tools/move_check.py <base>` compares each moved member of `MainActivity`
with the base, token by token and in order, with each member reference resolved to the class that
declares it; its docstring lists what else it allows and what it cannot see. It does not replace
the build, tests, and emulator checks. Code decisions are in `docs/decisions/`.

Run Android lint, ktlint, unit tests, the debug and release builds, and the emulator smoke
checks, which test the release APK that TVs get. Model tests must
prove restart/resume and profile isolation. Do not store generated APKs or build caches in
Git. Register and merge verified PRs, run the workspace personal-detail scanner, and remove
merged worktrees. Do not install or change an active TV merely to run tests; use the emulator.

This repository is public on GitHub; `.forgejo/workflows/publish.yml` pushes Forgejo's `main`
there (never tags or other branches). Commit as
`Sawtaytoes <3948069+Sawtaytoes@users.noreply.github.com>`: set `user.name` and `user.email` in
the checkout before the first commit. A rebase keeps each commit's original author, so reset
any other author with `git commit --amend --no-edit --reset-author`. Merges are fast-forward
only: squash and rebase the branch locally on `origin/main`, then merge with the
`fast-forward-only` style. A merge, squash, or rebase made by Forgejo carries another address,
and the publish job refuses every commit on `main` that does not use the no-reply address
([decision](docs/decisions/2026-11-25-the-public-github-copy-is-pushed-from-forgejo-and-every-commit-uses-the-no-reply-address.md)).

## Independent adversarial review

Before integrating any change in this repository, including an authorized direct push
to the default branch, obtain review from at least one other agent that did not author
the changes. This includes code, tests, dependencies,
configuration, CI, and documentation; scale review depth to the risk. For risky designs,
seek an early challenge as well as review of the final diff. Give the reviewer the
requirements, applicable instructions, base/head SHAs, full diff, and test evidence.
Ask it to find correctness defects, regressions, edge cases, security problems, and
missing verification; a no-findings result is valid. Reviewers inspect and report;
they do not edit, merge, or deploy.

Resolve findings with evidence, fix confirmed defects, and re-run affected checks.
Unresolved material concerns block integration. Re-review substantive changes after fixes
or rebases so the final head is covered. Record reviewer identity, reviewed SHAs,
findings (or none), resolutions, and verification on the PR; for a direct-push workflow,
use a tracked review note instead. Self-review and passing
CI do not replace independent review. If the reviewer is unavailable, retry or use
another authorized agent; report the gate instead of silently waiving it. Only an
explicit owner instruction can waive the review, with its scope recorded in the same
review evidence.
Existing required checks, merge rules, and deployment verification still apply.
