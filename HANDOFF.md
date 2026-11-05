# 交接说明：把「爽歪了AI键」模块并入另一个 App

> 把下面「一、给对方 AI 的指令」整段复制到新对话里即可。

---

## 一、给对方 AI 的指令

```
请把下面这个已经做好的 Android 模块（AI 键接管）并入 <目标 App 名称/包名>，
保留其全部功能，并适配目标 App 的 UI/包名。先读文档，再动手，不要重写。

【项目位置】
D:\小米云盘\文档\deepseek-harness\default-workspace\aikey-lsp

【现状】
- 包名 com.aikeyplus.lsp，应用名「爽歪了AI键」，versionName 4.3.1 / versionCode 58
- 产物 dist\aikey-plus-lsp-v4.3.1.apk（约 221 KB，无第三方依赖）
- 两种运行方式：
  1) LSPosed 钩子（system_server 内拦截 AI 键，功能最全）
  2) 守护进程（root 下读 /dev/input/event5，不依赖 LSPosed）

【必须保留的能力】
- 单击 / 双击 / 三击 / 长按 四个手势（阈值可配：longMs 默认 900、doubleMs 350、tripleMs 400）
- 原生动作分发（付款码、乘车码、扫码、防窥、静音、截图、背屏截图、相机、小爱等）
- 背屏能力：显示应用、回背屏桌面、背屏小部件（AppWidget 挂到 display 1）
- 动作目录 ActionCatalog（约 80 项）+ 设置界面 + 致谢页

【关键集成点】（照搬，不要改语义）
1. AndroidManifest.xml
   - <provider android:name=".AiKeyProvider" android:authorities="<新包名>.config" android:exported="true"/>
   - <service android:name=".KeepAliveService"/>（前台服务保活，防 MIUI 冻结）
   - <service android:name=".ShellService" android:exported="true"/>（可选，已无 Shizuku 依赖时不需要）
   - <receiver android:name=".BootReceiver"/>（开机自启守护进程）
   - <activity android:name=".WidgetActivity" resizeableActivity="true"/>
   - 权限：FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE、POST_NOTIFICATIONS、CAMERA（手电筒用 CameraManager）
2. 配置通道（最重要）
   App 用 root 把配置写进 Settings.System，钩子在按键流程里直接读：
     aikey_single / aikey_double / aikey_triple / aikey_long
     aikey_long_ms / aikey_double_ms / aikey_triple_ms
     aikey_haptic / aikey_codes / aikey_mode
   实现见 Engine.writeHookConfigSync() 与 hook/Bridge.readSettings()
   ——不要改回 ContentResolver 调 provider：开机早期 provider 不可用，钩子会永远读到默认值。
3. 钩子入口
   xposed_init 里 com.aikeyplus.lsp.hook.HookEntry
   钩 PhoneWindowManager 的 interceptKeyBeforeQueueing / interceptKeyBeforeDispatching，
   以及 MiuiPhoneWindowManager；AI 键框架键码 690（内核 689）。
4. 原生动作
   ShortCutActionsUtils.getInstance(ctx).triggerFunction(fn, "ai_key", new Bundle(), true)
   类在 /system_ext/framework/miui-services.jar，必须用 lpparam.classLoader。
   例外：launch_ai_memory 需要第二个参数传 "click_mi_ai_key"。
5. 守护进程
   App 把 assets 里的 aikey_lib.sh / aikey_daemon.sh / dispatch.sh + config.conf + commands.conf
   用 root 推到 /data/local/tmp/aikey/，再 `setsid sh ./aikey_daemon.sh`。
   App 私有目录 root 读不了（SELinux），所以必须放 /data/local/tmp。

【构建方式】
- 没有 Gradle：build.ps1 手工流水线（aapt2 / javac / d8 / zipalign / apksigner）
- 构建目录必须在 ASCII 路径（%TEMP%\aikey-lsp-build），aapt2 打不开中文路径
- d8 必须用 --debug；用 --release 会做类合并，破坏 Xposed/Shizuku 内部类
- 签名：%USERPROFILE%\.aikey-lsp\aikey-debug.jks，口令 android

【绝对不要做的事】
- 不要再引入 Shizuku/Stellar 的 provider（会 NoClassDefFoundError 崩溃）
- 不要写 /data/system/*（SELinux 拒绝，root 也不行）
- 不要依赖 provider 在开机 30 秒内可用
- 不要用 `--release` 跑 d8

【已知未完成】
- 小爱记忆 / 小爱视频：目前会进「小爱捷径」，正确的 function 键还没找到
- 背屏自定义面板：那块 display 带 FLAG_OWN_CONTENT_ONLY，第三方 Activity 上不去
```

---

## 二、项目结构与文件职责

