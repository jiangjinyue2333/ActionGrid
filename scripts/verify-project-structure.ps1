$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot

function Require-Text {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Pattern,
        [Parameter(Mandatory)] [string] $Description
    )

    $absolutePath = Join-Path $root $Path
    if (-not (Test-Path -LiteralPath $absolutePath)) {
        throw "缺少文件：$Path"
    }
    if (-not (Select-String -LiteralPath $absolutePath -Pattern $Pattern -Quiet)) {
        throw "结构验证失败：$Description"
    }
}

Require-Text 'settings.gradle.kts' 'include\(":app", ":hook"\)' '必须包含 app 和 hook 模块'
Require-Text 'app/build.gradle.kts' 'implementation\(project\(":hook"\)\)' 'app 必须依赖 hook'
Require-Text 'hook/build.gradle.kts' 'compileOnly\(libs\.libxposed\.api\)' 'hook 必须仅编译依赖 API 102'
Require-Text 'app/build.gradle.kts' 'minSdk = 34' '最低版本必须是 Android 14'
Require-Text 'app/build.gradle.kts' 'targetSdk = 37' '目标版本必须是 Android 17'
Require-Text 'app/build.gradle.kts' 'versionCode\s*=\s*[1-9][0-9]*' '正式版本必须有正整数 versionCode'
Require-Text 'app/build.gradle.kts' 'versionName\s*=\s*"[0-9][0-9A-Za-z.+-]*"' '正式版本必须有可发布 versionName'
Require-Text 'app/src/main/res/values/strings.xml' '<string name="author">作者：江浸月</string>' '关于页必须显示作者'
Require-Text 'app/src/main/res/layout/activity_main.xml' '@\+id/open_source_repository' '关于页必须提供源码仓库按钮'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/MainActivity.java' 'https://github\.com/jiangjinyue2333/ActionGrid' '源码仓库入口必须指向公开 GitHub 仓库'
Require-Text 'app/src/main/resources/META-INF/xposed/java_init.list' '^io\.github\.jiangjinyue2333\.actiongrid\.hook\.ActionGridModule$' 'Hook 入口不正确'
Require-Text 'app/src/main/resources/META-INF/xposed/module.prop' '^minApiVersion=102$' 'Modern API 最低版本不正确'
Require-Text 'app/src/main/resources/META-INF/xposed/module.prop' '^targetApiVersion=102$' 'Modern API 目标版本不正确'
Require-Text 'app/src/main/resources/META-INF/xposed/scope.list' '^com\.miui\.home$' '静态作用域必须只有小米桌面'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/StableItemRef.java' 'CONTAINER_HOTSEAT = -101' '必须识别小米底栏容器'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/ActionGridModule.java' 'createAccessibilityNodeInfo' '图标操作必须在节点生命周期稳定注入'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/ActionGridModule.java' 'installApplicationActions\(this\)' '澎湃 4 必须进入原生应用动作路径'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/ActionGridModule.java' 'isNativeCandidate' '未知无 DEX 8.x 不得回退到 Java 旧路径'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/HyperOs4Compatibility.java' '801028565' '必须保留 8565 native profile'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/HyperOs4Compatibility.java' '801027722' '必须保留 7722 native profile'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/HyperOs4Compatibility.java' '801027653' '必须保留 7653 native profile'
Require-Text 'app/src/main/cpp/native_init.cpp' 'actiongrid_native_capabilities' 'Rust 原生后端必须提供能力状态'
Require-Text 'app/src/main/cpp/native_init.cpp' 'knownSystemAbi' '基础动作必须独立验证完整系统 ABI'
Require-Text 'app/src/main/cpp/native_abi_profiles.h' 'kLegacy7748' '必须包含核对过的 7748 Rust profile'
Require-Text 'app/src/main/cpp/native_init.cpp' 'batchMoveOperation' '批量事务必须使用所选 profile 的操作号'
Require-Text 'app/src/main/cpp/native_init.cpp' 'workspaceContainer' '原生桌面事务必须检查文件夹边界'
Require-Text 'app/src/main/cpp/zip_directory.h' 'offset \+ 22 \+ commentSize == size' 'APK 尾记录必须完整且不能越界'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/FolderDragTransfer.java' 'removeItemsFromSourceWhenMultiDrag' '文件夹拖出必须复用原生源项清理'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/ActionGridModule.java' 'PageOrderProtocol.supports' '页码倒序必须通过共用的 7.x 范围检查'
Require-Text 'scripts/test-native-policy.ps1' 'native_abi_profiles_test.cpp' '原生能力边界必须有可编译回归'
Require-Text 'scripts/verify-launcher-contract.py' 'opcode_target' 'FRB 契约必须核对真实二进制操作目标'
Require-Text 'docs/PROJECT_STRUCTURE.md' 'native_abi_profiles.h' '结构文档必须记录原生 ABI 模块'
Require-Text 'docs/PROJECT_STRUCTURE.md' 'FolderDragTransfer' '结构文档必须记录文件夹事务模块'
Require-Text 'docs/PROJECT_STRUCTURE.md' 'PageOrderProtocol' '结构文档必须记录页码倒序边界'
Require-Text 'app/src/main/cpp/native_init.cpp' 'frb_pde_ffi_dispatcher_primary' '澎湃 4 必须使用内部 FRB 放置入口'
Require-Text 'app/src/main/cpp/native_init.cpp' 'kExpectedCurrentLauncherDartBuildId' 'native 必须识别 8565 Build ID'
Require-Text 'app/src/main/cpp/native_init.cpp' 'kExpectedPreviousLauncherDartBuildId' 'native 必须识别 7653 Build ID'
Require-Text 'app/src/main/cpp/native_init.cpp' 'resolveLauncherApis\(launcherHandle\)' 'libapp 延迟加载必须复用 profile 初始化'
Require-Text 'scripts/verify-native-abi.ps1' 'Build ID' 'Rust ABI 必须有离线基线检查'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/HyperOs4Workspace.java' 'resolver.query\(FAVORITES_URI' '澎湃 4 只能只读查询桌面布局'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/EmptyPositionAccessibilityProvider.java' 'extends AccessibilityNodeProvider' '空位置必须使用虚拟无障碍节点'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/VirtualGridLayout.java' 'List<Cell> cells' '虚拟桌面必须提供完整行列遍历模型'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/PlacementConfirmation.java' 'matchesItemAtPosition' '放置结果必须按目标位置实际项目确认'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/FolderPlacementTarget.java' 'static int anchorIndex' '文件夹前后插入必须使用独立锚点模型'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' 'findOptionalMethod' '可选放置能力不得阻断核心操作 Hook'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' 'com\.miui\.home\.folder\.FolderInfo' '7.x 必须使用真实 FolderInfo 类'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' 'isAllDropedSuccess' '放置确认必须读取原生整组拖动结果'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' '"autoDrop"' '7.x 占用目标必须支持原生定向投递'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' '"mAutoDraged"' '7.x 原生 autoDrop 必须补齐控制器状态'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' '"autoDrag"' '7.x 打开文件夹后必须直接建立桌面源拖动'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' 'MotionEvent.ACTION_CANCEL' '7.x 原生落下后必须结束合成触摸流'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' '"reorderItems"' '7.x 文件夹定向投递必须使用原生重排'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' 'mergeShortcutResults' 'Shortcut 必须合并 Activity 与包级查询'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java' 'showFolderSheet' '文件夹编辑必须进入小米原生界面'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/ActionController.java' 'ActionIds\.EDIT_FOLDER' '文件夹节点必须提供编辑动作'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/ActionController.java' 'if \(cached\.canRemove\(\)\)' '快捷方式移除动作必须独立显示'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/ItemLocationLabel.java' '底栏第 ' '底栏必须使用官方位置语义'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/HomeRestartProtocol.java' 'REQUEST_NONCE' '无 Root 桌面重启必须使用共享协议'
Require-Text 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/ActionGridModule.java' 'killProcess' '桌面进程必须自行响应重启请求'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/HomeRestarter.java' 'getRemotePreferences' '管理应用必须通过 LSP 远程配置请求重启'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/MainActivity.java' 'ClipData.newRawUri' '跨进程日志 URI 必须传播读授权'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/update/UpdateInstallActivity.java' 'ClipData.newRawUri' '安装包 URI 必须传播读授权'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/update/UpdateInstallActivity.java' 'openFileDescriptor\(updateUri, "r"\)' '拉起安装器前必须确认更新 URI 可读'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/update/UpdateNotification.java' 'UpdateInstallActivity.intent' '后台更新通知必须进入应用自有安装入口'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/update/UpdateDownloader.java' 'setDestinationInExternalFilesDir' '更新必须下载到应用专属外部目录'
Require-Text 'app/src/main/AndroidManifest.xml' 'android:readPermission="io\.github\.jiangjinyue2333\.actiongrid\.permission\.READ_DIAGNOSTICS"' '日志文件必须由平台 URI grant 控制读取'
Require-Text 'app/src/main/AndroidManifest.xml' 'android:permission="android\.permission\.SEND_DOWNLOAD_COMPLETED_INTENTS"' '更新完成广播必须只接受系统下载进程'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/WeChatShortcutActivity.java' 'getLaunchedFromPackage' '卸载入口必须校验调用方包名'
Require-Text 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/WeChatShortcutActivity.java' 'getPackagesForUid' '卸载入口必须校验调用方 UID'
Require-Text 'scripts/build-release.ps1' 'aapt dump badging' '发布脚本必须读取 APK 实际版本'
Require-Text 'scripts/create-update-manifest.ps1' 'releases/download' 'GitHub 发布必须生成可下载更新清单'
Require-Text 'scripts/verify-release.ps1' 'expectedCertificateSha256' '发布验证必须固定证书连续性'
Require-Text 'scripts/verify-release.ps1' 'ExpectedTag' '发布验证必须核对 tag 版本'
Require-Text 'scripts/verify-release.ps1' 'verify-apk-abi.ps1' '正式包必须检查合并 manifest 与 ELF 安装 ABI'
Require-Text '.github/workflows/signed-apk.yml' 'run: ./scripts/test-apk-abi.ps1' '普通构建必须验证安装 ABI 和错误样例'
Require-Text 'docs/INSTALLATION_ABI.md' 'multiArch' '安装 ABI 约束必须记录在专项文档'
Require-Text '.github/workflows/signed-apk.yml' 'refs/tags/v' '正式签名必须限制在 v<version> tag'
Require-Text '.github/workflows/signed-apk.yml' 'run: ./scripts/build-release.ps1' '云端构建必须复用正式发布脚本'
Require-Text '.github/workflows/signed-apk.yml' 'create-update-manifest.ps1' 'GitHub Release 必须上传更新清单'
Require-Text '.github/workflows/signed-apk.yml' 'if: always\(\)' '签名密钥清理必须始终执行'
Require-Text 'docs/PROJECT_STRUCTURE.md' 'signed-apk.yml' '项目结构文档必须说明云端签名入口'
Require-Text 'docs/PROJECT_STRUCTURE.md' '正式版本以' '项目结构文档必须使用通用版本说明'
Require-Text 'README.md' '作者：江浸月' '公开说明必须包含作者'
Require-Text 'README.md' '801028565' '公开说明必须列出 HyperOS 4 native 支持矩阵'
Require-Text 'README.md' 'LSPosed Modern API 102' '公开说明必须说明安装前提'
Require-Text 'LICENSE' '江浸月' '开源许可必须包含作者'

$releaseWorkflow = Get-Content -Raw -LiteralPath (Join-Path $root '.github/workflows/signed-apk.yml')
if ($releaseWorkflow -notmatch '(?m)^\s*pull_request\s*:') {
    throw '结构验证失败：普通 PR 必须只运行检查 job'
}
if ($releaseWorkflow -notmatch "(?m)^\s{4}if:\s*startsWith\(github\.ref,\s*'refs/tags/v'\)") {
    throw '结构验证失败：正式签名必须只在 v<version> tag 执行'
}
$checkWorkflow = [regex]::Split($releaseWorkflow, '(?m)^\s{2}release:\s*$')[0]
if ($checkWorkflow -match 'secrets\.') {
    throw '结构验证失败：检查 job 不得读取签名 Secrets'
}
if ($checkWorkflow -notmatch '(?m)^\s+"GRADLE_USER_HOME=\$env:RUNNER_TEMP/gradle" >> \$env:GITHUB_ENV\r?$') {
    throw '结构验证失败：检查 job 写入 GRADLE_USER_HOME 时必须保留 PowerShell 字符串引号'
}

$scopeLines = @(Get-Content -LiteralPath (Join-Path $root 'app/src/main/resources/META-INF/xposed/scope.list') |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($scopeLines.Count -ne 1 -or $scopeLines[0] -ne 'com.miui.home') {
    throw '结构验证失败：scope.list 必须只有小米桌面一项'
}

$hyperAdapter = Get-Content -Raw -LiteralPath (Join-Path $root 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/HyperOs4NodeAdapter.java')
if ($hyperAdapter -match 'com\.android\.server|onSystemServerStarting') {
    throw '结构验证失败：澎湃 4 适配器不得进入 system_server'
}

$hookBuild = Get-Content -Raw -LiteralPath (Join-Path $root 'hook/build.gradle.kts')
if ($hookBuild -match 'project\(":app"\)') {
    throw '结构验证失败：hook 不得依赖 app'
}

[xml] $appManifest = Get-Content -Raw -LiteralPath (Join-Path $root 'app/src/main/AndroidManifest.xml')
foreach ($attribute in @('multiArch', 'use32bitAbi')) {
    $value = $appManifest.manifest.application.GetAttribute($attribute, 'http://schemas.android.com/apk/res/android')
    if ($value -and $value -ne 'false') {
        throw "结构验证失败：arm64 单 ABI 包不得启用 android:$attribute"
    }
}

$bridge = Get-Content -Raw -LiteralPath (Join-Path $root 'hook/src/main/java/io/github/jiangjinyue2333/actiongrid/hook/MiuiHomeBridge.java')
if ($bridge -match 'com\.miui\.home\.model\.api\.FolderInfo') {
    throw '结构验证失败：7.x 不存在 model.api.FolderInfo'
}

$downloader = Get-Content -Raw -LiteralPath (Join-Path $root 'app/src/main/java/io/github/jiangjinyue2333/actiongrid/update/UpdateDownloader.java')
if ($downloader -match 'VISIBILITY_VISIBLE_NOTIFY_COMPLETED|setDestinationUri') {
    throw '结构验证失败：不得把 DownloadManager 完成通知作为安装入口'
}

Write-Output '项目结构验证通过：仅桌面作用域、native/legacy 双路径、Android 14–17 和 Xposed API 102 元数据均正确。'
