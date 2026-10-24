param(
    [Parameter(Mandatory)]
    [string] $ApkPath,
    [string] $ExpectedTag
)

$ErrorActionPreference = 'Stop'

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if (-not $sdk) {
    throw 'ANDROID_SDK_ROOT 或 ANDROID_HOME 未配置'
}

$releaseTag = if ([string]::IsNullOrWhiteSpace($ExpectedTag)) {
    if ($env:GITHUB_REF_TYPE -eq 'tag') { $env:GITHUB_REF_NAME } else { $null }
} else {
    $ExpectedTag
}
$expectedVersionName = $null
if ($releaseTag) {
    $tagMatch = [regex]::Match($releaseTag, '^v(?<version>[0-9][0-9A-Za-z.+-]*)$')
    if (-not $tagMatch.Success) {
        throw "发布 tag 必须使用 v<version> 格式：$releaseTag"
    }
    $expectedVersionName = $tagMatch.Groups['version'].Value
}

$buildTools = Join-Path $sdk 'build-tools\37.0.0'
$aapt = Join-Path $buildTools 'aapt.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$apkanalyzer = Join-Path $sdk 'cmdline-tools\latest\bin\apkanalyzer.bat'
if (-not (Test-Path -LiteralPath $apkanalyzer)) {
    $apkanalyzer = Get-ChildItem (Join-Path $sdk 'cmdline-tools') -Recurse -Filter 'apkanalyzer.bat' `
        -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
foreach ($tool in @($aapt, $apksigner, $apkanalyzer)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "缺少发布验证工具：$tool"
    }
}

$badging = (& $aapt dump badging $apk) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw 'aapt 无法读取 APK'
}
$packageMatch = [regex]::Match(
    $badging,
    "package:\s+name='(?<package>[^']+)'\s+versionCode='(?<versionCode>\d+)'\s+versionName='(?<versionName>[^']+)'")
if (-not $packageMatch.Success) {
    throw 'APK 缺少可识别的包名或版本元数据'
}
$packageName = $packageMatch.Groups['package'].Value
$versionCode = [int]$packageMatch.Groups['versionCode'].Value
$versionName = $packageMatch.Groups['versionName'].Value
if ($packageName -ne 'io.github.jiangjinyue2333.actiongrid') {
    throw "APK 包名不正确：$packageName"
}
if ($versionCode -le 0 -or $versionName -notmatch '^[0-9][0-9A-Za-z.+-]*$') {
    throw "APK 版本元数据不可发布：versionName=$versionName versionCode=$versionCode"
}
if ($expectedVersionName -and $versionName -ne $expectedVersionName) {
    throw "tag $releaseTag 与 APK versionName $versionName 不一致"
}
foreach ($expected in @(
    "sdkVersion:'34'",
    "targetSdkVersion:'37'"
)) {
    if (-not $badging.Contains($expected)) {
        throw "APK 元数据不正确：缺少 $expected"
    }
}
if ($badging -notmatch 'compileSdkVersion[^0-9]*37') {
    throw 'APK compile SDK 不是 37'
}

& (Join-Path $PSScriptRoot 'verify-apk-abi.ps1') -ApkPath $apk

& $apksigner verify --verbose $apk | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'APK 签名验证失败'
}

$certificateOutput = (& $apksigner verify --verbose --print-certs $apk) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw '无法读取 APK 签名证书'
}
$certificateMatch = [regex]::Match(
    $certificateOutput,
    'certificate SHA-256 digest: ([0-9a-fA-F]{64})')
if (-not $certificateMatch.Success) {
    throw 'APK 缺少可识别的签名证书摘要'
}
$certificateSha256 = $certificateMatch.Groups[1].Value.ToLowerInvariant()
$expectedCertificateSha256 = 'e000d876a95db3c3ea565e8650d19b81d75a674f1926e9889d4cf5a54aeb1f05'
if ($certificateSha256 -ne $expectedCertificateSha256) {
    throw 'APK 使用了非当前 ActionGrid 发布证书；禁止生成无法覆盖升级的发布包'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    $expectedEntries = @{
        'META-INF/xposed/java_init.list' = 'io.github.jiangjinyue2333.actiongrid.hook.ActionGridModule'
        'META-INF/xposed/native_init.list' = 'libactiongrid_native.so'
        'META-INF/xposed/module.prop' = "minApiVersion=102`ntargetApiVersion=102`nstaticScope=true"
        'META-INF/xposed/scope.list' = 'com.miui.home'
    }
    foreach ($pair in $expectedEntries.GetEnumerator()) {
        $entry = $archive.GetEntry($pair.Key)
        if ($null -eq $entry) {
            throw "APK 缺少 $($pair.Key)"
        }
        $reader = [IO.StreamReader]::new($entry.Open())
        try {
            $actual = $reader.ReadToEnd().Replace("`r`n", "`n").Trim()
        } finally {
            $reader.Dispose()
        }
        if ($actual -ne $pair.Value) {
            throw "APK 中 $($pair.Key) 内容不正确"
        }
    }
} finally {
    $archive.Dispose()
}

$defined = (& $apkanalyzer dex packages --defined-only $apk) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw 'apkanalyzer 无法读取 DEX'
}
if ($defined -notmatch '(?m)^C\s+\S+\s+\d+\s+\d+\s+\d+\s+io\.github\.jiangjinyue2333\.actiongrid\.hook\.ActionGridModule$') {
    throw 'Release DEX 中缺少 ActionGridModule'
}
if ($defined -match '(?m)^C\s+.*(?:de\.robv\.android\.xposed|io\.github\.libxposed\.api)\.') {
    throw 'APK 错误打包了 Xposed API 实现类'
}

$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "发布 APK 验证通过：versionName=$versionName、versionCode=$versionCode、Android 14–17、Modern API 102、arm64 原生库、静态作用域、DEX 入口与签名均正确。"
Write-Output "SHA-256: $hash"