| 文件 | 职责 |
| --- | --- |
| `AndroidManifest.xml` | 组件、权限、xposed 元数据 |
| `src/com/aikeyplus/lsp/MainActivity.java` | 设置界面（底栏两页签：设置 / 背屏） |
| `src/com/aikeyplus/lsp/Prefs.java` | 所有配置项读写 + config.conf 生成 |
| `src/com/aikeyplus/lsp/Engine.java` | 守护进程启停、设置备份、root shell 动作、原生设置读写 |
| `src/com/aikeyplus/lsp/Shell.java` | 常驻 root shell（su + 哨兵协议） |
| `src/com/aikeyplus/lsp/ActionCatalog.java` | 动作清单（~80 项，分 5 组），`native:` / `app:` / `shell:` / 普通命令 |
| `src/com/aikeyplus/lsp/AiKeyProvider.java` | 给旧版钩子的配置 provider（现在只作兜底） |
| `src/com/aikeyplus/lsp/hook/HookEntry.java` | Xposed 入口，只处理 `android` 与背屏中心进程 |
| `src/com/aikeyplus/lsp/hook/KeyHook.java` | 按键拦截 + 手势状态机调度 + 震动反馈 |
| `src/com/aikeyplus/lsp/hook/GestureEngine.java` | 纯 Java 手势状态机（单击/双击/三击/长按，有 37 个单元测试） |
| `src/com/aikeyplus/lsp/hook/HookConfig.java` | 配置快照 |
| `src/com/aikeyplus/lsp/hook/NativeActions.java` | 调 MIUI `ShortCutActionsUtils` |
| `src/com/aikeyplus/lsp/hook/Bridge.java` | 钩子 → App 的配置读取与手势派发 |
| `src/com/aikeyplus/lsp/hook/SubScreenHook.java` | 背屏小部件（在背屏中心进程里 addView） |
| `src/com/aikeyplus/lsp/WidgetActivity.java` | 小部件宿主页 |
| `assets/aikey_lib.sh` | 守护进程动作库（root 执行） |
| `assets/aikey_daemon.sh` | 读 /dev/input 的守护进程 |
| `test/GestureEngineTest.java` | 手势状态机回归测试（37 例） |
| `test/ActionCatalogTest.java` | 动作表自检（id 唯一、内置/命令分类等） |

## 三、并入另一个 App 的具体步骤

1. **复制源码**：把 `src/com/aikeyplus/lsp/**` 整体挪到目标 App（包名替换为目标的），
   保留 `hook/` 子包与 `assets/*.sh`、`test/*`。
2. **改包名与 authority**：`com.aikeyplus.lsp` → 目标包名；
   provider authority 改成 `<目标包名>.config`；同时改 `Bridge.URI`。
3. **合并 Manifest**：按「一、关键集成点」补齐组件与权限；
   若目标 App 已有 Application 类，把 `App.onCreate` 里的 `Engine.ensureInstalled()` /
   `writeHookConfigSync()` 挪进去。
4. **入口**：`assets/xposed_init` 内容保持 `com.<目标包名>.hook.HookEntry`。
5. **设置界面**：可只移植 `MainActivity` 的手势卡片部分，动作选择复用 `ActionCatalog`。
6. **构建**：复制 `build.ps1`，改 `$Version` / `$VersionCode` 即可；先跑 `-SkipTests` 快速验证。
7. **验证清单**（真机）：
   - 安装后打开 App：「LSPosed 钩子 = 已注入」「Root 权限 = 已授权」
   - 按一下 AI 键：日志有 `AIKey: key code=690 down`，随后 `手势 single -> ...`
   - 改动作后立即生效（配置走 Settings，不需要重启）
   - 守护进程模式：`/data/local/tmp/aikey/daemon.pid` 存在，`aikey.log` 有「执行动作」

## 四、踩过的坑（务必遵守）

1. **`aikey_native_busy` 卡在 1**：钩子和守护进程会把所有按键当“自己注入的”跳过。
   App 启动、应用设置、守护进程派发前都要 `settings put system aikey_native_busy 0`。
2. **LSPosed 记录旧 APK 路径**：每次 `pm install -r` 后 LSPosed 数据库里的 apk_path 会失效，
   模块看似加载实则跑旧代码。装完必须更新 `/data/adb/lspd/config/modules_config.db` 的
   `modules.apk_path`，或让用户在 LSPosed 里重装模块。
3. **重装会清空 App 数据**：这台机器有「应用数据隔离」，prefs 会被重定向到
   `/data/misc/apexdata/<uuid>/prefs/<pkg>/aikey.xml`，每次重装 uuid 可能变。
   所以配置要落到 Settings.System（已实现）。
4. **provider 在开机早期不可用**：`ContentResolver.call` 会抛 `Unknown authority` /
   `IActivityManager null`。配置一定走 Settings。
5. **MIUI 拦截 adb install**：报 `INSTALL_FAILED_USER_RESTRICTED`，
   用 `su -c "pm install -r -d /data/local/tmp/x.apk"`。
6. **d8 --release 会破坏类**：必须 `--debug`。
7. **背屏是私有屏**：`FLAG_OWN_CONTENT_ONLY`，第三方 Activity 上不去；
   小部件要在背屏中心进程里用 `WindowManager.addView` 挂。

## 五、版本与产物

- 当前 APK：`dist\aikey-plus-lsp-v4.3.1.apk`（221 KB）
- 源码包：`dist\aikey-plus-lsp-v2.1.1-src.zip`（较旧，仅供参考打包结构）
- 目标设备：小米 18 Pro / HyperOS OS4 / Android 17，AI 键 = keycode 690（内核 689）
