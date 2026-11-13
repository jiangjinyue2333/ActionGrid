# 桌面 8.01.02.9239 适配

2026-11-13 从设备取得 `RELEASE-8.01.02.9239-261013-11081915-R`（versionCode `801029239`）安装包及桌面进程实际加载的系统库。设备为 Android 17 / HyperOS 4，当前布局为 4×7、7 页、底栏 4 项，数据库包含 210 个 favorites。

## 样本与兼容边界

- APK SHA-256：`d0c890d7ce7f1b931126b65cd2dfbcf2c1f6414bc2687859a092a14b6ca9e1dd`；REA 只读取得样本的证据 ID 为 `ev_3fd31a6b6eece68f02422a480d52e14051ed0893c13a2f089b290623395de248`。
- `libapp_launcher.so` SHA-256：`ac8bf99bb05cd2a3493b26e5a04377d4c3d059c732ba70469332aeb0d9b5c1c8`。
- Launcher ELF 指纹：`19ea17777ae33aea / 537555cbb005ab82`，使用独立 `kCurrent9239`。
- 实测 private/public/AIDL/UI/media/Flutter 指纹均与 8593 样本相同；9239 仅接受已经验证的 current 系统配对。版本号及 Build ID 不作为接口放行依据。
- ItemInfo decoder 位于 `0x9a5138`，大小 `0x4c4`。将分支目标规范化为符号、导入名或函数内偏移后，305 条指令与 8593 完全一致，复用 35 字段、75 字节稀疏编码。
- 实际数据库仍为 `/data/user_de/0/com.miui.home/databases/launcher4x7.db`。连续读取 DB/WAL 得到稳定副本，`quick_check=ok`；favorites/screens 字段满足现有事务路径，无需迁移。
- Flutter 语义重建保持 7748 专用，不把新 profile 接入未经验证的 holder 链。

## 原生入口

地址均为当前样本 RVA；运行时仍校验指纹、系统配对和函数序言。

| 接口 | 9239 |
| --- | --- |
| FRB export / dispatcher | `0x9f67c8 / 0x8202c0` |
| 单项 / 批量 / 插入 / 删除操作号 | `840 / 864 / 836 / 827` |
| 小窗 / 分屏操作号 | `1033 / 798` |
| 工作区刷新 | `0x7d4c68` |
| 网格列数 / 行数 | `0x6ed3f4 / 0x6ed4a0` |
| 触摸入口 | `0xd84c18` |
| 窗口焦点 / 按键 | `0x79c418 / 0x9b19a0` |
| 返回 / 重定向 / 返回手势识别 | `0x74bb8c / 0x6d5548 / 0x728c5c` |

最近任务沿用已核实的 FRB 编码：小窗为 `String(package), String(activity), i32(taskId), i32(userId), bool(true)`，分屏为 `i32(taskId)`。提交成功不等于窗口已经打开，真机必须核对实际窗口状态。

返回识别函数大小变为 `0x2124`，门控读取指令偏移变为 `+0x5ac`；实际 `window+0xb0 → Arc+0x10` 字段链未变。触摸 v2 调用位置仍为 `+0x28`，MotionEvent clone/setAction 证据仍取自 `give_up_gesture_for_accessibility` 的 `+0xe4`。离线检查保留旧 profile 的全部断言。

## 离线验证

- Python 契约：9239、8593 真实样本通过；16 项错误 profile、旧操作号、最近任务解码/结束位置、未验证 Flutter、旧返回门控及触摸偏移负例均被拒绝。
- PowerShell ABI：9239/current、8593/current、7653/legacy 通过；9239/legacy、9239 混合 AIDL/UI、8593/legacy 被拒绝。
- 原生策略断言通过；设备上运行的生产 C++ 夹具 98 项通过，包含 8593/9239 最近任务 wire、失败路径、9239 放置确认及最近任务进入补播策略。夹具通过不代表实际桌面动作通过。
- Java 单元测试 123 项通过（app 57、hook 66）；Lint 零错误、7 条已有警告；`clean test lint assembleDebug assembleRelease`、项目结构、Debug/Release 安装 ABI 和 5 类错误 APK 检查通过。

样本位于忽略目录 `tmp/device-9239-20261113/`，构建及测试输出位于 `artifacts/9239/`。使用已安装 NDK `28.2.13676358`，可复现核心检查：

