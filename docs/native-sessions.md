# Native QueuePilot playback sessions

Version 0.3.0 implements the direct-play portion of this contract. It requires a QueuePilot
server with the native session API. The existing external-player `/api/play` path remains
separate; the TV client never invokes it.

## Selection and identity

`GET /api/native/queues?people=...` returns Plex queues and a projected people roster.
The server uses the existing audience predicate, including required people and group minimums.
Selecting people narrows queues; it does not choose the playback identity.

`POST /api/native/sessions` supplies `{setId}` and `X-Plex-Profile-Token`. Plex verifies the
actual Home identity, then the server requires the queue binding to match before selecting.
The plan preserves full engine-selected batches, source server IDs, resume offsets and
section windows. It returns an expiring session capability and ordered media IDs without
Plex credentials. The client resolves each source server with the selected profile's scoped
credential and streams directly. Cloud tokens never go to a media server.

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
