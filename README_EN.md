# AIKey Plus — Xiaomi AI button, remastered

[中文说明](README.md)

Turns the dedicated **AI button** on Xiaomi / Redmi phones (the one that opens 超级小爱 by default) into a fully customizable gesture key.

The module runs inside `system_server` through LSPosed, intercepts the key event, and dispatches **MIUI's own native actions** via
`com.miui.server.input.util.ShortCutActionsUtils#triggerFunction` — so it can do real system-level things (payment codes, transit codes,
scanners, peep protection, mute, screenshots, rear-screen capture, camera, Xiao Ai functions), not just "launch an app".

| | |
| --- | --- |
| Package | `io.github.jumaoli.aikey` |
| Version | 8.1.0 (71) |
| Min SDK | 30 |
| Runtime | LSPosed (scope: **System Framework**) |
| Tested on | Xiaomi 18 Pro, HyperOS OS4.0.27, Android 17 |

## Features

- **Four gestures**: single click / double click / triple click / long press — every threshold is configurable
- **80+ built-in actions** in five groups: 常用 / 小爱·语音 / 支付·乘车·扫码 / 系统功能 / 应用·自定义
- **Arbitrary shell commands** (requires root)
- **Rear screen support**: launch apps on display 1, back to the rear launcher, rear-screen screenshot, AppWidget hosting
- Optional haptic feedback, keep-alive foreground service, "consume key" mode
- Settings are stored in `Settings.System` (`aikey_*` keys), so they survive reinstalling the app
- Miuix / HyperOS style UI (large titles, grouped cards, icon rows, pill navigation)

## Install

1. Install the APK (from [Releases](https://github.com/jumaoli/aikey-plus-lsp/releases))
2. Enable the module in LSPosed, check the **System Framework** scope
3. Reboot
4. Open the app, tap **立即应用 (Apply)** and grant root when asked
5. Pick an action for each gesture

> If you previously installed `com.aikeyplus.lsp` (≤ 7.0.2), uninstall it first — it would hook the same key twice.

## How it works

1. `PhoneWindowManager#interceptKeyBeforeQueueing` / `#interceptKeyBeforeDispatching` and `MiuiPhoneWindowManager` are hooked.
2. The AI key arrives as keycode **690** in the framework layer (689 at the kernel layer, `/dev/input/event5`, `gpio-keys`).
3. A small state machine (`GestureEngine`) classifies the press into single / double / triple / long.
4. Native actions are dispatched through `ShortCutActionsUtils.triggerFunction(fn, "ai_key", bundle, true)` using the module classloader.
   Shell-based actions go back to the app, which runs them through a root shell.

## Build

No Gradle — a self-contained PowerShell pipeline (`build.ps1`) using `aapt2`, `javac`, `d8`, `zipalign` and `apksigner`.

```powershell
./build.ps1              # compile, run unit tests, package
./build.ps1 -SkipTests   # skip unit tests
```

Requirements:

- JDK 17+
- Android SDK with `build-tools` and a `platforms/android-*` containing `android.jar`
- The project path must be ASCII (the script re-stages into `%TEMP%` because `aapt2` cannot handle non-ASCII paths on Windows)

Signature: by default `%USERPROFILE%\.aikey-lsp\aikey-debug.jks` (password `android`, generated on first run).
Override with `-KeystorePath`, `-StorePass`, `-KeyPass`.

## CI

`.github/workflows/build.yml` builds the APK on every push and pull request and uploads it as an artifact.
Pushing a `v*` tag also publishes a GitHub Release.

To sign CI builds with the official key, add two repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64` of your `.jks` |
| `KEYSTORE_PASS` | keystore / key password |

Without them, CI builds use a throwaway key and **cannot** be installed over an official build.

## Known issues

- 「小爱记忆 / 小爱视频」 currently opens the 小爱 shortcut page instead of the memory/video screen — the correct function key is still unknown.
- The rear screen is a private display (`FLAG_OWN_CONTENT_ONLY`), so third-party activities cannot be shown there directly; only the subscreen app's own windows and AppWidgets work.
- Daemon mode (reading `/dev/input` directly with root) was removed in 8.1.0 — LSPosed hook only.

## Credits

- @婉彬 (Coolapk) — testing and feedback
- Cpek0 (Coolapk u/14823887) — 乐乐背屏, rear-screen launching approach
- NekoWannn — key handling logic and action implementation reference (`aikey_plus`)
- LSPosed — the runtime this module is built on

## License

MIT — see [LICENSE](LICENSE).
