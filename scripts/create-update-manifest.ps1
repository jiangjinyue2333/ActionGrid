param(
    [Parameter(Mandatory)]
    [string] $ApkPath,
    [Parameter(Mandatory)]
    [string] $ReleaseTag,
    [Parameter(Mandatory)]
    [string] $OutputPath,
    [string[]] $Changelog = @('GitHub Actions 正式签名构建')
)

$ErrorActionPreference = 'Stop'

if ($ReleaseTag -notmatch '^v[0-9][0-9A-Za-z.+-]*$') {
    throw "发布 tag 必须使用 v<version> 格式：$ReleaseTag"
}

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($sdk)) {
    throw 'ANDROID_SDK_ROOT 或 ANDROID_HOME 未配置'
}
$aapt = Join-Path $sdk 'build-tools\37.0.0\aapt.exe'
if (-not (Test-Path -LiteralPath $aapt)) {
    throw "缺少 aapt：$aapt"
}

$badging = (& $aapt dump badging $apk) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw 'aapt 无法读取正式 APK'
}
$metadataMatch = [regex]::Match(
    $badging,
    "package:\s+name='(?<package>[^']+)'\s+versionCode='(?<versionCode>\d+)'\s+versionName='(?<versionName>[^']+)'"
)
if (-not $metadataMatch.Success) {
    throw '无法从 APK 读取版本元数据'
}
$packageName = $metadataMatch.Groups['package'].Value
$versionCode = [int]$metadataMatch.Groups['versionCode'].Value
$versionName = $metadataMatch.Groups['versionName'].Value
if ($packageName -ne 'io.github.jiangjinyue2333.actiongrid') {
    throw "APK 包名不正确：$packageName"
}
if ($versionCode -le 0 -or $versionName -notmatch '^[0-9][0-9A-Za-z.+-]*$') {
    throw "APK 版本元数据不可发布：versionName=$versionName versionCode=$versionCode"
}
$expectedTag = "v$versionName"
if ($ReleaseTag -ne $expectedTag) {
    throw "tag $ReleaseTag 与 APK versionName $versionName 不一致"
}

$file = Get-Item -LiteralPath $apk
$sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$publishedAt = [DateTimeOffset]::UtcNow.ToString(
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    [Globalization.CultureInfo]::InvariantCulture)
$manifest = [ordered]@{
    schemaVersion = 1
    packageName = $packageName
    versionCode = $versionCode
    versionName = $versionName
    minSdk = 34
    apkUrl = "https://github.com/jiangjinyue2333/ActionGrid/releases/download/$ReleaseTag/ActionGrid-$versionName.apk"
    sha256 = $sha256
    sizeBytes = [int64]$file.Length
    publishedAt = $publishedAt
    changelog = @($Changelog | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}
if ($manifest.changelog.Count -eq 0) {
    throw '更新清单至少需要一条 changelog'
}

$output = [IO.Path]::GetFullPath($OutputPath)
$directory = Split-Path -Parent $output
[IO.Directory]::CreateDirectory($directory) | Out-Null
$json = $manifest | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText($output, $json + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false))
Write-Output "更新清单：$output (versionName=$versionName, versionCode=$versionCode, sha256=$sha256)"
