<#
    一键构建 AI 键多功能（LSPosed 版）APK —— 不依赖 Gradle / AGP，
    只用 JDK 17 + Android SDK 的 aapt2 / d8 / zipalign / apksigner。

    注意：aapt2 打不开含中文的路径，所以脚本会先把工程复制到
    %TEMP%\aikey-lsp-build 里构建，再把成品 APK 拷回 dist\。

    用法：
        .\build.ps1                 # 构建 + 跑手势状态机单元测试
        .\build.ps1 -SkipTests      # 只构建
        .\build.ps1 -Sdk "D:\Android\Sdk"

    产物：dist\aikey-plus-lsp-v1.0.0.apk
    签名：%USERPROFILE%\.aikey-lsp\aikey-debug.jks（口令 android，首次自动生成）
#>
[CmdletBinding()]
param(
    [string]$Sdk,
    [string]$KeystorePath,
    [string]$StorePass = 'android',
    [string]$KeyPass,
    [switch]$SkipTests,
    [switch]$Clean
)

if (-not $KeyPass) { $KeyPass = $StorePass }

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Stage = Join-Path $env:TEMP 'aikey-lsp-build'
if ($KeystorePath) { $Keystore = $KeystorePath } else { $Keystore = Join-Path $env:USERPROFILE '.aikey-lsp\aikey-debug.jks' }
$Version = '8.6.0'
$VersionCode = 85
$ApkName = "aikey-plus-lsp-v$Version.apk"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "    $msg" -ForegroundColor DarkGray }

function Quote-Arg([string]$s) {
    if ($s -match '[\s"]') { return '"' + ($s -replace '"', '\"') + '"' }
    return $s
}

# 用 .NET Process 直接跑工具：输出按 UTF-8 解码，避免中文乱码；
# .bat（d8 / apksigner）走 cmd /c。
function Run([string]$Exe, [string[]]$ArgList, [string]$What) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $psi.StandardErrorEncoding = [System.Text.Encoding]::Default
    $joined = (($ArgList | ForEach-Object { Quote-Arg $_ }) -join ' ')
    if ($Exe -match '\.(bat|cmd)$') {
        $psi.FileName = $env:ComSpec
        $psi.Arguments = '/c ""' + $Exe + '" ' + $joined + '"'
    } else {
        $psi.FileName = $Exe
        $psi.Arguments = $joined
    }
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    $text = @()
    if ($out) { $text += ($out -split "`r?`n") }
    if ($err) { $text += ($err -split "`r?`n") }
    $text = $text | Where-Object { $_ -ne '' }
    if ($p.ExitCode -ne 0) {
        Write-Host "---- $What 失败 (exit $($p.ExitCode)) ----" -ForegroundColor Red
        $text | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        throw "$What 失败"
    }
    return $text
}

function ApiOf($name) {
    if ($name -match '(\d+)') { return [int]$Matches[1] }
    return 0
}

# ---------------------------------------------------------------- 环境
Step '定位 Android SDK / 构建工具'
if (-not $Sdk -or -not (Test-Path $Sdk)) {
    foreach ($cand in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
            (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), 'C:\Android\Sdk')) {
        if ($cand -and (Test-Path $cand)) { $Sdk = $cand; break }
    }
}
if (-not $Sdk -or -not (Test-Path $Sdk)) { throw '找不到 Android SDK，请用 -Sdk 指定' }
$BuildTools = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory -ErrorAction Stop |
    Sort-Object { ApiOf $_.Name } -Descending | Select-Object -First 1
$Platform = Get-ChildItem (Join-Path $Sdk 'platforms') -Directory -ErrorAction Stop |
    Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
    Sort-Object { ApiOf $_.Name } -Descending | Select-Object -First 1
if (-not $BuildTools) { throw 'build-tools 缺失' }
if (-not $Platform) { throw 'platforms\android-* 缺失' }

$Aapt2 = Join-Path $BuildTools.FullName 'aapt2.exe'
$D8 = Join-Path $BuildTools.FullName 'd8.bat'
$ZipAlign = Join-Path $BuildTools.FullName 'zipalign.exe'
$ApkSigner = Join-Path $BuildTools.FullName 'apksigner.bat'
$AndroidJar = Join-Path $Platform.FullName 'android.jar'
Ok "SDK        : $Sdk"
Ok "build-tools: $($BuildTools.Name)"
Ok "platform   : $($Platform.Name)"

