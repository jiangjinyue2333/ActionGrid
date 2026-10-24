param(
    [Parameter(Mandatory = $true)][string] $Serial,
    [string] $NdkDirectory
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if (-not $sdk) { throw '请设置 ANDROID_SDK_ROOT' }
if (-not $NdkDirectory) {
    $NdkDirectory = (Get-ChildItem -LiteralPath (Join-Path $sdk 'ndk') -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'source.properties') } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).FullName
}
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw '指定设备未连接；本脚本不会新建或启动模拟器' }
$target = switch ($abi) {
    'x86_64' { 'x86_64-linux-android34' }
    'arm64-v8a' { 'aarch64-linux-android34' }
    default { throw "不支持的测试 ABI：$abi" }
}
$output = Join-Path $root 'artifacts/hyperos4-grid'
[IO.Directory]::CreateDirectory($output) | Out-Null
$binary = Join-Path $output 'native-grid-test'
$toolchain = Join-Path $NdkDirectory 'toolchains/llvm/prebuilt/windows-x86_64'
& (Join-Path $toolchain 'bin/clang++.exe') "--target=$target" `
    "--sysroot=$toolchain/sysroot" -std=c++17 -O1 -ffunction-sections -fdata-sections `
    '-Wl,--gc-sections' -static-libstdc++ -llog -ldl `
    (Join-Path $root 'app/src/test/cpp/native_grid_test.cpp') -o $binary
if ($LASTEXITCODE -ne 0) { throw 'Native 网格测试编译失败' }
$remote = '/data/local/tmp/actiongrid-native-grid-test'
& $adb -s $Serial push $binary $remote
if ($LASTEXITCODE -ne 0) { throw '无法传输 Native 网格测试' }
& $adb -s $Serial shell chmod 700 $remote
if ($LASTEXITCODE -ne 0) { throw '无法设置测试执行权限' }
& $adb -s $Serial shell $remote
if ($LASTEXITCODE -ne 0) { throw 'Native 网格回归失败' }
