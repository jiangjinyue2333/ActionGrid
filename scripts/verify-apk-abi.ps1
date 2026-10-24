param(
    [Parameter(Mandatory)]
    [string] $ApkPath
)

$ErrorActionPreference = 'Stop'
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if (-not $sdk) {
    throw 'ANDROID_SDK_ROOT 或 ANDROID_HOME 未配置'
}
$aapt = Join-Path $sdk 'build-tools\37.0.0\aapt.exe'
if (-not (Test-Path -LiteralPath $aapt)) {
    throw "缺少 APK ABI 验证工具：$aapt"
}

$manifest = @(& $aapt dump xmltree $apk AndroidManifest.xml)
if ($LASTEXITCODE -ne 0) {
    throw 'aapt 无法读取 APK manifest'
}
foreach ($attribute in @('multiArch', 'use32bitAbi')) {
    $declarations = @($manifest | Where-Object { $_ -match "A: android:$attribute\(" })
    if ($declarations.Count -gt 1 -or ($declarations.Count -eq 1 -and
            $declarations[0] -notmatch '\(type 0x12\)0x0\s*$')) {
        throw "APK 仅提供 arm64-v8a，不得启用 android:$attribute；否则可能发生 Error deriving application ABI"
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    $libraries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
    if ($null -eq $archive.GetEntry('lib/arm64-v8a/libactiongrid_native.so')) {
        throw 'APK 缺少 arm64-v8a/libactiongrid_native.so'
    }
    foreach ($library in $libraries) {
        if ($library.FullName -notmatch '^lib/arm64-v8a/') {
            throw "APK 包含未支持的 native ABI：$($library.FullName)"
        }
        $reader = [IO.BinaryReader]::new($library.Open())
        try {
            $header = $reader.ReadBytes(64)
        } finally {
            $reader.Dispose()
        }
        # ELF64, little-endian, ET_DYN and EM_AARCH64 must agree with the APK directory.
        if ($header.Length -ne 64 -or
                $header[0] -ne 0x7f -or $header[1] -ne 0x45 -or
                $header[2] -ne 0x4c -or $header[3] -ne 0x46 -or
                $header[4] -ne 2 -or $header[5] -ne 1 -or
                $header[16] -ne 3 -or $header[17] -ne 0 -or
                $header[18] -ne 183 -or $header[19] -ne 0) {
            throw "APK native 库不是有效的 ARM64 共享库：$($library.FullName)"
        }
    }
} finally {
    $archive.Dispose()
}

Write-Output "APK ABI 验证通过：arm64-v8a、ELF64 ARM64、单 ABI 安装声明（$($libraries.Count) 个原生库）。"
