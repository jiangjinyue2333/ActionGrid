param(
    [ValidateSet('x86_64', 'arm64-v8a')][string] $Abi = 'x86_64',
    [string] $SdkDirectory = 'D:\Android\Sdk',
    [string] $JdkDirectory = 'D:\Dev\Tools\jdk-21\jdk-21.0.12+8',
    [string] $NdkDirectory = 'D:\Android\Sdk\ndk\27.0.12077973'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'app/src/test/readers'
$output = Join-Path $root 'artifacts/drag-20261020/reader-fixture'
$classes = Join-Path $output 'classes'
$dex = Join-Path $output 'dex'
$native = Join-Path $output "lib/$Abi"
foreach ($directory in @($output, $classes, $dex, $native)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
$buildTools = Join-Path $SdkDirectory 'build-tools/35.0.0'
$android = Join-Path $SdkDirectory 'platforms/android-34/android.jar'
$toolchain = Join-Path $NdkDirectory 'toolchains/llvm/prebuilt/windows-x86_64'
$target = if ($Abi -eq 'x86_64') { 'x86_64-linux-android28' } else { 'aarch64-linux-android28' }
& (Join-Path $toolchain 'bin/clang++.exe') "--target=$target" "--sysroot=$toolchain/sysroot" `
    -std=c++17 -O1 -g -fPIC -shared -ffunction-sections -fdata-sections '-Wl,--gc-sections' `
    -static-libstdc++ -llog -ldl (Join-Path $source 'reader_fixture.cpp') -o (Join-Path $native 'libreader_fixture.so')
if ($LASTEXITCODE -ne 0) { throw 'Reader fixture native compile failed' }
& (Join-Path $JdkDirectory 'bin/javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath $android -d $classes (Join-Path $source 'ReaderFixtureActivity.java')
if ($LASTEXITCODE -ne 0) { throw 'Reader fixture Java compile failed' }
$env:JAVA_HOME = $JdkDirectory
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
& (Join-Path $buildTools 'd8.bat') --lib $android --min-api 28 --output $dex @classFiles
if ($LASTEXITCODE -ne 0) { throw 'Reader fixture dex failed' }
$unsigned = Join-Path $output 'unsigned.apk'
$aligned = Join-Path $output 'aligned.apk'
$apk = Join-Path $output 'reader-fixture.apk'
& (Join-Path $buildTools 'aapt2.exe') link -I $android --manifest (Join-Path $source 'AndroidManifest.xml') -o $unsigned
if ($LASTEXITCODE -ne 0) { throw 'Reader fixture resource packaging failed' }
& (Join-Path $JdkDirectory 'bin/jar.exe') uf $unsigned -C $dex classes.dex -C $output "lib/$Abi/libreader_fixture.so"
if ($LASTEXITCODE -ne 0) { throw 'Reader fixture APK assembly failed' }
& (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'Reader fixture alignment failed' }
$keyDirectory = 'D:\Android\Keystores\ActionGridReaderFixture'
[IO.Directory]::CreateDirectory($keyDirectory) | Out-Null
$key = Join-Path $keyDirectory 'fixture-only.keystore'
$keyPassword = Join-Path $keyDirectory 'fixture-password.txt'
if (-not (Test-Path -LiteralPath $key)) {
    $randomPassword = New-Object byte[] 32
    $randomSource = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $randomSource.GetBytes($randomPassword) } finally { $randomSource.Dispose() }
    [IO.File]::WriteAllText($keyPassword, [Convert]::ToBase64String($randomPassword))
    & (Join-Path $JdkDirectory 'bin/keytool.exe') -genkeypair -keystore $key "-storepass:file" $keyPassword "-keypass:file" $keyPassword -alias fixture -dname 'CN=ActionGrid Local Reader Fixture' -keyalg RSA -validity 10000 -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Reader fixture test key generation failed' }
}
if (-not (Test-Path -LiteralPath $keyPassword)) { throw 'Local fixture key password file is missing' }
& (Join-Path $buildTools 'apksigner.bat') sign --ks $key --ks-pass "file:$keyPassword" --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'Reader fixture signing failed' }
& (Join-Path $buildTools 'apksigner.bat') verify $apk
if ($LASTEXITCODE -ne 0) { throw 'Reader fixture signature verify failed' }
Write-Output "PASS: test-only native reader fixture: $apk ($Abi)"
