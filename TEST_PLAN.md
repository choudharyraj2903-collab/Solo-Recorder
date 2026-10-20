# Testing & release acceptance

## Automated tests

The GitHub Actions job must pass all before it uploads an installable APK:
1. `python3 tools/verify_project.py` checks XML, permissions, backup policy, non-exported service, no internet, source invariants.
2. `gradle :app:testDebugUnitTest` runs JUnit state-machine, filename sanitation, dimensions, bitrate tests.
3. `gradle :app:lintDebug` runs Android lint static analysis.
4. `gradle :app:assembleRelease` compiles the production Android code.
5. `zipalign` followed by `apksigner sign` and `apksigner verify` generates and validates an **installable release APK**.

## Manual phone acceptance (must be performed on a real Android 10+ device)

| ID | Scenario | Expected result |
|----|----------|----------------|
| M01 | Install APK, start app offline/airplane mode | App opens without login or network |
| M02 | First run, microphone OFF, tap Start, grant capture | Ongoing recording notification; file recorded |
| M03 | Pause for 10 seconds, resume, then stop after another 10 seconds | One playable MP4; paused time not part of video |
| M04 | Press Pause and Resume from notification shade | Works without reopening the app |
| M05 | Press Stop from notification shade | Final MP4 appears in Movies/SoloRecord and gallery |
| M06 | Microphone ON and grant Android permission | Narration audible and synced with screen video |
| M07 | Deny microphone permission | No recording without explicit choice to disable microphone |
| M08 | Deny screen sharing dialog | Nothing recorded; can try again |
| M09 | Deny notification permission (Android 13+) | In-app pause/stop remains available; warning displayed |
| M10 | End capture using Android recording chip or lock screen (15 QPR1+) | Service stops and either saves valid MP4 or deletes invalid output |
| M11 | Capture 720p/30 then 1080p/30 | Output metadata roughly matches selected long edge (never upscale) |
| M12 | Capture 1080p/60 on supported device | Smooth output; if encoder rejects, clean error + no corrupt media |
| M13 | Capture a portrait screen and a separate landscape screen | Both produce correct-orientation videos (don't rotate mid-session) |
| M14 | Rotate device during recording | May letterbox/stretch. **Known limitation:** orientation changes are not dynamically reconfigured |
| M15 | App sent to background while service runs | Service continues; Android notification provides controls |
| M16 | Stop immediately (under 1 second) | App survives; invalid MP4 is discarded |
| M17 | Start twice rapidly | Only one active session; permission obtained once per session |
| M18 | Enter prefix `../../My Video` | Saved filename is sanitized; no paths/traversal |
| M19 | Low storage and denied storage writes | Clear failure, no app crash; output cleaned up where permitted |
| M20 | Record DRM/secure content | Secure content not captured (Android restriction) |
| M21 | Leave one recording running for 20+ minutes | Stable final MP4, reasonable thermals/storage on device |

**Device verification is pending until you install the APK.** CI tests and structural checks do not simulate screen capture, hardware encoding, or the device-specific foreground-service behavior. Do not treat an APK as real-device-verified based only on green CI.
