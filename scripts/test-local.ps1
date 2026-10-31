# 在纯 ASCII 的临时目录里跑单元测试。
#
# 为什么需要这个脚本：
#   工程路径含中文时，JVM 无法从含中文的 classpath 目录加载测试类，会抛
#   `java.lang.ClassNotFoundException: com.example.videoplayer.xxxTest`。
#   这与 Gradle 缓存位置无关（把 GRADLE_USER_HOME 指到 ASCII 路径也不够），
#   只和「工程所在的绝对路径」有关。
#   所以这里先把工程同步到 %TEMP%\vp_test（纯 ASCII），再在那里跑测试。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\test-local.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\test-local.ps1 -KeepBuild   # 复用上次的临时目录
#
# 注意：编译和打 Release 包不受中文路径影响，只有跑测试需要这个绕法。
#       GitHub Actions 的 runner 路径本身是 ASCII，CI 里直接跑 gradlew 即可。

param(
    [switch]$KeepBuild
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "AndroidVideoPlayer"
$dst = Join-Path $env:TEMP "vp_test"

if (-not (Test-Path $src)) { throw "找不到工程目录: $src" }

Write-Host "[1/3] 同步工程到 $dst ..." -ForegroundColor Cyan
if ($KeepBuild -and (Test-Path $dst)) {
    robocopy $src $dst /E /XD build .gradle /NFL /NDL /NJH /NJS | Out-Null
} else {
    if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
    robocopy $src $dst /E /XD build .gradle /NFL /NDL /NJH /NJS | Out-Null
}

$gradle = Join-Path $root "_tools\gradle-8.7\bin\gradle.bat"
if (-not (Test-Path $gradle)) { throw "找不到 gradle: $gradle（本机工具链在 _tools 下）" }

$env:JAVA_HOME = Join-Path $root "_tools\jdk17"
$env:ANDROID_HOME = Join-Path $root "_tools\sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $root "_tools\android-home"
# 缓存放到 ASCII 路径，避免 argfile 路径含中文
$env:GRADLE_USER_HOME = Join-Path $env:TEMP "gradle-home"

Write-Host "[2/3] 跑单元测试 ..." -ForegroundColor Cyan
$log = Join-Path $dst "test-output.log"
& $gradle -p $dst :app:testDebugUnitTest --no-daemon --console=plain *> $log
$code = $LASTEXITCODE

$report = Join-Path $dst "app\build\reports\tests\testDebugUnitTest\index.html"
if (Test-Path $report) {
    Write-Host "      报告: $report" -ForegroundColor Green
}
Write-Host "      日志: $log"

if ($code -ne 0) {
    Write-Host "[3/3] 测试失败（exit $code），日志末尾如下：" -ForegroundColor Red
    Get-Content $log -Tail 30 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    exit 1
}
Write-Host "[3/3] 测试通过" -ForegroundColor Green
exit 0
