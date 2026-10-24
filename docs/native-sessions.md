# Native QueuePilot playback sessions

Version 0.3.0 implements the direct-play portion of this contract. It requires a QueuePilot
server with the native session API. The existing external-player `/api/play` path remains
separate; the TV client never invokes it.

## Selection and identity

`GET /api/native/queues?people=...` returns Plex queues and a projected people roster.
The server uses the existing audience predicate, including required people and group minimums.
Selecting people narrows queues; it does not choose the playback identity.

`POST /api/native/sessions` supplies `{setId}`, `X-Plex-Profile-Token`, and
`X-QueuePilot-Device` (the install's own identifier). Plex verifies that the token belongs to the
server owner's Plex Home; the TV is authorized as a household device, and its profile does not
have to match the queue. The server plays the queue as the profile the queue names
(`requires_profile`, else the routing binding, else the owner). The plan preserves full
engine-selected batches, source server IDs, resume offsets and section windows. It returns an
expiring session capability, ordered media IDs, and `grants`: the queue profile's
server-scoped Plex token and verified address for each server the items live on. The client
streams each item directly with that grant, so a queue plays as its own profile whichever
profile the TV has chosen; the chosen profile is used only outside queues. Cloud tokens never
go to a media server, and the grants are held in memory only.

A queue marked "Locked on TV" answers `423 {error, isLocked: true}` until the household TV PIN
is traded for an unlock token at `POST /api/native/unlock {pin}`. The client sends it as
`X-QueuePilot-Unlock` on later requests and forgets it when the app leaves the foreground. A
started session continues on its session key without asking again.

## History and continuation

Events carry an ID, increasing sequence, item index, state, position and duration. The client
sends `X-Native-Session-Key`, plus the same `X-Plex-Profile-Token` to renew the original grant
after a service restart. The server persists receipts for idempotent retries and sends Plex
timelines with the bound server token. Existing Plex completion verdicts and QueuePilot-owned
ledgers decide progress. Authored section boundaries have their own completion semantics.

`/continue` asks the same engine for the next eligible window after the current lineup ends.
Playback length and previously handed identities limit continuation. No ADB, Companion or
room power command is involved.

Pending native events are encrypted with Android Keystore and retried in order. Browse
snapshots and audience selection are cached. A snapshot cannot mint a new plan offline.
Expired or invalid pending grants are retained as failed records for later review. Active
lineup restoration across app termination and a viewer-facing failed-event recovery flow
remain future work. Direct library progress currently has no durable offline retry.

## Features beyond this release

Real story-order manifests, NFO editing, downloadable media, transcode negotiation,
intro/credit controls, scene-timed cast recognition and hardware playback verification remain
separate work. The fictional demo's Episode 9 → OVA → Episode 10 order remains a design test.
The API implementation and schema version 9 upgrade notes are documented in QueuePilot's
`docs/native-tv.md`.
