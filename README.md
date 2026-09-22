# AudioMix IPTV

Native Android IPTV client focused on independent video and audio sources.

## Architecture
- Native Kotlin + Jetpack Compose
- AndroidX Media3 / ExoPlayer
- Independent video and audio player instances
- Xtream Codes client
- PlaybackOrchestrator
- SyncController
- Audio path disables video track selection

## Critical engineering gate
Real-world verification must cover two independent live Xtream streams, audio-only decoding, synchronization, reconnect behavior, and deterministic channel switching.

## Build
Use Android Studio or Gradle with JDK 17.

## Security
Credentials must not be committed or logged. Production credential persistence will use Android Keystore-backed encrypted storage.
