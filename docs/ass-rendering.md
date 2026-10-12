# Authored ASS rendering

The Android client keeps Media3 for playback and supplies a dedicated libass text
renderer for embedded ASS in Matroska. The extractor preserves the original ASS
header and packets and reads font attachments through Media3's protected EBML hooks.
There is no reflection, external player, server burn-in, or video transcode.

A dedicated worker owns libass. The playback thread feeds timed packets; rendering
returns cropped, premultiplied bitmap frames to an overlay aligned to the video.
Embedded fonts, style outlines, positioned signs, vector drawings and animation are
interpreted by libass. Other subtitle formats retain Media3's existing path. ASS in
other containers and external ASS downloads are not covered by this integration.

Track changes replace the libass track. Seeks invalidate stale output and pending
packets while retaining decoded events, so a long sign can survive a seek inside
its duration; libass handles the playback clock moving backwards. Subtitle
Off releases the renderer's track and clears the overlay. Font data is scoped to one
playback session, not installed globally. Attachments are limited to 128 fonts,
16 MiB per font and 96 MiB total. Events older than sixty seconds are pruned. A stream
with fonts placed after video clusters may initially use the fallback font until its
attachments are encountered; this implementation does not scan a second copy of the
movie for late attachments.

## Dependency provenance and reproducibility

`tools/native-sources.json` pins official upstream source commits. We build our own
JNI adapter and native binaries, rather than adopting an Android wrapper or opaque
prebuilt AAR. The origins reviewed are:

- [libass](https://github.com/libass/libass): independent MPlayer-era subtitle project,
  originally authored by Evgeniy Stepanov; official upstream maintainers and signing
  keys are recorded in its `MAINTAINERS` file. ISC license.
- [FreeType](https://freetype.org/history.html): David Turner, Robert Wilhelm and
  Werner Lemberg's font project. The build uses its FreeType License option.
- [HarfBuzz](https://harfbuzz.github.io/why-is-it-called-harfbuzz.html): FreeType-derived
  shaping project maintained by Behdad Esfahbod. MIT-style license.
- [GNU FriBidi](https://github.com/fribidi/fribidi/blob/master/AUTHORS): Dov Grobgeld's
  project, maintained through its 0.19 series by Behdad Esfahbod. LGPL 2.1 or later.
- Google Android NDK r28c supplies the Android compiler/runtime. Meson 1.8.3 supplies
  the build generator; Ubuntu's package repository supplies Ninja and pkg-config.

`tools/native-build.sh` builds ARM64, ARMv7 and x86 from these pinned sources, with two
workers and a persistent cache. Native outputs go only into ignored build directories.
The native bridge is source-available alongside this application; upstream license
texts are packaged in the APK. Keep the pinned source archives and per-ABI object files
with release artifacts so recipients can rebuild/relink the LGPL component. The app's
original Java/C code remains MIT; third-party code retains its own terms.

The synthetic fixture uses ABeeZee by Anja Meiners from the Google Fonts project,
under the included SIL Open Font License. It exercises an attached font, authored
outline, rotated positioned sign and animated movement without private media.

## Acceptance

Build, lint, unit tests and emulator checks are required. Compare the fixture to an
independent FFmpeg/libass reference. On a physical device compare the selected real
ASS track at the same timestamp to a known-good player, including seeking, Off/On and
an effects-heavy scene. A successful video start or readable plain text is insufficient.
Device evidence is recorded separately; do not infer fidelity from compilation.
