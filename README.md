# Lixen Rings

A concentric ring alignment puzzle for Android.

## Mechanics

- 8 rotatable rings with notches
- Rotating a ring propagates to adjacent rings at half speed
- Goal: align all notches to a single line

## Controls

- **Tap**: Cycle selected ring (1→2→...→8→1)
- **Drag horizontal**: Rotate selected ring
- **Drag vertical**: Switch to inner/outer ring

## Build
```bash
# Debug APK
./gradlew assembleDebug

# Release AAB (requires signing config)
./gradlew bundleRelease
```

## Requirements

- Android SDK 26+ (Android 8.0)
- Kotlin 1.9.20
- AGP 8.13.2

## License

MIT
