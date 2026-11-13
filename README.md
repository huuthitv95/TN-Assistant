# TN Assistant

TN Assistant is an independent assistant stack composed of a Kotlin Android client, a Go backend, a React administration console and deployment tooling.

## Repository layout

- `android/`: Android application, tests, bundled libraries and required on-device models.
- `backend/`: Go API service, migrations and backend tests.
- `admin/`: React/Vite administration console and tests.
- `api/`: shared API definitions.
- `deploy/`: reproducible deployment configuration and the dedicated Zalo gateway.
- `scripts/`: active generation, import, voice and wake-word utilities.

The current Android source is 196 (`26.11.13.196`, version code `26111396`). It adds configured support QR/document dialogs and TN-owned help pages. Production publication is a separate verified step; a successful build alone does not establish full original-app parity.

Production 196 was published on 2026-11-13 at `https://assistant.tnfastvn.com/downloads/tn-assistant-preview196.apk` (the filename is a historical convention; its application ID is `vn.tn.assistant`). The production catalog points to 196; the public Preview catalog remains 194. See [support configuration](SUPPORT_CONFIG.md) and [remaining parity work](PARITY_PLAN.md).

## Prerequisites

- Git with Git LFS
- Java 21 and Android SDK 36
- Go matching `backend/go.mod`
- Node.js/npm matching `admin/package-lock.json`
- Python 3 for deployment tests and selected utilities

After cloning, fetch binary dependencies before building:

```powershell
git lfs pull
git lfs fsck
```

## Android signing

Release keys and passwords must stay outside this repository. Supply the absolute path to a private `signing.properties` file using either:

```powershell
$env:TN_ANDROID_SIGNING_PROPERTIES = "$env:LOCALAPPDATA\TN-Assistant\secrets\android-release-signing\signing.properties"
```

or:

```powershell
.\gradlew.bat :app:assembleRelease "-PtnSigningProperties=C:\private\signing.properties"
```

The `storeFile` value inside `signing.properties` may be absolute or relative to that properties file. Release tasks fail when complete signing configuration is unavailable. Preview and tests do not require release credentials.

## Local validation

Backend:

```powershell
Push-Location backend
go test ./... -count=1
go vet ./...
go build -trimpath -o (Join-Path $env:TEMP 'tn-assistant-server.exe') .
Pop-Location
```

Admin:

```powershell
Push-Location admin
npm.cmd ci --no-audit --no-fund
npm.cmd test -- --run
npm.cmd run typecheck
npm.cmd run build
npm.cmd run format:check
Pop-Location
```

Android:

```powershell
Push-Location android
.\gradlew.bat --max-workers=1 :app:testPreviewUnitTest :app:lintPreview :app:assemblePreview :app:assembleRelease :app:assemblePreviewAndroidTest -PnativeTestBuildType=preview --no-daemon --console=plain
Pop-Location
```

Deployment changes are intentionally separate from source validation. A local migration or build must not restart the VPS, call production providers or promote a release.
