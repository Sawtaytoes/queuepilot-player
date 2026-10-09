# Native QueuePilot playback sessions

Status: proposed contract, not an implemented API. QueuePilot's current `/api/play` route
publishes a command for an external device. Calling it from this client would not provide
native playback and can affect an active room.

## Keep the existing engine

QueuePilot owns queue eligibility, selection, exact queue IDs, provider profile binding,
and completion policy. The player consumes an engine-produced plan and reports events.
Do not copy the queue selector into the TV app or choose a profile from its display name.
A queue remains people plus one activity, without a newly invented queue name.

Separate plan creation from external transport. The current ADB/Companion transport can
continue alongside a native transport, with the same selection and history decisions.
A native session must not send room power, playback, or profile commands to another app.

## Proposed request and response

A session start identifies the exact queue, enrolled player, resolved provider profile,
and client capabilities. The server validates all of them before returning a plan.
The plan contains:

- Session ID and expiry, queue revision, provider server identity, and profile identity.
- Ordered positions with their own stable position keys and unchanged provider media IDs.
- Resume offset, permitted direct-play or server-transcode sources, source credential
  scope, audio/subtitle choices, cast, and intro/credit markers when available.
- Artwork sources and requested pixel dimensions for the actual rendered destination.
- A continuation mechanism governed by the engine, rather than unrestricted browsing
  credentials or an entire account token roster.

Use authenticated requests with bounded, expiring access. Tokens must never appear in
logs, crash reports, shared screenshots, or portable plaintext metadata. A player credential
must not silently acquire owner privileges. Authenticated media requests must keep their
credentials on the enrolled origin or use separately scoped credentials for an explicitly
approved media origin.

Progress events include an event ID, session ID, position key, playback position, and a
monotonic client sequence. Accept retries idempotently. Stop, pause, failure, and completion
are distinct events. Preserve QueuePilot's configured provider-history versus queue-history
policy; a player must not set watched state merely because it opened a file.

## Ordering without replacing Plex

An overlay can initially store positions outside Plex and resolve each provider ID through
Plex's normal API. Episode 10 retains its metadata and number; the OVA has an independent
position between Episodes 9 and 10. The season grid and Continue action use this same
sequence, including after restart.

A client-local overlay is sufficient for a design experiment. Sharing the order among
family devices requires durable QueuePilot-side storage or synchronized portable manifests.
This does not require modifying Plex's database or replacing its scanning model first.
NFO and ordering-file authoring belong to later metadata work.

## Cached queues and offline use

Cache a versioned, profile-scoped QueuePilot snapshot with its server identity and access
policy. It may preserve queue information and a previously granted plan when the player is
away. Cached information does not supply inaccessible media or grant new privileges.
Device-local selection from a snapshot needs a deliberate reconciliation policy so it does
not drift from engine eligibility or silently overwrite newer server state.

Store offline progress in a separate append-only event log and reconcile through the same
idempotent session API. Local-network playback during an internet outage and downloaded
media playback without any server connection are different test cases.
