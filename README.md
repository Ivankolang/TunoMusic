# Tuno Music

Tuno Music is an Android music player MVP built with Kotlin, Jetpack Compose and AndroidX Media3.

## Current MVP
- Scan local audio files using Android MediaStore.
- Search the local music library.
- Play, pause, skip next and previous.
- Continue playback through a MediaSessionService in the background.
- Open YouTube Music and Spotify in their official apps or websites.

## Build
Run:

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Platform integration
This MVP opens official music apps. It does not extract streams or bypass subscriptions, ads, DRM, or platform restrictions. In-app playback requires supported official APIs and authorization.
