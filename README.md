# SoloRecord — personal Android screen recorder

Lightweight, watermark-free, offline-first native Android app intended for **your personal use only**. Written in Kotlin with the Android SDK and no third-party runtime dependencies.

## Included in v1

- Explicit Android screen-capture consent on every recording.
- Start, **pause, resume, stop**; notification controls and in-app controls.
- H.264 MP4 files in `Movies/SoloRecord` (Gallery), one video across pause/resume.
- 720p or 1080p long-edge maximum, 30 or 60 FPS, hardware encoder (device-dependent).
- Optional microphone narration; microphone defaults **OFF**.
- Personalizable, sanitized filename prefix and one-tap open last recording.
- No watermarks, ads, subscriptions, internet permission or account.
- Local Android MediaStore saving, non-exported service, no overlay/accessibility privilege.

## Supported devices

**Android 10 (API 29) or newer.** AOSP and manufacturer restrictions vary by phone. USB debugging, root, Play Store publication and an account are not needed for installing the APK.

## Build your APK in your own **private** GitHub repo (recommended)

1. Extract the supplied project ZIP. Open the `SoloRecord/` folder; the repo root should contain `app/`, `settings.gradle.kts`, and the **hidden** `.github/workflows/android.yml` file.
2. Create an empty **private** GitHub repository, for example `solo-record-private`.
3. Push the project **contents** (including `.github`):
   ```bash
   git init
   git add .
   git commit -m "Private SoloRecord v1"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/solo-record-private.git
   git push -u origin main
   ```
4. In GitHub open **Actions → Build and test installable APK → Run workflow**. A push to main also triggers it. Enable Actions on your private repo if prompted.
5. **Only after every step turns green**, open the workflow run, scroll to **Artifacts**, download `SoloRecord-installable-APK.zip` and extract its `SoloRecord-v1.0.0-release.apk`.
6. Copy the APK to your Android phone and open it using the Files app. Grant permission to **Install unknown apps** to that Files app when Android asks. Disable the permission again after installation.
7. Open SoloRecord, select settings, tap **Start recording**, grant Android's screen-capture consent, navigate to your content, and stop from the ongoing notification or the app.
8. Open **Gallery → Albums → SoloRecord**, or `Movies/SoloRecord` in your Files app. Or tap **Open last recording** in SoloRecord.

**Security:** download the APK only from your own workflow run, not from unrelated websites. The signing key is generated at build time and discarded. A later workflow run produces a different signature, so Android will require uninstalling the earlier version before installing the newer one. Recordings in `Movies/` should remain in shared media storage, but your app preferences will reset after uninstall. Back up important videos independently.

## Alternative: Android Studio

Open the project in Android Studio with JDK 17 and Android SDK Platform 36 / build-tools 35.0.0. This project intentionally doesn't bundle Gradle wrapper binaries; the GitHub workflow installs pinned Gradle 8.13. In a terminal with Gradle 8.13 installed, run:

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Android Studio can also generate a debug APK from **Build → Build APK(s)** after Gradle sync. For a reproducible installable release APK, use the GitHub workflow (it also signs and verifies the APK).

## Key limitations (v1)

- **No internal/system audio capture** yet; only microphone or silent video. Android playback audio requires a separate `AudioPlaybackCapture`/encoder pipeline and applications may refuse capture.
- No floating overlay. Reliable pause, resume and stop controls are in the notification and in the app, eliminating intrusive overlay permission.
- No video editing or merge tool. Pause/resume edits out pause time without splitting files.
- Changing device orientation during a recording may cause letterboxing/distortion. Start a new recording after rotating. The phone's system capture picker can allow app-window sharing with different dimensions.
- Android may automatically end capture when the screen locks, starting from Android 15 QPR1.
- DRM-protected screens may appear black. SoloRecord cannot bypass Android screen security.
- Video encoding, FPS and mic compatibility require physical device testing. Recording very briefly may produce an invalid MP4; the app discards it.
- The videos are saved to shared Gallery storage, not encrypted private storage.

See [`PRIVACY.md`](PRIVACY.md) and [`TEST_PLAN.md`](TEST_PLAN.md) for detailed threat model and verification checklist.

## Implementation map

| File | Responsibility |
|------|----------------|
| `MainActivity.kt` | Native UI, permissions, user consent, video open |
| `RecorderService.kt` | Foreground service, MediaProjection/MediaRecorder, pause/resume, MediaStore |
| `RecordingPolicy.kt` | Pure recorder state machine, safe sizes/bitrate, prefix sanitation |
| `Settings.kt` | Device-only SharedPreferences |
| `RecorderStatus.kt` | Volatile in-process UI snapshot |
| `.github/workflows/android.yml` | Tests, lint, compile, sign and verify installable release APK |

## Development verification status

The source contains unit tests and CI build/signing steps, but an actual Android build and device video-capture test must run on GitHub Actions / your phone. This project source must not be described as hardware-verified before those checks pass.
