# 桌面 8.01.02.8593 离线适配

2026-11-07 从已连接设备只读取得 `RELEASE-8.01.02.8593-260918-10241550-R`（versionCode `801028593`）安装包及进程实际加载的系统库。设备为 HyperOS `OS4.0.5.0.XPMCNXM`、Android 17；当前桌面为 `4×7`、7 页、底栏 4 项。

首次交付为离线适配和本地测试构建。随后用户确认设备已开启核心破解，授权直接覆盖安装；安装及加载确认见下文。尚未在真实桌面执行移动、小窗、分屏等完整动作验收。模拟器夹具验证的是生产 C++ 逻辑，不能替代小米桌面、解说读屏及系统窗口行为的真机验收。

## 覆盖安装与加载确认

2026-11-07 12:49，使用 `adb install -r -t` 直接覆盖安装本次 Debug APK，返回 `Success`，未卸载模块。重启小米桌面后，新进程 PID `23847` 已加载新 APK 的原生库；本轮新诊断记录确认 `profile=8.01.02.8593 fingerprint=1 system=1 cap=0x5dff`。模块原有日志设置和解说读屏服务仍在，桌面进程保持运行。此项仅证明安装及 ABI 加载成功，不代表全部操作已真机通过。

## 样本与能力边界

- APK SHA-256：`a53ab31d3ca08ff16267385972d8c208176537964034256592dae132848b810c`。
- `libapp_launcher.so` SHA-256：`fba7ff80c3c1f2bcae819989bcd1a3d24eff0067ebeb21de622b657ecb20cfbf`。
- Launcher ELF 结构指纹：`cbeed432e2b66754 / 857131b8c64b7be4`；使用独立 `kCurrent8593`，不按版本名称或 Dart Build ID 放行。
- 本机 private/AIDL/UI/public 系统库指纹分别匹配已有 private、current AIDL、current UI、public 基线；媒体库也匹配已有点击音基线。8593 只接受这个已验证的 current 系统配对。
- 实际打开的数据库为 `/data/user_de/0/com.miui.home/databases/launcher4x7.db`。只读取得稳定的 DB/WAL 副本后确认 favorites/screens 字段可复用，继续使用现有数据库事务、刷新和真实节点确认流程。
- Flutter 引擎指纹为 `e3502ea4ab9ffcb3 / f7edc38c6de026f3`，不匹配 7748。语义树重建保持 7748 专用，8593 的新 holder 链没有启用；若真机出现缺节点，再单独取证。

## 专门更新的入口

以下地址均为当前样本的 RVA，运行时仍校验 ELF 指纹和已有函数序言。

| 接口 | 8593 基线 |
| --- | --- |
| FRB export / dispatcher | `0x9c4f70 / 0x7fa600` |
| 单项 / 批量 / 插入 / 删除操作号 | `834 / 858 / 830 / 821` |
| 工作区刷新 | `0x7b02a0` |
| 网格列数 / 行数 | `0x6cd790 / 0x6cd83c` |
| 触摸入口 | `0xd482e0` |
| 窗口焦点 / 按键 | `0x778420 / 0x980dc0` |
| 返回 / 事件重定向 / 返回手势识别 | `0x729ed4 / 0x6b5c10 / 0x707bc4` |

ItemInfo decoder 为 `0x9744ec`，大小 `0x4c4`。与已有 8565 样本对照全部 305 条指令，将分支目标解析为符号或导入名后完全一致，继续使用现有 35 字段和 75 字节稀疏编码。

触摸调用约定仍为 `void(scene*, event*)`。8593 的 `on_motion_event_v2` 在 `+0x28` 调用共享入口，旧基线为 `+0x24`。离线验证中的 MotionEvent clone/setAction 证据改取 `GestureInputMonitor::give_up_gesture_for_accessibility`，保留转参、取消动作值和所有权检查；不改变运行时触摸处理逻辑。返回手势的 `window+0xb0 → Arc+0x10` 布尔字段也通过静态校验。

## 最近任务小窗与分屏

8593 使用已核实的 FRB 编码入口：

