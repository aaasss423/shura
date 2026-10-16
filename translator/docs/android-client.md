# Android client

Experimental Beta. Not a product decision about any translation model: no model has
been selected, and this app does not select one.

## What it is

A native Android client for this platform's REST API. Four screens:

| Screen | Endpoint | Purpose |
| --- | --- | --- |
| Text | `POST /translate` | one line or paragraph, Arabic output |
| Chapter | `POST /translate/chapter` | a manga/manhwa page as one segment per line |
| Status | `GET /health`, `/engines`, `/knowledge`, `/memory`, `/glossary` | server, engine availability, knowledge-layer counts |
| Settings | — | server address, source language, timeout, optional engine id |

### Design constraints

- **`TranslationEngine` stays untouched.** The app is an HTTP client. It has no model,
  no vocabulary file and no translation logic. Swapping the backend's engine changes
  what the app shows without an app rebuild.
- **No model is chosen here.** The engine field is optional and defaults to empty,
  meaning "let the server route". Primary, fallback and per-language routing are
  undecided (`selectDefaultModel()` throws), so hardcoding a preference would bake a
  guess into every build.
- **Minimal dependencies.** `HttpURLConnection` and `org.json` are both in the Android
  framework, so there is no OkHttp, Retrofit, Gson or Kotlin coroutines. AndroidX is
  the only external set, for the UI. One dependency for HTTP and one for JSON would
  both be for things the framework already does — including cancellation, which
  `disconnect()` handles properly.
- **Java, not Kotlin.** No Kotlin compiler download, no build-time cost, and the
  client stays readable for whoever inherits it.

### Where TM, Glossary and Knowledge run

Server-side, inside the pipeline. The app does not reimplement any of it. It shows
which engine answered, whether the result came from cache/memory, and the
knowledge-layer counts on the Status screen — so their presence in the path is
visible without the client pretending to own them.

### Cancellation

`Cancellation` owns an abort action; `ApiClient` registers
`HttpURLConnection.disconnect()` as it, so cancelling tears the socket down rather
than abandoning a future. The server observes the disconnect and its own token
propagates. A flag nobody checked would leave the reader watching a spinner after
pressing stop.

## Build

Requires JDK 17 (the default JDK 21 on this host cannot start), Gradle 8.7+, Android
SDK with platform 34 and build-tools 35.0.0.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

npm run apk:test        # JVM unit tests
npm run apk:release     # signed release APK
npm run apk:debug       # debug APK
npm run apk:all         # all three
```

The publish tasks depend on the assembling task, so they cannot republish a stale
APK from a previous build: `npm run apk:release` with nothing built fails rather than
quietly copying whatever is in the output directory.

The publish tasks copy the deliverables to `release/` — not `dist/`, because
`npm run clean` (run by every backend build) removes `dist/`:

- `release/shura-translator-0.1.0-beta1-release.apk` — signed with the local dev key
- `release/shura-translator-0.1.0-beta1-debug.apk`

### Host notes

Two things are specific to this machine and are already configured in
`android/gradle.properties`:

- `android.aapt2FromMavenOverride` — AGP downloads an x86-64 aapt2, which cannot run
  on this aarch64 host. It is pointed at the SDK's native aapt2 instead.
- `JAVA_HOME` must be JDK 17; JDK 21 fails with "Failed to mark memory page as
  executable".

### Talking to the server: loopback, and cleartext

The default address is `http://127.0.0.1:8787`, and since `targetSdk 34` Android
refuses cleartext HTTP by default. Every request failed with `CLEARTEXT
communication to 127.0.0.1 not permitted by network security policy` on any device
on API 28 and up — the app could not reach any server with its own default settings.

`app/src/main/res/xml/network_security_config.xml` therefore permits cleartext on
**loopback only** (`127.0.0.1`, `localhost`, and the emulator's `10.0.2.2`); every
other host stays https-only. The manifest must reference that file, or the policy is
inert and the failure comes back — `NetworkSecurityPolicyTest` asserts both halves.

`127.0.0.1` on a device is the device itself, so a physical phone needs the host
server forwarded into it:

```bash
adb reverse tcp:8787 tcp:8787     # then the default address works unchanged
```

A server on a LAN or the internet must be reached over **https**. Widening the
cleartext exception to a subnet would put translation traffic, and any credential
sent with it, on the wire in plaintext.

### Signing

`android/keystore/shura-dev.jks` is generated locally and git-ignored. It is a
development key: the password is in `app/build.gradle` on purpose so the build
reproduces, and because anyone can install a build signed with it, **it must not be
used for a store release**. Regenerate with:

```bash
keytool -genkeypair -v -keystore android/keystore/shura-dev.jks \
  -storepass shuradev -keypass shuradev -alias shura \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Shura Translator Dev, O=Shura, C="
```

## Tests

```bash
npm run apk:test
```

JVM unit tests only — no emulator needed.

- `TranslationApiTest` — real HTTP against a JDK `ServerSocket` server. Bodies are
  copied from the platform's own serializers (`serializeTranslation`,
  `serializeChapter`, `/engines`, `/health`). Asserts the request bodies the client
  sends, and that 4xx is not offered as retryable while 5xx/408/429 is.
- `RequestStateTest` — loading / error / retry / cancel transitions, including that a
  retry clears the previous error and does not offer Retry while running.
- `SegmentParserTest` — chapter splitting, blank-line handling, the segment ceiling.
- `ArabicTextTest` — that the server's `direction` wins over inspecting the text, and
  that unusable output is flagged.
- `NetworkSecurityPolicyTest` — that the cleartext exception covers the default server
  address and nothing wider, and that the manifest applies the policy file. The
  cleartext refusal itself is enforced by the Android framework, not by the JVM, so a
  JVM test cannot observe it; this asserts the configuration that causes it. It runs
  in the default suite: `npm run apk:test` — 54 methods, 48 passed, 6 skipped (the
  opt-in live tests), 0 failed.

### Not covered

Whether the app opens. There is no device or emulator on this host (`/dev/kvm` is
absent), and Robolectric cannot substitute: it needs a Conscrypt native library with
no build for this Alpine JDK on aarch64, so a `Robolectric.buildActivity(MainActivity)`
test was tried and abandoned rather than left failing. Layout ids are checked against
the code that uses them, and lint is clean of errors, but "the activity starts" is
only provable on a device.


### End-to-end against the real backend

Opt-in, so the default suite stays offline:

```bash
# terminal 1
npm run start                     # or TRANSLATION_ALLOW_ECHO_ENGINE=true npm run start

# terminal 2
SHURA_E2E_URL=http://127.0.0.1:8787 npm run apk:test
```

`LiveServerIntegrationTest` drives the same `TranslationApi` the app ships against the
running server: health, engines, translate, chapter, language detection, a rejected
empty request, and an unreachable address.

**What it proves:** the request shapes the app sends are accepted, and the responses
the app parses are what the server actually sends.

**What it does not prove:** translation quality. No local model has been executed
anywhere in this project.

## Not implemented

- No offline mode or on-device model.
- No source download, image handling or OCR. Chapter text is pasted; supplying it from
  a reader extension is separate work.
- No streaming. `/translate/chapter` returns one response; the progress events it
  already emits are not surfaced yet.
- No accounts, sync or history.
- No Shura, Mihon or Keiyoushi integration, by design: this client is standalone so
  it has no dependency on any of them.