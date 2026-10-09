# Roku evaluation

A Roku application uses BrightScript and SceneGraph, so the Android APK and a Flutter
UI cannot be reused as its runtime. Share the QueuePilot protocol, ordering model,
provider adapter contract, fixture media, and behavioral cases across separate clients.

The official Jellyfin Roku client is an existing open-source foundation worth evaluating
when selecting a Jellyfin backend. Its player and remote navigation are more relevant than
starting a Roku decoder integration from nothing. No fork or Roku application is built in
this first Android experiment. [Jellyfin Roku source](https://github.com/jellyfin/jellyfin-roku).

Roku's official Cloud Emulator runs virtual players and TVs through a web UI and REST
APIs. Availability is a phased developer-account rollout. It offers a five-hour trial;
continued access requires an AWS Marketplace subscription. It is hosted by Roku, not a
local container. UI and protocol tests can use it if access is available, while
hardware-dependent performance, audio output, HDR conversion, and memory still need the
physical device. Do not purchase a subscription or request partner access automatically.
[Roku Cloud Emulator](https://developer.roku.com/dev/docs/rce).

A physical Roku can sideload a ZIP through its developer-mode application installer.
The owner must enable developer mode on the device and supply its developer password
before a first deployment. Remote automation can then drive the device rather than
requiring a TV to be observed for every basic navigation test.
[Roku developer setup](https://developer.roku.com/dev/docs/developer-setup).

Keep the existing Plex Roku path available while the replacement is evaluated. A Roku
Stick+ connected to an SDR display needs a supported server-transcoding and tone-mapping
path for incompatible HDR or codec combinations; a Shield's on-device behavior does not
establish that capability for Roku.
