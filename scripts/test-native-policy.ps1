param([string] $NdkDirectory)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $NdkDirectory) {
    $sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
    if (-not $sdk) { throw '请设置 ANDROID_SDK_ROOT 或指定 NdkDirectory' }
    $ndk = Get-ChildItem -LiteralPath (Join-Path $sdk 'ndk') -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'source.properties') } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $ndk) { throw '缺少已安装的 NDK' }
    $NdkDirectory = $ndk.FullName
}
$toolchain = Join-Path $NdkDirectory 'toolchains/llvm/prebuilt/windows-x86_64'
& (Join-Path $toolchain 'bin/clang++.exe') --target=aarch64-linux-android34 `
    "--sysroot=$toolchain/sysroot" -std=c++17 -fsyntax-only `
    (Join-Path $root 'app/src/test/cpp/native_abi_profiles_test.cpp')
if ($LASTEXITCODE -ne 0) { throw 'Native ABI 能力分级回归失败' }
Write-Output 'Native ABI 回归通过：7748 操作号、未知接口降级、完整系统配对和文件夹边界。'
