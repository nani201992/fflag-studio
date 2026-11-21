# FFlag Studio

Android app for editing the Roblox FastFlag file that the client actually reads:

```
/data/local/tmp/ClientAppSettings.json
```

No root, no PC, no adb tether: the app talks to **Shizuku**, which runs as `shell` (uid 2000),
and that uid is allowed to write `/data/local/tmp`. The flag engine is Rust, the UI is plain
Java on the framework (no AndroidX, no gradle).

```
┌──────────────────────────────┐
│ Java UI (views built in code)│
│  list · add · presets · JSON │
└──────────────┬───────────────┘
               │ JNI
┌──────────────▼───────────────┐
│ Rust engine (libfflagcore.so)│  parse · validate · canonicalise · presets
└──────────────┬───────────────┘
               │
┌──────────────▼───────────────┐
│ Shizuku → sh (uid 2000)      │  cat > file · chmod 644 · read back · am start
└──────────────────────────────┘
```

## Features

* **Edit the real flag file** — reads it into editable rows, writes it back, verifies by
  reading again and comparing canonical JSON.
* **Type-safe flags** — every key must start with `FFlag`/`DFFlag` (bool), `FInt`/`DFInt`
  (int) or `FString`/`DFString` (string). The Rust engine rejects anything else before the
  client ever sees the file; `"38760"` is coerced to `38760`.
* **Client picker** — `apply to: Roblox | VNG` decides which client is restarted after a
  write (both read the same file).
* **Presets** — `fps`, `performance`, `clean`, defined in Rust.
* **Custom JSON** — `JSON` dialog with `Replace`, `Cancel`, and `.json file` (system file
  picker, JSON only, validated before it is accepted).
* **Lenient import** — real flag packs are messy, so an import keeps every valid key and
  reports the rest instead of refusing the file:
  * `FLog`/`DFLog` are supported (integer log levels).
  * a bare name that also appears prefixed in the same file is dropped — the client reads
    `prefix + name`, so only the prefixed entry can ever apply.
  * a bare name with no prefixed twin is written as both the `F` and the `DF` variant; the
    variant the client doesn't register is a no-op.
  * anything else is listed with its reason.
* **Flag mechanics** (what this app assumes; checked against the Android build):
  * the prefix carries the type and the lifetime: `Flag` bool, `Int` i64, `String`, `Log` u8;
    `F` = read once at startup, `DF` = can be re-read at runtime, `SF` = server-synchronised.
  * the client looks a flag up as `prefix + baseName`, so an unprefixed key never applies —
    that is why the importer drops bare duplicates and expands orphan bare names to both variants.
  * Roblox's Android cloud config is public
    (`https://clientsettingscdn.roblox.com/v2/settings/application/AndroidApp`, ~25 500 names).
    It is **not** a validity list: `FFlagDebugDisplayFPS` works and is absent from it, so absence
    proves nothing. The same goes for the client binary: Roblox's scripts read flags too, so a
    name missing from `libroblox.so` may still be live.
  * values are typed, not scored: `"DFIntTaskSchedulerTargetFps": 99999` is legal and simply
    means "don't cap me"; a big number is not automatically bad, and a low one is not automatically
    safe. **This app never rewrites a value you set.**
* **Fast with big packs** — the list is a recycled `ListView`, so a 1300-flag set builds ~10
  row views instead of 1300 (measured: 1% janky frames, 8 ms median frame while scrolling).
  Row edits commit on focus loss, and `Apply` also commits the focused row.
* **Autosave** — edits you have not applied yet are kept in app storage, so leaving the app
  loses nothing. The draft is versioned, only exists while it differs from the device file,
  and is deleted as soon as the device file is read or an apply succeeds, so a stale draft can
  never shadow what is actually on the device.
* **Clear all** — empties the working set (draft included) without touching the device; press
  `Apply` to write an empty flag file. The pre-first-apply file stays in app storage as
  `ClientAppSettings.json.bak`.
  deletes it if there was none.
* **AI prompt** — builds a ready-to-paste prompt (format rules, your client version, your
  current flags, a goal slot) and provides Copy / Share. Paste the answer back in `JSON`.
* **Fails loudly** — a failed apply shows the failing command, its stderr and a probe
  (`uid`, `/data/local/tmp` mode, write test, target file mode, SELinux context), with a
  Copy button. Restart failures never hide a successful write.
## Installation ( android )
1. install shizuku
2. grant shizuku for fflag studio
3. add fflags
4. press apply then it immediately start roblox

## Tested on

Samsung SM-A526B (Android 14), Shizuku 13.6 running as `shell`, Roblox global 2.747.1486 and
Roblox VNG 2.746.1264. Verified end-to-end: apply/read-back, restart of either client,
presets, `.json` import (valid and invalid), 30 vs 240 FPS visible in the client's FPS overlay,
missing-file first run, and the failure report with probe.

## Requirements

