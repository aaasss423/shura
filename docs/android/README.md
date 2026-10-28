# Android

The application ID is `app.shura.manga`, with Android minimum API 26 and target API 34. `shura-source-api` defines manga/chapter/page operations; `shura-source-host` registers source adapters and provides a bounded HTTPS downloader with per-hop host checks and SHA-256 output. Extensions are loaded only after APK digest and publisher checks; they execute in the app process, so a valid signature does not establish that an extension is benign.

On startup, the built-in source is registered immediately. Repository seeding, extension loading, and catalog reads run on `Dispatchers.IO`; failures are surfaced in the repository settings rather than blocking or terminating the initial screen. Chapter pages load progressively, and decoded image bitmaps use a bounded, heap-sensitive memory cache.

Android unit and instrumentation tests require the Android SDK. CI runs unit tests and an emulator smoke test that launches `MainActivity`, then runs the real extension-load test; emulator logs and JUnit reports are retained as workflow artifacts. Emulator tests use a 2 GiB guest RAM setting, which is a constrained-emulator check rather than proof of performance on every 2–3 GiB physical device.

Release builds are non-debuggable and require a production keystore supplied outside the repository through `SHURA_RELEASE_KEYSTORE`, `SHURA_RELEASE_KEY_ALIAS`, `SHURA_RELEASE_STORE_PASSWORD`, and `SHURA_RELEASE_KEY_PASSWORD`. The build deliberately refuses release tasks when those values are absent. Never commit the keystore or passwords, and do not distribute `assembleDebug` output as a release.

No signing key was available for this maintenance build. The published `v0.33.0` APK's exact signature and provenance have not been independently established, so this repository cannot claim that a replacement is safe to install over it or that Google Play Protect accepts it.