| 操作 | 操作号 | wire 参数顺序 |
| --- | --- | --- |
| 小窗 | `1026` | `String(package), String(activity), i32(taskId), i32(userId), bool(true)` |
| 分屏 | `792` | `i32(taskId)` |

字符串使用小端 i32 UTF-8 字节长度和原始字节，不添加 Option 存在字节。校验器验证 dispatcher 目标、全部解码调用及参数结束位置。发送前检查已验证 profile、系统 ABI、FRB 能力、有效任务 ID，以及小窗的非空包名/Activity 和非负用户 ID。

8593 的直接小窗 helper 首参实际是包含两段 Rust String 的 48 字节 ComponentName 值。系统 `ComponentName_new` 返回的是另一种 opaque handle，不能直接传入该 helper。复用 FRB 后由 Rust 自己构造、释放对象；C++ 只交出独立分配的字节缓冲区。派发返回值仅表示请求已提交，不证明窗口已经打开。

## 验证命令与后续真机验收

本次离线结果：

- 8593、8565 真实样本的 Python 契约校验通过；13 个错误版本、操作号、解码调用、参数结束位置及未验证 Flutter 等负例均被拒绝。7748/8584 缺少完整本地样本，本轮未重跑其完整二进制契约。
- PowerShell ABI 校验通过 8593/current 和 7653/legacy；8593/legacy 混配被拒绝，12 种系统配对组合符合生产规则。
- 本地 `Medium_Phone_API_36.1` x86_64 模拟器以不保存状态的无界面模式执行 97 项生产 C++ 回归，全部通过。新增用例覆盖最近任务 wire、失败路径及 4×7 网格；已有放置确认矩阵加入 8593。
- Java 单元测试 123 项通过（app 57、hook 66），零失败、错误和跳过。Lint 零错误、7 条警告，涉及 SharedPreferences、构建版本、ABI、SDK 和已有反射 API。
- `test lint assembleDebug assembleRelease` 构建通过；项目结构、两个 APK 的 ARM64 安装 ABI 及错误 APK 样例验证通过。

产物保留在 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/release/app-release-unsigned.apk`。前者使用本地调试签名，本机通过用户已配置的核心破解完成覆盖安装；后者未签名。Debug 包不具备正式签名，不能据本机安装结果推断普通设备也可直接覆盖正式版。构建日志与 C++ 回归结果位于 `artifacts/8593/`。

只读样本位于忽略目录 `tmp/device-8593-20261107/`；`com.miui.home/native/8593-contract-analysis.json` 保留原始分析结果。核心离线校验命令：

```powershell
$ndk = 'C:/Users/liqia/AppData/Local/Android/Sdk/ndk/28.2.13676358'
$sample = 'tmp/device-8593-20261107'
python scripts/verify-launcher-contract.py "$sample/com.miui.home/native/libapp_launcher.so" --profile 8593 --llvm-nm "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe" --json
./scripts/verify-native-abi.ps1 -PrivateLibrary "$sample/system/libhyper_os_accessibility_private.dylib.so" -PublicLibrary "$sample/system/libhyper_os_accessibility_public.so" -LauncherLibrary "$sample/com.miui.home/native/libapp_launcher.so" -DartLibrary "$sample/com.miui.home/native/libapp.so" -AidlLibrary "$sample/system/libhyper_os_aidl.dylib.so" -UiLibrary "$sample/system/libhyper_os_ui_public.so"
./scripts/test-native-policy.ps1 -NdkDirectory $ndk
```

错误旧 profile、错误移动操作号、系统库混配及最近任务参数错误必须被拒绝。C++ 夹具在本地 x86_64 模拟器上运行，包含 4×7 网格、移动、文件夹、返回以及最近任务编码；Java 测试、Lint 和 Debug/Release 构建单独验证。

后续可使用已安装测试包，保留当前解说读屏配置，逐项验收真实图标和空格的触摸/滑动、翻页项目数、同页/跨页/底栏放置、文件夹事务、两级返回与连续边缘返回、焦点恢复、最近任务小窗和分屏。操作结果必须同时检查实际界面、持久化位置和重开状态；测试后恢复原布局。不得将当前离线结果标为真机通过。