```powershell
$sample = 'tmp/device-9239-20261113'
$ndk = 'C:/Users/liqia/AppData/Local/Android/Sdk/ndk/28.2.13676358'
python scripts/verify-launcher-contract.py "$sample/com.miui.home/native/libapp_launcher.so" --profile 9239 --llvm-nm "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe" --json
./scripts/verify-native-abi.ps1 -PrivateLibrary "$sample/system/libhyper_os_accessibility_private.dylib.so" -PublicLibrary "$sample/system/libhyper_os_accessibility_public.so" -LauncherLibrary "$sample/com.miui.home/native/libapp_launcher.so" -DartLibrary "$sample/com.miui.home/native/libapp.so" -AidlLibrary "$sample/system/libhyper_os_aidl.dylib.so" -UiLibrary "$sample/system/libhyper_os_ui_public.so"
./scripts/test-native-policy.ps1 -NdkDirectory $ndk
```

## 真机验收

原安装包、模块设置、桌面设置及稳定 DB/WAL 已备份至 `artifacts/9239/install-backup/`。`adb install -r -t` 成功覆盖安装 0.4.20（36），没有卸载模块。保益悦听（`com.bjbyhd.voiceback`）及小米增强读屏保持启用，所有探针均使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`。

首次仅重启桌面后未出现模块动作；作用域、安装路径及 native metadata 正确，独立 `dlopen` 也成功，但桌面进程没有映射模块。经用户明确授权执行 `setprop ctl.restart hyos_spawner` 后，桌面 PID 27426 正常加载。此结果证明刷新原生运行时恢复了注入，不足以把内部原因归结为某个 APK 缓存或 metadata 换行问题。没有重启整部手机，也没有把运行时重启加入模块代码。

新诊断确认 `module=0.4.20 profile=8.01.02.9239 fingerprint=1 system=1`，`node/action/focus/motion/event` hooks 均为 1。验收结束后再次仅重启桌面，PID 27426 → 21088，模块仍正常加载，默认动作探针通过。证据为 `runtime-final-before-restart.log`、`runtime-final.log`、`final-reopened-home.txt`。

| 场景 | 真机结果与证据（均位于 `artifacts/9239/`） |
| --- | --- |
| 托起、空位、焦点 | 4×7 网格和虚拟空位出现，空位无障碍焦点成功；`empty-lift.txt`。 |
| 7 页翻页 | 前进至第 7 页及跨页回退成功；`page-2.txt` 至 `page-7.txt`。原生页码事件记录页 1–5 各 28 项、页 6 为 26 项、页 7 为 6 项，与数据库计数一致；这不是人工听辨结果。 |
| 同页前后移动 | 文件管理与 QQ 的先后位置按动作改变，反向动作恢复；节点确认与 DB 坐标均通过，`same-page-*.txt`、`same-page-restored/layout.json`。 |
| 同页空位与跨页 | VVTTS 移到第 7 页空位，再移至第 6 页第 6 行第 3 列，随后恢复；`cross-placement-observed/layout.json`、`cross-observed-ui.txt`、`runtime-cross.log`。生产日志确认 `persisted=1 visible=1 focused=1`。 |
| 底栏独立位置 | 设置从底栏移至第 7 页第 7 行第 4 列，再以短信为锚点移回；`hotseat-on-desktop/layout.json`、`hotseat-restored/layout.json`。 |
| 文件夹事务 | 向现有文件夹移入 VVTTS，内部前后排序、关闭重开、移回桌面均成功；`folder-*.txt`、`folder-extracted-restored/layout.json`。 |
| 创建与空夹清理 | VVTTS 与 Stash 创建新文件夹，重开确认两个成员，依次移出，空文件夹行被删除，二者回原位；`newfolder-created/layout.json`、`newfolder-open-observed.txt`、`newfolder-restored/layout.json`。 |
| 两级及连续返回 | 文件夹托起后第一次返回关闭文件夹并保持托起，第二次取消；连续 `input keyevent 4 4` 也完成取消；`folder-back-1.txt`、`folder-back-2.txt`、`rapid-key-after.txt`。 |
| 触摸探索与双击 | 通过已核对的 `focaltech_ts` 输入设备发送触摸事件，真实图标获得焦点，滑至空位后焦点转移，双击实际完成移动；`raw-touch-real-observed.txt`、`raw-touch-swipe-observed.txt`、`raw-doubletap-db/layout.json`，恢复见 `raw-touch-restored/layout.json`。 |
| 小窗 | Stash 任务 1112 实际处于 `visible=true mode=freeform`，有截图及 Activity 状态；`recent-freeform-activity.txt`、`freeform.png`。 |
| 分屏 | Stash 与应用详情两个任务 1112/1111 同时处于 `visible=true mode=multi-window`，有截图；`split-pair-activity.txt`、`split-pair.png`。随后均恢复 `mode=fullscreen`，见 `split-restored-activity.txt`。 |
| 持久化与恢复 | 最终重启桌面后的 210 个 favorites 的 ID、容器、页、行列、跨度和类型与安装前备份逐项相同；标题及 screens 也与动作前快照相同。`final-restored/layout.json`、`final-settings.json`。没有用数据库备份覆盖测试结果，均通过反向动作恢复。 |

首次适配测试包 SHA-256：`87ada842337606d1b90e80e1d0748123d742e9fba8112a3896ec30528f8d1685`，当次已确认设备安装包和桌面进程映射一致。模块偏好设置仅诊断心跳 `native_contact_time` 更新，其他原有键值未变。当次过程未观察到桌面或模块的新崩溃，最终没有残留托起状态。最新补播调整包见下节。

## 界面切换补播调整

用户确认 9239 官方已播报进入最近任务及返回桌面，因此仅在已支持的旧 profile（7653、7722、7748、8565、8584、8593）补充“进入多任务界面”和“系统桌面”。`queueSurfaceAnnouncement` 在发送或排队前统一检查版本，9239、新增 profile 及未识别版本默认不补播，也不启动返回桌面后 250 ms 的兜底任务。该策略不放宽 ABI 兼容门槛，保留状态切换、原生焦点回调、托起清理和任务清除等操作反馈。

复用生产事件捕获夹具，各播报测试显式指定 profile，覆盖全部已支持旧版、9239 和 unknown。验证正常返回、快速返回无节点查询、焦点回调中重建节点、十次连续返回、最近任务退回桌面，以及控制器恢复后的队列行为；新版两句提示既不发送也不排队，旧版保留补播，任务清除反馈和状态清理正常。98 项 C++ 测试通过；123 项 Java 测试使用 `--rerun-tasks` 实际重跑通过，避免源码契约检查被 Gradle 缓存跳过；Lint 零错误、7 条已有警告，Debug/Release 构建及最终 APK ABI 检查通过。

该轮包位于 `artifacts/9239-home-announcement/ActionGrid-0.4.20-debug.apk`，SHA-256 为 `cf3ead94afc7caf9d2e80ef04cdee66be68b32f2d2d44f39c5817d54bfd5f6c7`，保留未发布的版本 0.4.20（36）。沿用此前因安全中心 `AdbInstallActivity` 缺失而验证过的系统包管理器覆盖安装方式，未修改安装限制设置。单独重启桌面后 PID 32745 映射该 APK，诊断确认 `profile=8.01.02.9239 fingerprint=1 system=1 cap=0x7dff`，无需再次刷新 `hyos_spawner`。仅关闭最近任务补播的上一轮包及记录仍保留于 `artifacts/9239-recents/`。后续 QQ 排查及最终恢复包见下节。

从 ActionGrid 主界面按主屏幕键、按返回键，以及从最近任务按返回键回桌面，各连续三轮通过；最近任务小窗/分屏动作存在，返回后桌面图标可获取并保持无障碍焦点。通用动作探针最终通过（32 个项目、169 个动作）。210 个 favorites、7 页顺序和读屏/导航/屏幕超时/音效设置与本轮安装前一致；模块偏好仅诊断心跳 `native_contact_time` 更新。证据位于 `artifacts/9239-home-announcement/` 下的 `native-grid.log`、`java-regression.log`、`home-key-*.txt`、`back-key-*.txt`、`recents-key-return-*.txt`、`native-final.log` 和 `acceptance.json`。补播抑制由生产事件回归及已加载的新包确认；没有把界面检查等同于人工语音听辨。

保留原始探针失败记录：空名称系统动作曾触发调试探针空指针，已复用现有 `String.valueOf` 模式修复；早期两次通用动作检查报告没有桌面动作，其中一次随后确认前台为 QQ，另一次未单独确定原因，不能据此归因于模块未加载。确认桌面并取得图标焦点后，同一通用检查通过。另一次把无障碍全局返回和焦点请求放在同一探针内（返回后等待 750 ms），即时焦点成功但下一探针为空；独立返回键及后续独立焦点检查连续三轮通过，不能将后者写成所有时序均已通过。

## QQ 返回桌面的重复播报排查

用户反馈同一个声音连续读两遍“系统桌面”。设备安装包及进程映射确认是补播已关闭的版本；临时日志捕获到原生空内容 `TYPE_WINDOW_STATE_CHANGED`，模块的桌面补播入口被版本策略拦截，未观察到对应的模块发送。

| 对比 | 用户听辨结果 | 结论 |
| --- | --- | --- |
| 过滤返回时的空内容原生窗口通知 | 完全不读 | 删除正常播报，撤回 |
| 保留通知，仅补齐 Launcher 类名 | 仍不读 | 类名修改不能作为修复，撤回 |
| 完全不安装模块原生 hook | 初次读一遍，未固定焦点位置 | 单次结果不足以归因 |
| 恢复模块，但窗口通知跳过附带处理直接转发 | QQ 内移动焦点后一次；焦点在系统栏时两次 | 仍复现，撤回临时分支 |
| 再次完全停用 hook，并固定系统栏焦点条件 | 用户确认与上述规律相同 | 模块补播不是该重复的必要条件 |

可复现条件为：焦点停在状态栏或底部导航按钮，连续进入 QQ 再回桌面；在 QQ 内容中移动焦点后返回则读一遍。静态读屏代码对 Android 17 的无类名桌面事件有应用名称兜底，但不足以确认内部重复来源；没有把该推测做成生产修复，也未修改读屏设置。

所有临时过滤、类名修补、窗口直通分支、停用 hook 入口、事件捕获探针及 `[DEBUG-home-speech]` 日志均已清除。最终保留原生窗口事件原样转发，以及 9239 起关闭两项模块补播的策略。恢复包为 `artifacts/9239-qq-double-speech/ActionGrid-0.4.20-restored-debug.apk`，SHA-256 `ae28601a18f9a57f403304a970e22fe137b8b0cb128842c078918696d45a2b35`；APK 各成员内容与先前补播关闭包一致。设备安装哈希与桌面 PID 4680 映射已核对，完整模块动作恢复，未刷新 `hyos_spawner`。

恢复后重新运行 123 项 Java、98 项 C++ 回归，Lint、Debug/Release 构建、两种 APK 的 ABI 及结构检查通过。210 条 favorites 和 7 页顺序、读屏/触摸探索/导航/超时/音效设置与安装前一致。首次通用探针报告 `No direct Home icon actions`，保留原记录；后续桌面节点检查确认“托起”“应用信息”“卸载”等动作及当前焦点，运行日志确认 `cap=0x7dff` 和持续动作注入，不将首次失败改记为通过。设备上的本轮临时探针、Frida 程序及安装暂存文件已删除，没有残留相应进程。

证据位于 `artifacts/9239-qq-double-speech/` 的 `trial-results.json`、`restoration.json`、`restored-home-nodes.txt`、`restored-native.log` 和测试日志。用户确认焦点停留系统栏时的两遍播报不再作为模块缺陷处理：关闭模块仍可复现，按原生环境的已知现象记录，不修改原生通知，也不作为 0.4.20 发布阻塞项。原生停用对照只用于诊断，最终安装包保留完整模块功能。

## 保留限制与未覆盖项

- 底栏默认“系统应用”文件夹使用资源名存储标题。现有 `folderResourceMatchesCell` 仅匹配桌面容器，因此以它为“移到前面”的锚点返回 false；未放松身份校验，底栏恢复使用短信锚点成功。该身份解析逻辑未在本次版本适配中修改。
- 特殊最近任务标题“应用详情”无法与 favorites 应用名对应，小窗请求被拒绝。普通应用 Stash 的小窗与分屏已经核对实际窗口；不能据此声称所有特殊任务标题都可用。
- 设备 `force_fsg_nav_bar=0`，使用三键导航。注入边缘滑动会触发普通触摸/翻页，不能作为全面屏返回手势通过的证据。本次保留导航设置，仅将返回键行为记为真机通过。
- 保留原读屏服务、触摸探索、屏幕超时和系统音效设置。早期适配仅核对节点焦点及播报事件；后续 QQ 排查的用户听辨和未解决条件单独记录于上节，不能扩展为所有语音及音效均已通过。
- 旧 `NativeActionsProbe` 的跨页 placement-only 分支没有像移动分支一样清除节点缓存，该次检查未确认放置；独立重读节点、数据库及生产日志均确认成功，保留失败日志而不把该探针结果改成通过。相似名称的文件夹成员使用精确节点描述重验，避免探针前缀匹配选错锚点。
