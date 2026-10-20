# SoloRecord privacy and security model

- **No Internet permission:** no capability to make ordinary network requests.
- **No accounts, analytics, ads, cloud or embedded third-party SDKs.**
- **Android system consent is required for each screen-capture session.** Permission is not cached.
- **Microphone is OFF by default** and requires explicit Android microphone permission to enable.
- **The only background work is a visible foreground recording service.** The service cannot be invoked directly by other apps (`exported=false`). Android displays capture status and can revoke capture.
- **No broad storage permissions.** Uses Android MediaStore on Android 10+ for `Movies/SoloRecord`.
- **No overlay permission, accessibility service, rooting or privileged APIs.** Controls use the ongoing notification or app.
- **No automatic recording at boot;** no intent listener for incoming links/remote commands.
- **Backup is disabled** for app preferences; recording files themselves are in the phone's shared Gallery/Movies storage, **not encrypted**. Other apps with media access may see the saved videos. Move sensitive recordings into an encrypted vault if required.
- **The installable APK is signed using a fresh, short-lived CI-generated keystore**, not a release key checked into source control. That key is discarded after every run. APKs from different workflow runs **cannot update each other** without first uninstalling the old APK. Treat this as a personal one-time build, not a distribution signing scheme.
- **Build provenance:** download the APK only from the Actions run in **your own private GitHub repository**. Verify that the job passed unit tests and APK signature verification. Don't install APKs from random third-party mirrors.
- **Restrictions:** DRM/FLAG_SECURE-protected screens are not captured; Android 15 QPR1+ may end capture at lock screen; audio recording of other apps is intentionally **not implemented** in v1.
