# Lixen Rings - Development Notes

## Build Constraints
- **DO NOT** modify any config files: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle-wrapper.properties`, `AndroidManifest.xml`, `proguard-rules.pro`
- **DO NOT** attempt to build, sync, or run gradle commands
- Output only Kotlin source changes to files in `app/src/main/kotlin/`

## Architecture
- Single-activity app with custom `GameView` (Canvas-based rendering)
- `GameState` holds ring angles and game flags
- All drawing/input in `GameView.onDraw()` / `onTouchEvent()`

## Coordinate System
- `centerX`, `centerY` = screen center (ring origin)
- `radii[1..8]` = ring radii, `radii[0]` = center circle
- `ringWidth` = stroke width and spacing unit
- Angles: 0° = top (12 o'clock), clockwise positive

## Touch Control Contract
- Tap = cycle selected ring
- Drag horizontal = rotate selected ring (+ half-speed propagation to adjacent)
- Drag vertical = change selected ring (up = outer, down = inner)
- Mode must lock on gesture start, no switching mid-drag

