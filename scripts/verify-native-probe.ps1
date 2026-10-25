param([Parameter(Mandatory = $true)][string]$Serial)

$ErrorActionPreference = 'Stop'
function Invoke-Adb {
    $result = & adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($args -join ' ')" }
    return $result
}

Invoke-Adb logcat -c | Out-Null
$homePid = (Invoke-Adb shell pidof com.miui.home | Out-String).Trim()
if ($homePid -match '^\d+$') { Invoke-Adb shell am force-stop com.miui.home | Out-Null }
Invoke-Adb shell am force-stop com.tencent.mobileqq | Out-Null
Invoke-Adb shell monkey -p com.miui.home 1 | Out-Null
Invoke-Adb shell input keyevent 3 | Out-Null
Start-Sleep -Seconds 8
$homePid = (Invoke-Adb shell pidof com.miui.home | Out-String).Trim()
if ($homePid -notmatch '^\d+$') { throw 'Xiaomi Home is not running as a single process.' }
$nativeLog = Invoke-Adb logcat -d -s 'ActionGridNative:I' '*:S' | Out-String
if ($nativeLog -notmatch 'Rust node action hook installed') {
    throw 'Native node action hook was not installed after Home restart.'
}
if ($nativeLog -notmatch 'Launcher workspace reload symbol installed') {
    throw 'Launcher workspace reload entry point was not installed after Home restart.'
}
Start-Sleep -Seconds 3
$preheat = & adb -s $Serial shell am instrument -w -e inspect true `
    -e icon QQ -e builtin 16 -e oneShot true io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if ($preheat -notmatch 'BUILTIN true') {
    throw "Home Scene preheat failed: $preheat"
}
Invoke-Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 1
$uninstall = ''
try {
    $uninstall = & adb -s $Serial shell am instrument -w -e stay true -e action uninstall -e iconAlias qq `
        -e expect '要卸载此应用吗？' io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
    if ($uninstall -notmatch 'CALLBACK true' -or $uninstall -notmatch '要卸载此应用吗？' -or
            $uninstall -notmatch 'PASS') {
        throw "TalkBack uninstall confirmation probe failed: $uninstall"
    }
} finally {
    Invoke-Adb shell input keyevent 4 | Out-Null
}
$qqPath = Invoke-Adb shell pm path com.tencent.mobileqq | Out-String
if ($qqPath -notmatch 'package:') { throw 'QQ disappeared during the uninstall probe.' }

$lift = & adb -s $Serial shell am instrument -w -e stay true -e action lift -e icon 相册 io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if ($lift -notmatch 'CALLBACK true') {
    Start-Sleep -Seconds 1
    $lift = & adb -s $Serial shell am instrument -w -e stay true -e action lift -e icon 相册 io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
}
$probeFailed = ($lift -notmatch 'CALLBACK true') -or ($lift -notmatch 'PASS')
if ($probeFailed) {
    throw "Direct TalkBack lift probe failed: $lift"
}

$pageNextFull = & adb -s $Serial shell am instrument -w -e stay true -e action page-next io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if (($pageNextFull -notmatch 'CALLBACK true') -or ($pageNextFull -notmatch 'PASS')) {
    throw "Lifted next-page full-grid probe failed: $pageNextFull"
}
$pageNextSparse = & adb -s $Serial shell am instrument -w -e stay true -e action page-next -e requireVirtual true io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if (($pageNextSparse -notmatch 'CALLBACK true') -or ($pageNextSparse -notmatch 'PASS')) {
    throw "Lifted next-page virtual-grid probe failed: $pageNextSparse"
}
$pagePreviousFull = & adb -s $Serial shell am instrument -w -e stay true -e action page-previous io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if (($pagePreviousFull -notmatch 'CALLBACK true') -or ($pagePreviousFull -notmatch 'PASS')) {
    throw "Lifted previous-page full-grid probe failed: $pagePreviousFull"
}
$pagePreviousSparse = & adb -s $Serial shell am instrument -w -e stay true -e action page-previous -e requireVirtual true io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if (($pagePreviousSparse -notmatch 'CALLBACK true') -or ($pagePreviousSparse -notmatch 'PASS')) {
    throw "Lifted previous-page virtual-grid probe failed: $pagePreviousSparse"
}

$moveAfter = & adb -s $Serial shell am instrument -w -e stay true -e action move-after -e icon 设置 -e source 相册 -e place true io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if (($moveAfter -notmatch 'CALLBACK true') -or ($moveAfter -notmatch 'placementConfirmed=true') -or
        ($moveAfter -notmatch 'PASS')) {
    throw "Direct TalkBack move-after probe failed: $moveAfter"
}

$lift = & adb -s $Serial shell am instrument -w -e stay true -e action lift -e icon 相册 io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if (($lift -notmatch 'CALLBACK true') -or ($lift -notmatch 'PASS')) {
    throw "Second direct TalkBack lift probe failed: $lift"
}
$moveBefore = & adb -s $Serial shell am instrument -w -e stay true -e action move-before -e icon 设置 -e source 相册 -e place true io.github.jiangjinyue2333.actiongrid/.NativeActionsProbe | Out-String
if (($moveBefore -notmatch 'CALLBACK true') -or ($moveBefore -notmatch 'placementConfirmed=true') -or
        ($moveBefore -notmatch 'PASS')) {
    throw "Direct TalkBack move-before probe failed: $moveBefore"
}

Invoke-Adb shell am start -W -n io.github.jiangjinyue2333.actiongrid/.MainActivity | Out-Null
Start-Sleep -Seconds 3
Invoke-Adb shell uiautomator dump /data/local/tmp/actiongrid-probe.xml | Out-Null
$xml = Invoke-Adb exec-out cat /data/local/tmp/actiongrid-probe.xml | Out-String
if ($xml -notmatch 'module_status_row' -or
        $xml -notmatch '澎湃 4 原生动作') {
    throw 'Manager did not report the HyperOS 4 native profile status.'
}
Invoke-Adb shell rm /data/local/tmp/actiongrid-probe.xml | Out-Null

Start-Sleep -Seconds 10
$after = (Invoke-Adb shell pidof com.miui.home | Out-String).Trim()
if ($after -ne $homePid) { throw 'Home process restarted during the check.' }
$crashes = Invoke-Adb logcat -d -b crash | Out-String
if ($crashes -match 'com\.miui\.home' -and $crashes -match 'Fatal signal|FATAL EXCEPTION') {
    throw 'Current Home process has a crash log.'
}
Write-Output 'PASS: lifted paging and live occupied placement were confirmed; Home process remained stable.'
