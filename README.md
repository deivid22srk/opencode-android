# OpenCode Android

[![Build APK](https://github.com/deivid22srk/opencode-android/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/deivid22srk/opencode-android/actions/workflows/build.yml)
![status](https://img.shields.io/badge/platform-Android%20arm64--v8a-3DDC84)
![language](https://img.shields.io/badge/language-Kotlin%20%2B%20Compose-7F52FF)
![design](https://img.shields.io/badge/design-Material%203%20Expressive-006AC9)
![minSdk](https://img.shields.io/badge/minSdk-26%20(Android%208.0)-blueviolet)

Run the [opencode-ai](https://github.com/anomalyco/opencode) coding agent as a
**localhost server on your Android phone** — built with Jetpack Compose and
Material 3 Expressive.

## Why this exists

opencode ships prebuilt ARM64 Linux binaries that are dynamically linked
against `musl libc`, `libstdc++`, and `libgcc_s`. Android's kernel is Linux,
so the syscalls work — but Android's userland uses Bionic, not musl. **This
app bundles a musl runtime inside the APK** and uses the musl dynamic linker
to launch whatever `opencode` ARM64 binary the user imports. No Termux, no
root, no proot — just a real `opencode serve` process running inside the
app's sandbox.

## How it works

1. **You** download an ARM64 release from
   https://github.com/anomalyco/opencode/releases
   (`opencode-linux-arm64-musl.tar.gz` is recommended).
2. **You** open the app and tap **Import release file** — the app extracts
   the `opencode` binary into its private storage and `chmod +x`s it.
3. **The app** copies the musl dynamic linker, libc, libstdc++, and libgcc_s
   out of `assets/musl/` into the same sandbox.
4. When you tap **Start server**, the app launches:
   ```
   ld-musl-aarch64.so.1 --library-path <libdir> opencode serve --port 4096 --hostname 127.0.0.1
   ```
   inside a foreground service.
5. The console shows live `stdout`/`stderr` and the URL the server is
   listening on (`http://127.0.0.1:4096`).

## Architecture

```
app/src/main/
├── assets/musl/                       ← bundled musl runtime (~3.5 MB)
│   ├── ld-musl-aarch64.so.1
│   ├── libstdc++.so.6
│   └── libgcc_s.so.1
├── java/com/deivid/opencode/
│   ├── MainActivity.kt                ← single-activity Compose host
│   ├── OpenCodeApp.kt                 ← Application + notification channel
│   ├── server/
│   │   ├── BinaryManager.kt           ← import + extract + chmod binary
│   │   ├── TarReader.kt               ← minimal ustar tar parser
│   │   ├── OpencodeProcess.kt         ← launches `opencode serve` via musl ld
│   │   ├── OpencodeService.kt         ← foreground service + log streaming
│   │   └── Paths.kt (inside BinaryManager.kt)
│   ├── viewmodel/ServerViewModel.kt   ← state holder
│   └── ui/
│       ├── theme/                     ← Material 3 Expressive colours + type
│       ├── components/                ← StatusPill, LogViewer
│       └── screens/HomeScreen.kt      ← main UI
└── res/                               ← strings, themes, icons, file_paths
```

## Build

CI runs on every push:

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Artifacts are uploaded automatically by `.github/workflows/build.yml`.

**Latest APK:** download from the
[Actions tab](https://github.com/deivid22srk/opencode-android/actions/workflows/build.yml)
— pick the most recent green run, scroll to "Artifacts", grab
`opencode-android-debug`.

## First run

1. **Install** the APK on an ARM64 phone (Android 8.0+).
2. On a computer or your phone, download
   `opencode-linux-arm64-musl.tar.gz` from
   [opencode releases](https://github.com/anomalyco/opencode/releases).
3. Open the app → tap **Import release file** → pick the tar.gz.
4. Set a port (default `4096`) and optionally a password.
5. Tap **Start server**. The console will print
   `opencode server listening on http://127.0.0.1:4096`.
6. Open that URL in any browser on the phone — the opencode HTTP API is now
   live.

> The server keeps running in the background via a foreground service. A
> persistent notification shows the URL; tap "Stop" on it to halt the server.

## Requirements

- Android 8.0+ (API 26+)
- ARM64 device (arm64-v8a ABI) — matches the only architecture opencode ships
  for. (Most modern phones are arm64-v8a.)
- ~80 MB free storage after import (the opencode binary is ~160 MB unpacked).

## Permissions

| Permission | Why |
| --- | --- |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keep the opencode server alive in the background |
| `WAKE_LOCK` | Don't let the CPU sleep mid-session |
| `POST_NOTIFICATIONS` | Show the "server running" notification (Android 13+) |
| `INTERNET` / `ACCESS_NETWORK_STATE` | opencode may call out to model APIs |

No storage permission is needed: the binary is picked via the system
file picker (`OpenDocument` with persistable read access).

## License

MIT — same as opencode itself. Bundled musl libraries are MIT-licensed from
Alpine Linux.
