# TapeCam — retro VHS camcorder for Android

A camera app that films and shoots through a live 1980s/90s camcorder look:
tape jitter, colour bleed, a rolling tracking band, head-switching noise at the
bottom edge, grain, dropouts, scanlines, and a burned-in camcorder display
(STBY / ● REC, tape counter, SP, battery, AM/PM time and date stamp).

## Build & run
1. Open the `TapeCam` folder in Android Studio (Ladybug or newer).
2. Let Gradle sync (Gradle 8.9, AGP 8.7, Kotlin 2.0, JDK 17).
3. Run on a physical device (Android 8.0+). Emulators work but the camera is fake.

## Controls
| Control | What it does |
|---|---|
| Red button | Start/stop recording (video mode) or take a photo (photo mode) |
| VIDEO / PHOTO | Switch capture mode |
| FLIP | Switch front/back camera |
| TRACK | Fire a tracking-error glitch burst |
| DATE (tap) | Toggle the date/time stamp |
| DATE (long-press) | Set any date & time for the stamp (e.g. 1991). "Today" resets |
| FX slider | Effect strength, 0 = clean, 100 = worn-out tape |

Videos go to **Movies/TapeCam** (960×720, 30 fps, H.264 + AAC mono);
photos go to **Pictures/TapeCam** (1440×1080 JPEG). Everything you see in the
viewfinder — effect and OSD — is burned into the saved files.

## How it works
- `MainActivity` — UI, permissions, CameraX binding, record/photo flow.
- `VhsRenderer` — own EGL context on a GL thread. CameraX writes frames to a
  `SurfaceTexture`; each frame is drawn through the shader to the screen and,
  while recording, to the `MediaRecorder` input surface. Photos render into an
  offscreen framebuffer.
- `VhsShaders` — the single-pass tape effect (YIQ chroma smear, jitter, noise…).
- `OsdOverlay` — draws the camcorder display into a bitmap texture.
- `VhsRecorder` / `MediaSaver` — encoding and saving through MediaStore.

## Ideas for next steps
- Lo-fi audio (band-limit + hiss) via MediaCodec instead of MediaRecorder.
- Import a video from the gallery and re-process it.
- More looks: Hi8, Betamax, 8mm film, black-and-white security cam.
- Pinch-zoom with an on-screen "W ▮▮▮▯ T" zoom bar.
