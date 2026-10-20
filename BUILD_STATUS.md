# Verification status — source package

Date: 2026-10-20

## Passed locally in the source-generation environment

- `tools/verify_project.py`: **PASS**, manifest permissions, absence of Internet/full-storage permission, non-exported service, Android XML parsing, source lifecycle checks, GitHub workflow present.
- `RecordingPolicyTest.kt`: **10/10 tests passed** when executed against the actual production `RecordingPolicy.kt` using local JVM compilation and minimal JUnit API shims (Android SDK and Gradle were not available here).
- `tools/PolicySmoke.kt`: **19 JVM assertions passed**.
- Project ZIP archive structure and integrity verified after creation.

## NOT performed locally — must pass in GitHub Actions

- Android Gradle plugin dependency download, complete project compilation, Android lint, SDK-aware JUnit run, assembly and APK signing. No Android SDK, Gradle or external Maven network is available in the source-generation environment. Therefore **no APK is included in the source ZIP**, and no claim is made that this source has already produced a working APK.
- GitHub workflow runs `testDebugUnitTest`, `lintDebug`, `assembleRelease`, `zipalign`, `apksigner sign`, and `apksigner verify`. It uploads the APK only if all stages succeed.

## NOT performed — needs your physical phone

- Android capture consent, MediaProjection foreground-service lifecycle, encoder support, pause/resume audio sync, lock-screen interruption, long recordings and Gallery integration. See `TEST_PLAN.md` for 21 manual acceptance cases.

If CI fails, share the full red-step error log. Do not sideload an APK from a failed workflow.
