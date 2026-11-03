# Image to GIF & Video (Android)

Native Kotlin app. Pick a folder (or many images), choose seconds per image, and it builds an
MP4 (optionally with music) and/or a GIF. Images are processed one at a time and streamed to
the encoders, so 1000+ images don't fill memory.

## Open and run
1. Open this folder in Android Studio (Koala or newer) and let Gradle sync.
2. Run on a device or emulator with Android 10+ (minSdk 29).
3. No permissions are needed. Output goes to `Movies/ImageToGifVideo` and `Pictures/ImageToGifVideo`.

## Features
- Folder picker or multi-image picker, natural sort (img2 before img10)
- EXIF rotation handled, images letterboxed on 16:9, 9:16 or 1:1 frames
- MP4: H.264, 480p / 720p / 1080p, any seconds-per-image
- Music: any audio file the phone can decode; trimmed or looped to match the video length
- GIF: width setting and "every Nth image" to keep file size sane (GIF cannot carry sound)

## Notes
- 1000 images at 0.5 s is an 8+ minute video. A GIF of 1000 frames can reach tens of MB; use a
  smaller width or every-Nth-image.
- GIF delays below 0.02 s are clamped by viewers.
- GIF uses a fixed 256-colour palette with dithering for speed.
- Conversion runs while the app is open (screen kept on). For very long jobs, moving the
  work into a foreground service is the natural next step.
