# ActionGrid 项目结构

## 项目用途

ActionGrid 是小米桌面的 TalkBack 增强模块。管理应用负责模块状态、诊断和在线更新，Hook 模块负责向小米桌面图标与位置节点注入无障碍操作。

正式版本以 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode` 为准。

## 目录

```text
ActionGrid/
├─ .github/workflows/           # main/PR 检查与 v<version> 签名发布
│  └─ signed-apk.yml
├─ app/                         # Android 应用、关于页、诊断与更新
│  └─ src/
│     ├─ main/
│     │  ├─ cpp/                # 原生 Hook、ABI 策略和 APK 库读取
│     │  ├─ java/.../actiongrid/
│     │  ├─ res/
│     │  └─ resources/META-INF/xposed/
│     ├─ androidTest/           # 安装后诊断 Provider、存储与导出回归
│     └─ test/                  # Java 回归、cpp 策略及 Android 原生行为回归
├─ hook/                        # 小米桌面 Hook 与无障碍操作
│  └─ src/
│     ├─ main/java/.../hook/
│     └─ test/java/.../hook/
├─ docs/                        # 项目结构与开发资料
├─ scripts/                     # 结构、构建、更新清单和 APK 校验脚本
├─ gradle/
├─ build.gradle.kts
└─ settings.gradle.kts
```

## 模块边界

- `:app` 生成最终 APK，入口为 `MainActivity`，并依赖 `:hook`。
- `:hook` 是 Android Library，仅编译依赖 libxposed API，不依赖 `:app`。
- Xposed 入口为 `ActionGridModule`，静态作用域仅包含 `com.miui.home`。
- 7.x Java 小米桌面继续使用 View 适配器；Android 17 及以上的无 DEX 原生桌面按实际接口选择能力，管理页不再以三个版本号作为总开关。`HyperOs4Compatibility` 只选择后端，Rust/FRB 操作仍由 native 层校验实际 ABI 后执行。
- `app/src/main/cpp/native_init.cpp` 负责澎湃 4 Rust 库的 Build ID、系统 ABI 配对、延迟加载复核、节点 action 透传和内部模型事务；离线基线检查见 `scripts/verify-native-abi.ps1`，运行时验收见 `docs/HYPEROS4_NATIVE_FINDINGS.md`。
- `native_abi_profiles.h` 独立管理原生能力边界；7748 使用批量操作号 855 和经过核对的刷新入口。只更新 Dart/版本号/Build ID 时复用兼容的 Rust 库；未知 Rust 移动接口只关闭移动，基础动作仍要求完整系统 ABI 校验。文件夹内项目不走未验证的原生桌面移动事务。
- `FolderDragTransfer` 处理 7.x 关闭源文件夹后拖出时的源项清理；使用桌面原生回调保留取消恢复，说明、普通手指拖动的检查边界与验收见 `docs/FOLDER_REPAIR.md`。
- `PageOrderSettings` / `PageOrderProtocol` / `PageOrderHook` / `PageIndicatorOrder` 为 PR #2 的页码倒序设置与 Hook，仅对有 DEX 的 7.x 安装。`HookMethodLookup` 查找继承的可选 hover 方法，避免单个能力缺失阻断初始化。见 `docs/PAGE_ORDER.md`。
- `scripts/verify-apk-abi.ps1` 校验合并 manifest 与最终 ELF 库；`scripts/test-apk-abi.ps1` 验证有效与错误安装样例。arm64 APK 不声明 multiArch，实测见 `docs/INSTALLATION_ABI.md`。
- 原生能力策略的可编译回归位于 `app/src/test/cpp/native_abi_profiles_test.cpp`，由 `scripts/test-native-policy.ps1` 和 CI 在已安装的 NDK 下验证。文件夹子项在显式托起时提示不可用，源与目标事务入口也同时校验容器。
- `app/src/test/cpp/native_grid_test.cpp` 在 Android 上执行生产 C++ 的网格、触摸、移动参数与图标确认回归；`scripts/test-native-grid.ps1 -Serial <已有设备>` 不创建或启动模拟器。样本、测试结果与真机验证边界见 `docs/HYPEROS4_GRID_REPAIR.md`。
- 0.4.5 在同一原生回归加入窗口焦点、连续回桌面、返回键/手势、图标与空位混合遍历和页码即时播报；新增 Hook 受 7748/8565 ELF 指纹、系统 ABI 配对和函数序言限制。见 `docs/RELEASE_0.4.5.md`。
- 0.4.6 补充真实 child 顺序、稳定空位 ID、失效查询、底栏隔离和公告去重回归。`app/src/test/readers/` 与 `scripts/build-reader-fixture.ps1` 是独立的真实读屏测试夹具，Java 只转换生产 C++ 的节点与事件，不进入正式 APK。见 `docs/RELEASE_0.4.6.md`。
- 0.4.8 保留窗口恢复期间生成节点的操作，修正托起 favorite ID 与无障碍节点 ID 混用，并按实际原因播报放置准备失败；相关原生行为回归和验证边界见 `docs/RELEASE_0.4.8.md`。
- 0.4.9 使用已有原生 ContentResolver 将 7748/8565 桌面日志异步传入 `DiagnosticsProvider`。`native_diagnostics.h` 限制队列和批次，`LogStore` 校验开关 session 与写入确认，`RotatingLog` 保存四份轮换文件并生成独立分享快照；没有在桌面进程创建 Java VM。见 `docs/RELEASE_0.4.9.md`。
- 0.4.10 在原生移动前注册节点观察，刷新后先通知读屏再等待查询，并复查最终数据库位置；诊断日志区分保存、刷新改位及节点匹配失败。见 `docs/RELEASE_0.4.10.md`。
- 0.4.11 恢复页码指示器默认描述，仅替换自动翻页公告为当前页项目数；修正事件窗口与字符串 ABI，使原生旧公告能被正确去重。桌面文件夹按自身类型解析并保留托起时的 favorite ID，成功反馈使用系统桌面的托起、放置原文。见 `docs/RELEASE_0.4.11.md`。
- 0.4.13 在桌面节点重建或快照解析短暂失败时继续复用托起阶段确认的 favorite 位置；原生 workspace host 更换窗口/节点后仍重写页码项目数播报。见 `docs/RELEASE_0.4.13.md`。
- `zip_directory.h` 为 APK 中直接加载的 Rust 库定位 ZIP 尾记录，检查截断和注释长度，避免兼容性探测读取末尾以外的内存；同一 native 回归包含空 ZIP、注释与缺失尾记录。
- `scripts/verify-launcher-contract.py` 从指定桌面 ELF 的真实跳转表验证 FRB 操作号、刷新入口及 `Vec<ItemInfo>` 解码调用链，证据见 `docs/LAUNCHER_7748_ABI.md`；不用于运行时猜测新接口。
- 应用最低支持 Android 14，目标版本为 Android 17。
- 关于页显示作者，并通过明确按钮打开 GitHub 源码仓库。
- `UpdateSource` / `UpdateClient` / `UpdateState` 管理 GitHub 与 GitCode 更新来源；关于页显示当前来源并以原生单选对话框切换。GitHub 正式工作流用 `scripts/create-update-manifest.ps1` 从已验证 APK 生成 `latest.json` 并作为 Release 资产上传。
- `README.md` 记录当前支持范围、7748 的真机验证边界、仅澎湃 3 的页码开关和文件夹限制。当前工作区的翻页、图标/文件夹移动及焦点验证见 `docs/DEVICE_VALIDATION_2026-10-25.md`。

## 签名构建

- 普通 push/PR 只运行结构检查、测试、Lint 和 unsigned build，不读取签名 Secrets。
- 只有推送形如 `v<version>` 的 tag 才进入正式签名 job；工作流从 APK 实际元数据生成 `ActionGrid-<version>.apk` 并创建 GitHub Release。
- 发布 tag 的版本必须与 `app/build.gradle.kts` 的 `versionName` 完全一致，`versionCode` 只能递增。
- `scripts/verify-release.ps1` 校验包名、版本、Xposed 元数据、固定发布证书、DEX 入口和 arm64 native ABI；成功输出只说明 APK 的静态校验结果。
- 签名文件和密码由 README 所列四个 Repository secrets 注入，临时签名文件在结束时清理。外部 PR 不执行正式签名；工作流不自动修改在线更新清单。

## 完成任务前检查

1. 运行 `scripts/verify-project-structure.ps1`。
2. 运行 `gradlew.bat clean test lint assembleDebug assembleRelease`。
   随后运行 `scripts/test-native-policy.ps1` 和 `scripts/test-apk-abi.ps1 -ApkPath app/build/outputs/apk/release/app-release-unsigned.apk`。
3. 对签名包运行 `scripts/verify-release.ps1 -ApkPath <apk>`。
4. 检查 `git status`，确认构建产物和本机文件未进入版本控制。
