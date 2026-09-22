# Phase 0 — Engineering Report

Status: Accepted as the implementation baseline.

## 1. ADR
The product is an Android-native IPTV client for phones, tablets, Android TV and Google TV. The media problem is two independent IPTV subscriptions: one supplies video and one supplies commentary/audio.

Decision: use native Android/Kotlin with Jetpack Compose for presentation and Media3/ExoPlayer as the first media-engine prototype. Video and audio are independent ExoPlayer instances owned by a PlaybackOrchestrator. The audio player must have video track selection disabled and must be verified with renderer/analytics evidence. No shared mutable player state is permitted.

The architecture remains replaceable at the media boundary: if prototype evidence shows Media3 cannot meet the synchronization or codec requirements, reassess against libmpv/MediaKit/LibVLC before building the full UI.

## 2. Technology comparison
Native Android/Compose gives direct access to Android lifecycle, audio focus, MediaSession, hardware decoding, TV focus/D-pad, Media3 and diagnostics without a cross-platform bridge. Flutter remains viable for ordinary UI, but a native media layer would still be required. Therefore native Android is selected for this product.

## 3. Media engine comparison
Media3/ExoPlayer: strong Android integration, HLS/progressive HTTP support, hardware decoder integration, live playback controls, renderer/track-selection APIs, MediaSession integration and a comparatively small Android-native maintenance surface.

libmpv/MediaKit: broad codec/container coverage and mature playback behavior, but larger native integration and APK/ABI/maintenance cost.

LibVLC: broad format support and mature playback, with a heavier native footprint and integration surface.

MediaCodec: low-level control but requires implementing much of the player pipeline, buffering, timestamp handling and recovery.

Decision for Phase 1: Media3/ExoPlayer. This is a prototype decision, not a claim that it has already passed real-world dual-source sync.

## 4. Final technology selection
Kotlin + Android SDK + Jetpack Compose + Media3/ExoPlayer + OkHttp + coroutines. Java 17. Feature-first Clean Architecture. Android Keystore/encrypted storage will be added before credentials are persisted.

## 5. Synchronization architecture
SyncController must model independent video and audio clocks. Drift is AudioClock - VideoClock. The implementation must distinguish:
- player/timeline position;
- live-window position/live edge where available;
- observed startup offset;
- buffering/stall state;
- reconnect generation.

Small drift is corrected without stopping video. Large drift triggers deterministic audio resynchronization. Fixed sleeps are forbidden. Thresholds are test parameters and must be documented from tests, not treated as universal constants.

Important limitation: two independent IPTV services may expose unrelated timestamp origins/live edges. currentPosition alone cannot prove synchronization. If a common live clock cannot be derived reliably, the system must expose that limitation and reassess the media architecture/content-alignment strategy instead of pretending that two players are synchronized.

## 6. Audio-only architecture
The audio account supplies live channels. When an audio stream contains video+audio, the audio player disables video track selection before playback. Verification must inspect selected tracks/renderers/decoder information and resource usage; the presence of an Audio button is not proof.

## 7. Flutter/native boundary
No Flutter boundary is required because native Android is selected. If cross-platform UI is introduced later, the media/orchestration layer must remain native and exposed through a typed interface; media state must not be duplicated in Flutter.

## 8. Project structure
- core: common models, result/error types, dispatchers and security primitives
- data: Xtream clients, repositories, cache
- domain: account/channel/pairing/playback use cases
- media: VideoEngine, AudioEngine, PlaybackOrchestrator, SyncController, diagnostics
- features: onboarding, home, live, player, pairings, settings, diagnostics
- presentation: Compose design system and navigation
- tv: Android TV focus/navigation behavior
- tests: unit, media, sync, integration and end-to-end fixtures
- docs: engineering reports and validation records

The current repository contains an earlier prototype plus legacy Java sources; these are not considered proof of production readiness and must be consolidated/removed during implementation.

## 9. Dependency list
Initial prototype:
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- compileSdk/targetSdk 35
- minSdk 23
- Java 17
- Media3 1.11.1
- OkHttp 4.12.0
- Kotlin coroutines 1.10.2
- Jetpack Compose Material 3

Exact versions must be kept reproducible in Gradle and reviewed before release.

