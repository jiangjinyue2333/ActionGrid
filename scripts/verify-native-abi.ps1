param(
    [string] $PrivateLibrary = 'tmp/home/sys/libhyper_os_accessibility_private.dylib.so',
    [string] $PublicLibrary = 'tmp/home/sys/libhyper_os_accessibility_public.so',
    [string] $LauncherLibrary = 'tmp/system-apk/lib/arm64-v8a/libapp_launcher.so',
    [string] $DartLibrary = 'tmp/system-apk/lib/arm64-v8a/libapp.so',
    [string] $AidlLibrary = 'tmp/home/sys/libhyper_os_aidl.dylib.so',
    [string] $UiLibrary = 'tmp/home/sys/libhyper_os_ui_public.so'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

public static class ActionGridElfFingerprint
{
    private const ulong Prime = 1099511628211UL;

    private struct Range
    {
        public ulong Start;
        public ulong End;
    }

    private struct Load
    {
        public ulong Offset;
        public ulong FileSize;
        public uint Flags;
        public ulong MemorySize;
        public ulong Align;
    }

    private static ushort U16(byte[] bytes, int offset)
        => BitConverter.ToUInt16(bytes, offset);

    private static uint U32(byte[] bytes, int offset)
        => BitConverter.ToUInt32(bytes, offset);

    private static ulong U64(byte[] bytes, int offset)
        => BitConverter.ToUInt64(bytes, offset);

    private static void AddRange(List<Range> ranges, ulong start, ulong size, int length)
    {
        if (size == 0) return;
        if (start > (ulong)length || size > (ulong)length - start)
            throw new InvalidDataException("ELF range is outside the file");
        ranges.Add(new Range { Start = start, End = start + size });
    }

    private static string SectionName(byte[] bytes, ulong offset, ulong size, uint name)
    {
        if (name >= size || offset > (ulong)bytes.Length
                || size > (ulong)bytes.Length - offset)
            throw new InvalidDataException("ELF section name is invalid");
        int start = checked((int)(offset + name));
        int end = checked((int)(offset + size));
        int zero = Array.IndexOf(bytes, (byte)0, start, end - start);
        if (zero < 0) throw new InvalidDataException("ELF section name is unterminated");
        return Encoding.ASCII.GetString(bytes, start, zero - start);
    }

    private static void HashByte(ref ulong first, ref ulong second, byte value)
    {
        unchecked
        {
            first = (first ^ value) * Prime;
            second = (second ^ (byte)(value ^ 0xa5U)) * Prime;
        }
    }

    private static void HashWord(ref ulong first, ref ulong second, ulong value)
    {
        for (int shift = 0; shift < 64; shift += 8)
            HashByte(ref first, ref second, (byte)((value >> shift) & 0xffU));
    }

    public static string Compute(string path)
    {
        byte[] bytes = File.ReadAllBytes(path);
        if (bytes.Length < 64 || bytes[0] != 0x7f || bytes[1] != 0x45
                || bytes[2] != 0x4c || bytes[3] != 0x46
                || bytes[4] != 2 || bytes[5] != 1)
            throw new InvalidDataException("ELF64 little-endian file expected");

        ulong phoff = U64(bytes, 32);
        ushort phentsize = U16(bytes, 54);
        ushort phnum = U16(bytes, 56);
        if (phentsize != 56 || phoff > (ulong)bytes.Length
                || (ulong)phnum * phentsize > (ulong)bytes.Length - phoff)
            throw new InvalidDataException("ELF program table is invalid");

        var ranges = new List<Range>();
        var loads = new List<Load>();
        for (int index = 0; index < phnum; ++index)
        {
            int offset = checked((int)(phoff + (ulong)index * phentsize));
            uint type = U32(bytes, offset);
            uint flags = U32(bytes, offset + 4);
            ulong fileOffset = U64(bytes, offset + 8);
            ulong fileSize = U64(bytes, offset + 32);
            ulong memorySize = U64(bytes, offset + 40);
            ulong align = U64(bytes, offset + 48);
            if (type == 4) AddRange(ranges, fileOffset, fileSize, bytes.Length);
            if (type == 1)
                loads.Add(new Load {
                    Offset = fileOffset,
                    FileSize = fileSize,
                    Flags = flags,
                    MemorySize = memorySize,
                    Align = align,
                });
        }

        ulong shoff = U64(bytes, 40);
        ushort shentsize = U16(bytes, 58);
        ushort shnum = U16(bytes, 60);
        ushort shstrndx = U16(bytes, 62);
        if (shoff != 0 && shnum != 0 && shentsize == 64)
        {
            ulong sectionBytes = (ulong)shnum * shentsize;
            if (shoff > (ulong)bytes.Length || sectionBytes > (ulong)bytes.Length - shoff
                    || shstrndx >= shnum)
                throw new InvalidDataException("ELF section table is invalid");
            int namesOffset = checked((int)(shoff + (ulong)shstrndx * shentsize));
            ulong namesFileOffset = U64(bytes, namesOffset + 24);
            ulong namesSize = U64(bytes, namesOffset + 32);
            if (namesFileOffset > (ulong)bytes.Length
                    || namesSize > (ulong)bytes.Length - namesFileOffset)
                throw new InvalidDataException("ELF section names are invalid");
            for (int index = 0; index < shnum; ++index)
            {
                int offset = checked((int)(shoff + (ulong)index * shentsize));
                string name = SectionName(bytes, namesFileOffset, namesSize, U32(bytes, offset));
                if (name.StartsWith(".note.", StringComparison.Ordinal)
                        || name == ".comment")
                    AddRange(ranges, U64(bytes, offset + 24), U64(bytes, offset + 32),
                            bytes.Length);
            }
        }

        ulong first = 14695981039346656037UL;
        ulong second = 1099511628211UL;
        HashByte(ref first, ref second, (byte)'A');
        HashByte(ref first, ref second, (byte)'G');
        HashByte(ref first, ref second, (byte)'E');
        HashByte(ref first, ref second, (byte)'L');
        HashByte(ref first, ref second, (byte)'F');
        HashByte(ref first, ref second, 1);
        HashWord(ref first, ref second, U16(bytes, 16));
        HashWord(ref first, ref second, U16(bytes, 18));

        ulong loadCount = 0;
        foreach (Load load in loads)
        {
            if (load.Offset > (ulong)bytes.Length
                    || load.FileSize > (ulong)bytes.Length - load.Offset)
                throw new InvalidDataException("ELF load segment is invalid");
            HashByte(ref first, ref second, 0x4c);
            HashWord(ref first, ref second, load.Offset);
            HashWord(ref first, ref second, load.FileSize);
            HashWord(ref first, ref second, load.MemorySize);
            HashWord(ref first, ref second, load.Flags);
            HashWord(ref first, ref second, load.Align);
            ulong position = load.Offset;
            ulong end = load.Offset + load.FileSize;
            while (position < end)
            {
                ulong next = end;
                bool skip = false;
                foreach (Range range in ranges)
                {
                    if (position >= range.Start && position < range.End)
                    {
                        skip = true;
                        if (range.End < next) next = range.End;
                    }
                    else if (range.Start > position && range.Start < next)
                        next = range.Start;
                }
                if (next <= position) throw new InvalidDataException("ELF range did not advance");
                if (!skip)
                    for (ulong index = position; index < next; ++index)
                        HashByte(ref first, ref second, bytes[checked((int)index)]);
                position = next;
            }
            ++loadCount;
        }
        if (loadCount == 0) throw new InvalidDataException("ELF has no load segments");
        HashWord(ref first, ref second, loadCount);
        return first.ToString("x16") + second.ToString("x16");
    }
}
'@

function Find-NdkTool([string] $name) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $sdk = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdk)) {
        $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    $candidate = @(Get-ChildItem (Join-Path $sdk 'ndk') -Recurse -Filter "$name.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1)
    if (-not $candidate) { throw "找不到 NDK 工具：$name" }
    return $candidate.FullName
}

function Resolve-PathFromRoot([string] $path) {
    if ([IO.Path]::IsPathRooted($path)) { return $path }
    return Join-Path $root $path
}

function Build-Id([string] $path, [string] $readelf) {
    $output = & $readelf -n $path | Out-String
    $match = [regex]::Match($output, 'Build ID:\s*([0-9a-f]+)')
    if (-not $match.Success) { throw "没有 Build ID：$path" }
    return $match.Groups[1].Value
}

function Fingerprint([string] $path) {
    return [ActionGridElfFingerprint]::Compute($path)
}

function Require-Symbol([string] $symbols, [string] $symbol) {
    if ($symbols -notmatch [regex]::Escape($symbol)) {
        throw "缺少导出符号：$symbol"
    }
}

$privatePath = Resolve-PathFromRoot $PrivateLibrary
$publicPath = Resolve-PathFromRoot $PublicLibrary
$launcherPath = Resolve-PathFromRoot $LauncherLibrary
$dartPath = Resolve-PathFromRoot $DartLibrary
$aidlPath = Resolve-PathFromRoot $AidlLibrary
$uiPath = Resolve-PathFromRoot $UiLibrary
if (-not (Test-Path -LiteralPath $privatePath)) { throw "缺少私有 Rust 库：$PrivateLibrary" }
if (-not (Test-Path -LiteralPath $publicPath)) { throw "缺少公开 Rust 库：$PublicLibrary" }
if (-not (Test-Path -LiteralPath $launcherPath)) { throw "缺少桌面 Rust 库：$LauncherLibrary" }
if (-not (Test-Path -LiteralPath $dartPath)) { throw "缺少桌面 Dart 库：$DartLibrary" }
if (-not (Test-Path -LiteralPath $aidlPath)) { throw "缺少 AIDL Rust 库：$AidlLibrary" }
if (-not (Test-Path -LiteralPath $uiPath)) { throw "缺少 UI Rust 库：$UiLibrary" }

$readelf = Find-NdkTool 'llvm-readelf'
$nm = Find-NdkTool 'llvm-nm'
$objdump = Find-NdkTool 'llvm-objdump'
$privateId = Build-Id $privatePath $readelf
$publicId = Build-Id $publicPath $readelf
$aidlId = Build-Id $aidlPath $readelf
$uiId = Build-Id $uiPath $readelf
$dartId = Build-Id $dartPath $readelf
$privateFingerprint = Fingerprint $privatePath
$aidlFingerprint = Fingerprint $aidlPath
$uiFingerprint = Fingerprint $uiPath
$publicFingerprint = Fingerprint $publicPath
$dartFingerprint = Fingerprint $dartPath
$launcherFingerprint = Fingerprint $launcherPath
Write-Output "FINGERPRINT private=$privateFingerprint aidl=$aidlFingerprint ui=$uiFingerprint public=$publicFingerprint dart=$dartFingerprint launcher=$launcherFingerprint"
$systemFingerprintKey = "$privateFingerprint|$aidlFingerprint|$uiFingerprint"
$legacySystemFingerprintKey = '5b7a19c56d7704c7f5c729612d74d0de|d884584d8d3d6fb8cd7b61ccbcf2dab1|06ad928995604d98e150a44005597071'
$currentSystemFingerprintKey = '5b7a19c56d7704c7f5c729612d74d0de|daf46dda4c2b40d4ceb378cc00132dc9|0447f0269df2c72f98e52e43a1f1887a'
$systemProfile = switch ($systemFingerprintKey) {
    $legacySystemFingerprintKey { 'legacy' }
    $currentSystemFingerprintKey { 'current8565' }
    default { throw "系统 Rust ABI 指纹配对不匹配：$systemFingerprintKey" }
}
if ($publicFingerprint -ne 'e3cc5ece19a86f1968095ade224bb05b') {
    throw "公开 Rust 库 ELF 指纹不匹配：$publicFingerprint (Build ID=$publicId)"
}
$launcherFingerprintKey = $launcherFingerprint
$launcherProfile = switch ($launcherFingerprintKey) {
    '0676d70c1d6227d6e899ab4db826315e' { 'legacy7653' }
    '6aafec65d595b957d78ecbfd33f5b7af' { 'legacy7722' }
    '6018f13c7a9d6274d0b4054c1d248904' { 'legacy7748' }
    'd43e8de27a633a7e770a572f4b6ddfc6' { 'current8565' }
    default { throw "桌面 ELF 指纹未验证：$launcherFingerprintKey (Build ID=$dartId)" }
}
if (($systemProfile -eq 'current8565') -ne ($launcherProfile -eq 'current8565')) {
    throw "系统与桌面 ABI profile 不匹配：system=$systemProfile launcher=$launcherProfile"
}

# A Build ID-only refresh must keep the same structural fingerprint.
$refreshedPrivate = Join-Path $root 'tmp/current-system-20261018/libhyper_os_accessibility_private.dylib.so'
$refreshedAidl = Join-Path $root 'tmp/current-system-20261018/libhyper_os_aidl.dylib.so'
if ((Test-Path -LiteralPath $refreshedPrivate) -and ((Fingerprint $refreshedPrivate) -ne $privateFingerprint)) {
    throw 'Build ID-only private refresh changed the structural fingerprint'
}
if ((Test-Path -LiteralPath $refreshedAidl) -and ((Fingerprint $refreshedAidl) -ne $aidlFingerprint) -and $systemProfile -eq 'current8565') {
    throw 'Build ID-only AIDL refresh changed the structural fingerprint'
}

function Verify-WorkspaceReloadPrologue([string] $path, [string] $tool, [UInt64] $offset) {
    $start = '0x{0:x}' -f $offset
    $stop = '0x{0:x}' -f ($offset + 16)
    $disassembly = & $tool -d "--start-address=$start" "--stop-address=$stop" $path | Out-String
    foreach ($word in @('d10343ff', 'a90b7bfd', 'a90c4ff4', '9102c3fd')) {
        if ($disassembly -notmatch "\b$word\b") {
            throw "workspace reload prologue 不匹配：$path offset=$start word=$word"
        }
    }
}

$workspaceReloadOffset = switch ($launcherProfile) {
    'legacy7653' { [UInt64] 0x7a1660 }
    'legacy7722' { [UInt64] 0x7a2008 }
    'legacy7748' { [UInt64] 0x7a35a4 }
    'current8565' { [UInt64] 0x7aa050 }
    default { throw "没有 workspace reload profile：$launcherProfile" }
}
Verify-WorkspaceReloadPrologue $launcherPath $objdump $workspaceReloadOffset

$privateSymbols = & $nm -D $privatePath | Out-String
Require-Symbol $privateSymbols 'create_accessibility_node_infoB9_'
Require-Symbol $privateSymbols 'perform_actionB9_'
Require-Symbol $privateSymbols 'AccessibilityInteractionController28perform_accessibility_action'
Require-Symbol $privateSymbols 'IAccessibilityInteractionConnection26performAccessibilityAction'
Require-Symbol $privateSymbols 'AccessibilityController32request_send_accessibility_event'

$publicSymbols = & $nm -D $publicPath | Out-String
foreach ($symbol in @(
    'AccessibilityAction_new',
    'AccessibilityAction_drop',
    'AccessibilityNodeInfo_new',
    'AccessibilityNodeInfo_add_action',
    'AccessibilityNodeInfo_drop',
    'AccessibilityNodeInfo_set_content_description',
    'AccessibilityNodeInfo_set_traversal_after',
    'AccessibilityNodeInfo_set_text',
    'AccessibilityNodeInfo_set_selected',
    'AccessibilityNodeInfo_set_checked',
    'AccessibilityEvent_obtain',
    'AccessibilityEvent_drop',
    'AccessibilityEvent_record_push_text',
    'AccessibilityEvent_record_set_source',
    'AccessibilityEvent_record_set_item_count',
    'AccessibilityEvent_record_set_from_index',
    'AccessibilityEvent_record_set_to_index',
    'AccessibilityEvent_set_event_type'
)) {
    Require-Symbol $publicSymbols $symbol
}

$launcherSymbols = & $nm -D $launcherPath | Out-String
foreach ($symbol in @(
    'frb_pde_ffi_dispatcher_primary',
    'Bundle_insert_boolean',
    'LauncherApps_default',
    'LauncherApps_get_shortcuts',
    'LauncherApps_start_shortcut',
    'LauncherApps_start_main_activity',
    'LauncherApps_start_app_details_activity',
    'ShortcutQuery_default',
    'ShortcutQuery_set_package_name',
    'ShortcutInfo_get_id',
    'ShortcutInfo_get_title'
)) {
    Require-Symbol $launcherSymbols $symbol
}

$nativeSource = (Get-Content -Raw -LiteralPath (Join-Path $root 'app/src/main/cpp/native_init.cpp')) +
    (Get-Content -Raw -LiteralPath (Join-Path $root 'app/src/main/cpp/native_abi_profiles.h'))
foreach ($buildId in @(
    '401af21eddb88554a568b61ef29fc138',
    'e05f4ad255225718a2f86dfcc636b871',
    '237f244c1c392a963dce55a2d11068fd',
    'c3f3fc36f279d12dd70d69f328596457',
    '7de2dd3e204a267ee1ed57c7a54391d2',
    '9768c65ebc28df3b10059df4fae9bd7e',
    '0c593771414b630a523e548f33700e83',
    'a6c7eeb5fec3383a58b89d43166852b8',
    '4f1bdaed62b77ed2de7d3ebc34e453ca',
    '4f1bdaedd47beb4ede7d3ebcd292504e',
    '4f1bdaeda5978273de7d3ebca639ae02'
)) {
    if ($nativeSource -notmatch [regex]::Escape($buildId)) {
        throw "native_init.cpp 缺少已验证 profile Build ID：$buildId"
    }
}
foreach ($fingerprint in @(
    '5b7a19c56d7704c7', 'f5c729612d74d0de',
    'd884584d8d3d6fb8', 'cd7b61ccbcf2dab1',
    'daf46dda4c2b40d4', 'ceb378cc00132dc9',
    '06ad928995604d98', 'e150a44005597071',
    '0447f0269df2c72f', '98e52e43a1f1887a',
    'e3cc5ece19a86f19', '68095ade224bb05b',
    '0676d70c1d6227d6', 'e899ab4db826315e',
    '6018f13c7a9d6274', 'd0b4054c1d248904',
    '6aafec65d595b957', 'd78ecbfd33f5b7af',
    'd43e8de27a633a7e', '770a572f4b6ddfc6'
)) {
    if ($nativeSource -notmatch [regex]::Escape($fingerprint)) {
        throw "native_init.cpp 缺少已验证 ELF 指纹：$fingerprint"
    }
}
foreach ($op in @(
    'kFrbUpdateItemDatabaseBatch = 854U',
    'kFrbStartLoad = 851U',
    'kFrbMoveItemInDatabase = 833U'
)) {
    if ($nativeSource -notmatch [regex]::Escape($op)) {
        throw "缺少 HyperOS4 FRB 内部模型操作：$op"
    }
}
foreach ($offset in @('0x7a1660', '0x7a2008', '0x7a35a4', '0x7aa050')) {
    if ($nativeSource -notmatch [regex]::Escape($offset)) {
        throw "native_init.cpp 缺少 workspace reload profile 偏移：$offset"
    }
}
foreach ($word in @('d10343ff', 'a90b7bfd', 'a90c4ff4', '9102c3fd')) {
    if ($nativeSource -notmatch $word) {
        throw "native_init.cpp 缺少 workspace reload prologue：$word"
    }
}
if (($nativeSource -notmatch 'resolveWorkspaceReloadForProfile') -or ($nativeSource -notmatch 'readModuleFingerprint') -or ($nativeSource -notmatch 'CAP_MOVE_PROFILE')) {
    throw 'native_init.cpp 缺少 ELF fingerprint/prologue/move capability resolver'
}
if ($nativeSource -match 'g_trigger_workspace_reload\s*=\s*nullptr\s*;\s*if\s*\(\s*g_frb_dispatcher') {
    throw 'native_init.cpp 不得禁用已验证的 workspace reload entry point'
}
if ($nativeSource -match '0x23ca8|0x23ce4|0x27af8|0x28828') {
    throw 'native_init.cpp 不得硬编码未经运行时解析的 ABI 偏移'
}
if ($nativeSource -notmatch 'dlsym\(') {
    throw 'native_init.cpp 必须通过 dlsym 解析符号'
}

Write-Output "PASS: Rust ABI 基线有效（system=$systemProfile, launcher=$launcherProfile, private=$privateId, aidl=$aidlId, ui=$uiId, public=$publicId, libapp=$dartId）。"