# ---------------------------------------------------------------- 拷贝到 ASCII 工作目录
Step "准备构建目录 $Stage"
if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null
foreach ($item in @('src', 'res', 'assets', 'stubs', 'test', 'libs')) {
    Copy-Item (Join-Path $Root $item) -Destination $Stage -Recurse -Force
}
Copy-Item (Join-Path $Root 'AndroidManifest.xml') -Destination $Stage -Force
$Src = Join-Path $Stage 'src'
$ResDir = Join-Path $Stage 'res'
$AssetsDir = Join-Path $Stage 'assets'
$StubsDir = Join-Path $Stage 'stubs'
$TestDir = Join-Path $Stage 'test'
$BuildDir = Join-Path $Stage 'build'
$LibsDir = Join-Path $Stage 'libs'
New-Item -ItemType Directory -Force -Path $BuildDir, $LibsDir, (Join-Path $BuildDir 'dex') | Out-Null
Ok '工程已复制（aapt2 不支持中文路径）'

# ---------------------------------------------------------------- 单元测试
if (-not $SkipTests) {
    Step '编译并运行手势状态机单元测试'
    $testOut = Join-Path $BuildDir 'test-classes'
    New-Item -ItemType Directory -Force -Path $testOut | Out-Null
    Run 'javac' @('-encoding', 'UTF-8', '-d', $testOut,
        (Join-Path $Src 'io\github\jumaoli\aikey\hook\GestureEngine.java'),
        (Join-Path $TestDir 'GestureEngineTest.java')) '编译测试'
    Run 'java' @('-Dfile.encoding=UTF-8', '-cp', $testOut,
        'io.github.jumaoli.aikey.hook.GestureEngineTest') '运行测试' |
        ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }

    Step '编译并运行动作表自检'
    $actOut = Join-Path $BuildDir 'act-test'
    New-Item -ItemType Directory -Force -Path $actOut | Out-Null
    Run 'javac' @('-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-bootclasspath', $AndroidJar, '-classpath', $AndroidJar, '-d', $actOut,
        (Join-Path $Src 'io\github\jumaoli\aikey\ActionCatalog.java'),
        (Join-Path $TestDir 'ActionCatalogTest.java')) '编译动作表测试'
    Run 'java' @('-Dfile.encoding=UTF-8', '-cp', "$actOut;$AndroidJar",
        'io.github.jumaoli.aikey.ActionCatalogTest') '运行动作表测试' |
        ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    Ok '测试通过'
}

# ---------------------------------------------------------------- Xposed API 桩
Step '编译 Xposed API 桩（compileOnly，不进 APK）'
$stubClasses = Join-Path $BuildDir 'stub-classes'
New-Item -ItemType Directory -Force -Path $stubClasses | Out-Null
$stubJar = Join-Path $LibsDir 'xposed-api-stubs.jar'
$stubSources = Get-ChildItem $StubsDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Run 'javac' (@('-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-bootclasspath', $AndroidJar, '-d', $stubClasses) + $stubSources) '编译 Xposed 桩'
Run 'jar' @('cf', $stubJar, '-C', $stubClasses, '.') '打包 Xposed 桩'
Ok "xposed-api-stubs.jar $((Get-Item $stubJar).Length) bytes"

# Shizuku API：没有 root 时的 shell 通道（Stellar 兼容同一套 API）
$ShizukuJarList = @()
$ShizukuJars = ''
foreach ($libName in @()) {
    $libPath = Join-Path $Stage "libs\$libName"
    if (Test-Path $libPath) {
        $ShizukuJarList += $libPath
        if ($ShizukuJars) { $ShizukuJars += ';' }
        $ShizukuJars += $libPath
    }
}

# ---------------------------------------------------------------- 资源
Step 'aapt2 编译资源'
$resZip = Join-Path $BuildDir 'res.zip'
Run $Aapt2 @('compile', '--dir', $ResDir, '-o', $resZip) 'aapt2 compile'