## 10. Estimated/measured size impact
A final size measurement is not available yet. The current repository has a debug APK workflow, but that is not a release-size measurement. Release validation will measure signed APK/AAB size per ABI, inspect dependency contribution, enable R8/resource shrinking, use ABI splits where appropriate, and avoid duplicate media engines/codecs.

## 11. UI Design System
Dark-first, minimal, premium, fast.
Background #080A0F
Secondary #10141C
Card #151A23
Elevated #1C222D
Primary #FFFFFF
Secondary text #A7AFBF
Muted #697386
One accent plus semantic success/warning/error states.

Spacing: 4/8/12/16/24/32dp.
Radii: 8/12/16/20dp.
Inputs ~52dp, buttons ~48dp.
Typography: display 32–36, title 22–26, section 18–20, body 14–16, caption 12–13.
RTL/LTR must be native and tested with Arabic, English, mixed text, numbers, URLs and usernames.

## 12. Screen map
Splash -> Welcome -> Connect Video IPTV -> Connect Audio IPTV (skippable) -> Home.
Home -> Live TV / Movies / Series / Settings.
Live TV -> channel list -> player.
Player -> Audio Source -> Audio controls -> Save Pairing.
Settings -> Playback / IPTV Accounts / Audio / Video / Network / Appearance / Language / Privacy / About / Diagnostics.
Android TV adds D-pad/focus behavior and Favorites navigation.

## 13. Playback state machine
Each engine has independent states:
IDLE, CONNECTING, BUFFERING, PLAYING, PAUSED, STALLING, RECONNECTING, ERROR, STOPPED.
PlaybackOrchestrator owns the combined session and transition rules. UI observes state; it does not manipulate players directly.

## 14. Xtream data model
Separate:
- VideoXtreamConfig(serverUrl, username, password)
- AudioXtreamConfig(serverUrl, username, password)

Video account: account info, live categories/streams, VOD, series, EPG when available.
Audio account: live channels by default.
Repositories must lazy-load and cache metadata. Credentials must never appear in logs.

## 15. Security model
Credentials are kept out of source control and logs. Production persistence uses Android Keystore-backed encrypted storage. URLs containing usernames/passwords must be redacted before diagnostics/analytics. CI uses GitHub Secrets only for build-time secrets that are genuinely required. Signing keys and IPTV credentials are never committed.

## 16. Testing strategy
Unit: state machine, Xtream URL parsing, redaction, repository mapping, sync math.
Media: HLS, MPEG-TS, HTTP, H264, HEVC, AAC, MP3, AC3, E-AC3; video-only, audio-only and video+audio.
Sync: startup offset, steady drift, hard resync, buffering, reconnect, channel switching and stale-callback protection.
Network: fast/slow Wi-Fi, 4G, high latency, packet loss, disconnect, timeout.
UI: onboarding, login navigation, audio-source selection, settings, RTL/LTR, TV focus.
Release: signed release build, install, launch, smoke test, artifact integrity and size report.

## 17. CI/CD strategy
CI must checkout, resolve dependencies, run formatting/lint/static analysis, unit tests, critical media/sync tests, build debug and release artifacts, validate APK/AAB, produce a size report, run secret scanning and upload artifacts. Critical test failure fails the build. A clean clone must build reproducibly.

## 18. Prototype implementation plan
Gate A: prove independent video and audio playback.
Gate B: prove audio-only decoding.
Gate C: prove initial synchronization without fixed sleeps.
Gate D: prove continuous synchronization and deterministic hard resync.
Gate E: prove independent audio/video reconnect.
Gate F: prove A/A, B/A, B/B, C/C and rapid channel switching without stale sessions or leaks.

Only after Gates A–F pass should the full product UI and feature set be expanded.

## Phase rule
Every phase follows:
IMPLEMENT -> BUILD -> TEST -> VERIFY -> DOCUMENT.

No compile-only success is accepted as media success. No arbitrary delays, random restarts, giant caches, global mutable player state or undocumented synchronization hacks are accepted.

## Current status
Phase 0 report is the baseline. The repository still contains prototype/legacy media code and therefore is NOT yet Definition-of-Done. The next implementation step is consolidation around the documented architecture and execution of the dual-playback gates before full UI expansion.