* Android 8.0+ (API 26), arm64.
* [Shizuku](https://shizuku.rikka.app/) installed and running, with this app granted.
  Shizuku is usually started over wireless debugging (`adb shell sh …/start.sh`) or with root.
* Roblox (`com.roblox.client`) and/or Roblox VNG (`com.roblox.client.vnggames`).

## Install

Grab `FFlagStudio-<version>.apk` from the [releases page](../../releases) and side-load it.
The app footer shows the version, so a screenshot always identifies the build.

First run: press **Apply** once and accept the Shizuku prompt. The status line turns into
`channel: Shizuku · uid 2000`.

## Usage

| Action | What it does |
|---|---|
| `Add` | Add one flag; the key/value pair is validated by Rust first |
| `Preset` | Merge a built-in set (`fps`, `performance`, `clean`) |
| `JSON` | Edit the set as JSON; `Replace` applies the text, `.json file` loads a file |
| `Clear all` | Empty the working set; `Apply` writes an empty file |
| `Apply` | Normalise, write, verify, restart the selected client |
| `filter` | Filter rows by key |
| `✕` | Remove a row (autosaved, not written until Apply) |

Flags are only read by the client **at startup**, so `Apply` restarts the selected package.
If the restart fails the flags are still written — just open Roblox yourself.

### Flag format

```json
{ "DFIntS2PhysicsSenderRate": "38760", "FIntDebugTextureManagerSkipMips": 8 }
```

becomes

```json
{"DFIntS2PhysicsSenderRate":38760,"FIntDebugTextureManagerSkipMips":8}
```

Prefix decides the type; an unknown prefix (`{"DebugThing":true}`) or a value that does not
fit the prefix is refused with the engine's message.

## Build from source

Everything below runs on-device in Termux; no gradle, no Android SDK manager.

```sh
# 1. build tools (once)
#    android.jar + d8 + apksigner from Google's repository, Shizuku AARs from Maven Central
mkdir -p ~/apkbuild/tools ~/apkbuild/dl
cd ~/apkbuild/dl
curl -O https://dl.google.com/android/repository/build-tools_r34-linux.zip
curl -O https://dl.google.com/android/repository/platform-34-ext12_r01.zip
unzip -oq build-tools_r34-linux.zip android-14/lib/d8.jar android-14/lib/apksigner.jar
unzip -oq platform-34-ext12_r01.zip android-34-ext12/android.jar
#    Shizuku: api, provider, aidl (13.1.5), then extract classes.jar from each
# 2. Rust engine
cd rust && CARGO_TARGET_DIR=~/.cache/fflagcore cargo test
CARGO_TARGET_DIR=~/.cache/fflagcore cargo build --release
# 3. APK
./build.sh
```

`build.sh` is the whole build: `aapt2 compile` → `aapt2 link --java` → `javac` → `d8` →
zip `classes.dex` + `lib/arm64-v8a/libfflagcore.so` → `apksigner`.

Signing uses `~/apkbuild/debug.keystore`; replace it (and the `--ks-*` flags) for anything you
intend to publish.

## Repo layout

```
AndroidManifest.xml        package, permissions, Shizuku provider, adaptive icon
build.sh                   aapt2 + javac + d8 + apksigner pipeline
res/                       adaptive icon (vector + monochrome) and colours
src/com/kizu/fflagstudio/
  MainActivity.java        UI, autosave, apply/undo, JSON dialog, AI prompt
  ShizukuShell.java        Shizuku binder shell (run, run with stdin)
  Core.java                JNI bridge to the Rust engine
rust/                      libfflagcore: parse/validate/canonicalise/presets (+ tests)
```

## Versioning

`versionName` is in `AndroidManifest.xml`, shown in the footer and in failure reports.
Each fix bumps it by 0.1 (`0.1`, `0.2`, …), and the APK is named `FFlagStudio-<version>.apk`.

## Troubleshooting

* **`Shizuku not running`** — start Shizuku, then reopen the app (it re-checks the binder on
  resume).
* **`Apply failed`** — the dialog names the exact command and stderr plus a probe:
  `probe: uid=2000 tmp=drwxrwx--x write=ok file=-rw-r--r-- ctx=u:r:shell:s0`.
  `write=denied` means Shizuku's uid cannot write `/data/local/tmp`; `file=` shows the
  existing file's mode if one is there. Press **Copy** and read the first line: it is the
  app version.
* **Flags applied but nothing changes** — the file is only read at client startup; restart
  the client (or press Apply and let the app do it). Flags removed from a client build are
  written and silently ignored.
* **Cleared by accident** — `Clear all` only empties the editor; nothing reaches the device
  until `Apply`, and an empty draft is ignored on the next launch. If you already applied an
  empty set, the file from before the first apply is kept in app storage as
  `ClientAppSettings.json.bak` (no longer reachable from the UI — pull it with Shizuku).

## Limits

* One shared file: both clients read it, so there is no per-client flag set. The picker only
  chooses what gets restarted.
* Restart uses `cmd package resolve-activity`, falling back to a `LAUNCHER` intent; a Roblox
  build with a different package id won't be restarted automatically.
* `minSdk 26` and arm64 only (the Rust lib is built for `aarch64-linux-android`).
* Release APKs are signed with a local debug keystore.

## Third party

* [Shizuku](https://github.com/RikkaApps/Shizuku-API) (Apache-2.0) — privileged shell channel.
* Android build tools (`aapt2`, `d8`, `apksigner`) and the Android platform `android.jar`.
* Rust crates: `jni`, `serde_json`.