Step 'aapt2 链接 APK（含 assets）'
$unsigned = Join-Path $BuildDir 'unsigned.apk'
$genDir = Join-Path $BuildDir 'gen'
New-Item -ItemType Directory -Force -Path $genDir | Out-Null
Run $Aapt2 @(
    'link', '-o', $unsigned,
    '-I', $AndroidJar,
    '--manifest', (Join-Path $Stage 'AndroidManifest.xml'),
    '-R', $resZip,
    '-A', $AssetsDir,
    '--auto-add-overlay',
    '--java', $genDir,
    '--min-sdk-version', '30',
    '--target-sdk-version', '34',
    '--version-code', "$VersionCode",
    '--version-name', $Version
) 'aapt2 link'
Ok "unsigned.apk $((Get-Item $unsigned).Length) bytes"

# ---------------------------------------------------------------- Java
Step 'javac 编译 Java 源码'
$classes = Join-Path $BuildDir 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem $Src -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem $genDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Run 'javac' (@('-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-bootclasspath', $AndroidJar,
        '-classpath', "$AndroidJar;$stubJar;$ShizukuJars",
        '-d', $classes) + $sources) 'javac'
Ok "$($sources.Count) 个源文件编译完成"

# ---------------------------------------------------------------- dex
Step 'd8 生成 classes.dex'
$dexOut = Join-Path $BuildDir 'dex'
# 先打成 jar 再喂给 d8：Windows 命令行有 8K 长度限制，类一多就传不下
$classJar = Join-Path $BuildDir 'classes.jar'
if (Test-Path $classJar) { Remove-Item $classJar -Force }
Run 'jar' @('cf', $classJar, '-C', $classes, '.') '打包 classes.jar'
Run $D8 (@('--debug', '--lib', $AndroidJar, '--min-api', '30', '--output', $dexOut, $classJar) + $ShizukuJarList) 'd8'
$dex = Join-Path $dexOut 'classes.dex'
if (-not (Test-Path $dex)) { throw 'classes.dex 没有生成' }
Ok "classes.dex $((Get-Item $dex).Length) bytes"

# ---------------------------------------------------------------- 合并 dex
Step '把 classes.dex 塞进 APK'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    $old = $zip.GetEntry('classes.dex')
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex, 'classes.dex') | Out-Null
} finally {
    $zip.Dispose()
}

# ---------------------------------------------------------------- 签名
Step 'zipalign + apksigner'
$aligned = Join-Path $BuildDir 'aligned.apk'
Run $ZipAlign @('-f', '4', $unsigned, $aligned) 'zipalign'

if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Keystore) | Out-Null
    Run 'keytool' @('-genkeypair', '-keystore', $Keystore, '-alias', 'aikey',
        '-storepass', $StorePass, '-keypass', $KeyPass, '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000', '-noprompt',
        '-dname', 'CN=AIKeyPlus LSP, O=AIKeyPlus, C=CN') '生成签名密钥'
    Ok "首次生成签名：$Keystore"
} else {
    Ok "复用已有签名：$Keystore"
}

$apk = Join-Path $BuildDir $ApkName
Run $ApkSigner @('sign', '--ks', $Keystore, '--ks-pass', "pass:$StorePass",
    '--key-pass', "pass:$KeyPass", '--out', $apk, $aligned) 'apksigner sign'
Run $ApkSigner @('verify', '--print-certs', $apk) 'apksigner verify' |
    ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }

# ---------------------------------------------------------------- 校验
Step '校验 APK 内容'
$badging = Run $Aapt2 @('dump', 'badging', $apk) 'aapt2 dump'
$badging | Select-Object -First 6 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }

$need = @('classes.dex', 'assets/xposed_init', 'assets/aikey_lib.sh', 'assets/dispatch.sh')
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $names = $zip.Entries | ForEach-Object { $_.FullName }
} finally {
    $zip.Dispose()
}
foreach ($n in $need) {
    if ($names -notcontains $n) { throw "APK 里缺少 $n" }
    Ok "存在 $n"
}
if ($names -contains 'de/robv/android/xposed/XposedBridge.class') {
    throw 'Xposed API 桩被打进 APK 了，运行时会和 LSPosed 冲突'
}
Ok 'Xposed API 桩没有被打进 APK（正确）'

# ---------------------------------------------------------------- 拷回项目
$DistDir = Join-Path $Root 'dist'
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
$final = Join-Path $DistDir $ApkName
Copy-Item $apk $final -Force
$size = [math]::Round((Get-Item $final).Length / 1024.0, 1)

Write-Host "`n构建完成：$final  ($size KB)" -ForegroundColor Green
Write-Host "安装：adb install -r `"$final`"" -ForegroundColor Green
