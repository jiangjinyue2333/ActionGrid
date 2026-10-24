param(
    [Parameter(Mandatory)]
    [string] $ApkPath
)

$ErrorActionPreference = 'Stop'
$verify = Join-Path $PSScriptRoot 'verify-apk-abi.ps1'
& $verify -ApkPath $ApkPath

$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
$aapt = Join-Path $sdk 'build-tools\37.0.0\aapt.exe'
$androidJar = @(
    (Join-Path $sdk 'platforms\android-37\android.jar'),
    (Join-Path $sdk 'platforms\android-37.0\android.jar')
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $androidJar) {
    throw '缺少 Android 37 android.jar，无法生成安装 ABI 回归样例'
}
$fixtures = Join-Path (Split-Path -Parent $PSScriptRoot) 'app\build\abi-regression'
[IO.Directory]::CreateDirectory($fixtures) | Out-Null
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
try {
    $entry = $archive.GetEntry('lib/arm64-v8a/libactiongrid_native.so')
    $reader = [IO.BinaryReader]::new($entry.Open())
    try {
        $nativeBytes = $reader.ReadBytes([int]$entry.Length)
    } finally {
        $reader.Dispose()
    }
} finally {
    $archive.Dispose()
}

function New-AbiFixture {
    param([string] $Name, [string] $Attributes, [hashtable] $Libraries)

    $manifestPath = Join-Path $fixtures 'AndroidManifest.xml'
    [IO.File]::WriteAllText($manifestPath, @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.jiangjinyue2333.actiongrid.abifixture">
    <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="37" />
    <application android:hasCode="false" $Attributes />
</manifest>
"@)
    $fixture = Join-Path $fixtures "$Name.apk"
    & $aapt package -f -M $manifestPath -I $androidJar -F $fixture
    if ($LASTEXITCODE -ne 0) {
        throw "无法编译 ABI 回归样例：$Name"
    }
    $zip = [IO.Compression.ZipFile]::Open($fixture, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($entry in $Libraries.GetEnumerator()) {
            $stream = $zip.CreateEntry($entry.Key).Open()
            try {
                $stream.Write($entry.Value, 0, $entry.Value.Length)
            } finally {
                $stream.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
    }
    return $fixture
}

function Assert-Rejected {
    param([string] $Fixture, [string] $ExpectedMessage)

    try {
        & $verify -ApkPath $Fixture
    } catch {
        if (-not $_.Exception.Message.Contains($ExpectedMessage)) { throw }
        Write-Output "正确拒绝：$([IO.Path]::GetFileName($Fixture))"
        return
    }
    throw "ABI 回归验证漏报：$Fixture"
}

$nativePath = 'lib/arm64-v8a/libactiongrid_native.so'
$libraries = @{ $nativePath = $nativeBytes }
& $verify -ApkPath (New-AbiFixture 'single-abi-default' '' $libraries)
& $verify -ApkPath (New-AbiFixture 'single-abi-explicit' 'android:multiArch="false"' $libraries)
Assert-Rejected (New-AbiFixture 'multiarch-arm64-only' 'android:multiArch="true"' $libraries) 'Error deriving application ABI'
Assert-Rejected (New-AbiFixture 'missing-native' '' @{}) 'APK 缺少'
Assert-Rejected (New-AbiFixture 'empty-native' '' @{ $nativePath = [byte[]]@() }) '不是有效的 ARM64'
$wrongMachine = $nativeBytes.Clone()
$wrongMachine[18] = 62 # EM_X86_64 disguised as arm64-v8a.
Assert-Rejected (New-AbiFixture 'wrong-elf-machine' '' @{ $nativePath = $wrongMachine }) '不是有效的 ARM64'
Assert-Rejected (New-AbiFixture 'unexpected-abi' '' @{
    $nativePath = $nativeBytes
    'lib/x86_64/libunexpected.so' = $wrongMachine
}) '未支持的 native ABI'
Write-Output 'APK ABI 回归验证通过：默认/显式单 ABI，以及多 ABI 错误声明、缺库、空库、错误 ELF 和意外架构。'
