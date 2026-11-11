# TN Assistant Android

Kotlin + Android framework Views/XML; Java 21 build runtime, JVM bytecode 17; minSdk 21, target/compile 36. Application ID/namespace `vn.tn.assistant`, preview `vn.tn.assistant.preview`; current candidate `26.11.06.182` / `26110682`. Gradle 8.11.1 wrapper, AGP 8.10.1, Kotlin 2.1.21. Dependency versions are locked in app/gradle.lockfile. Release requires HTTPS; debug/preview allow only localhost / 127.0.0.1 / 10.0.2.2 cleartext for local development.

Login UI revision: [design](DESIGN.md), [interaction contract](UX-CONTRACT.md), [device evidence and comparison](../reports/login-ui/REPORT.md). The `preview` build installs as `vn.tn.assistant.preview` alongside the original app. It installs separately from the older Kiki preview. Rebuilt classes and Whisper JNI use the TN namespace; unchanged original VAD/Sherpa JNI descriptors stay compatible.

## Production signing

The production `release` build reads `../.runtime/android-release-signing/signing.properties` relative to the Android project root. Keep that properties file and `tn-assistant-release.jks` private; `.runtime/` and keystore files are ignored by Git. Without the local signing files, Gradle leaves Release unsigned. The `.181` key introduced TN Assistant's first production signing identity and is reused by `.182`; future app updates must keep using it. Build with `.\gradlew.bat :app:assembleRelease` from this directory.

## In-app updates

Settings checks the public `/v1/releases/latest` endpoint without requiring an account, and includes the installed application ID so Production and Preview receive separate catalog entries. Administrators must provide the matching APK package, version, minimum Android API, HTTPS download path under `/downloads/`, and SHA-256. Draft entries stay hidden until published. The app follows no redirects, limits downloads to 200 MiB, verifies the hash and APK package/version/minSdk, then opens Android's package installer through a cache-only FileProvider. Android may ask the user to allow installs from TN Assistant before installation.

## Build and test

Set JAVA_HOME to your JDK 21 and ANDROID_HOME to the SDK containing Android 36. Create local.properties (`sdk.dir=...`) when importing in Android Studio. From this directory:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
.\gradlew.bat :app:assemblePreview
.\gradlew.bat :app:assemblePreviewAndroidTest -PnativeTestBuildType=preview
.\gradlew.bat :app:testPreviewUnitTest -PnativeTestBuildType=preview -I ..\reports\parity69\gradle-test-memory-r1.init.gradle
```

Default backend: `http://127.0.0.1:8088`; use `adb -s emulator-5556 reverse tcp:8088 tcp:8094`. Edit the endpoint in Cài đặt → Kết nối. Production provider credentials belong to the backend; no original APK API keys are used.

To review login without replacing the original package, install `app/build/outputs/apk/preview/app-preview.apk` using `adb install --no-streaming -t`, then run:

```powershell
adb shell am start -n vn.tn.assistant.preview/vn.tn.assistant.MainActivity --es preview_screen login
```

The login shortcut is enabled only in debug/preview. The normal launcher still follows the existing onboarding/consent flow.

## Preserved inputs and supported flows

Original artwork: translucent lower assistant gradient, microphone animated vector, guide/settings vectors, Kiki launcher icon, settings background particles, Barlow fonts. Settings keep a left navigation pane on landscape displays. The recreated form/detail screens implement the named user journeys; they are rewritten Views, not bit-for-bit restored original layouts.

Guest/session refresh, account/Zalo-link provider errors, real QR code encoding, consent/permission denial, contacts/dialing, optional GPS/call permissions, defaults/offline cached settings, devices/unlink, activation/Premium, sandbox paid/failed/cancel checkout, history, traffic provider errors, content/welcome, diagnostics, update download SHA-256 and platform installer, and restricted WebView are included. Backend-confirmed entitlements remain authoritative. OEM test controls alter an explicitly simulated state; production hardware requests report unavailable on Android SDK VMs.

All nine ARM64 libraries and the original five KWS/VAD assets are copied without modification. JNI class descriptors and field names are preserved. VADJNI uses the original four static native methods. Sherpa OnlineRecognizer creates a real native Wenet CTC model with chunkSize 16 / leftChunks 4; the foreground microphone service uses the original Kiki wake phrase and trie beam search, with a bounded PCM window to retain commands spoken in the same utterance. The detector uses the original 60-frame buffer, beamSize 10, topK 5, score -7, and 500 ms cooldown. Native ownership is serialized across service/diagnostic/tests.

Microphone PCM16 mono 16 kHz runs on a dedicated worker; streaming retains bounded pre-ready packets in order, partial/final events come from the provider, and ASR fallback is used only when the provider reports unsupported streaming. A session generation cancels stale results. Focus loss, noisy/removed audio routes and network loss cancel. Directives execute in order, including nonblocking Delay and asynchronous speech playback; a generation-scoped cancellation suppresses stale actions/completions. Immutable voice generations/request tickets guard result acknowledgements and late typed-command/TTS responses. Missing ASR/NLU/TTS configurations produce errors, never successful fixture responses.

Google Maps, VietMap S1/S2 broadcasts and VietMap Live/Navitel URIs preserve the extracted integration contracts. App/skill URI adapters require an explicit package allowlist in connection settings. These integrations still need installed map/skill applications and real provider payloads for full vehicle acceptance.

## Test coverage and limits

JVM tests: silent/incomplete/low-confidence keyword rejection and original token-sequence detection. Instrumentation: real original ARM64 JNI/model initialization and silence; cancelled Delay suppresses later actions/completion; concurrent two-client HTTP 401 rotates one refresh token (isolated local MockWebServer fixture, not production speech). Root-owned NativeTimingTest measures original WAV and silence on LDPlayer.

Cloud/provider authentication and live speech are not validated without separate developer credentials/contracts. Zalo OAuth callback behavior depends on the provider. Full voice-command/provider payload parity, exact original UI pixels and real vehicle hardware remain external acceptance work; this project does not claim recovery of proprietary server source or every original algorithm implementation. Native libraries remain original compiled artifacts.
