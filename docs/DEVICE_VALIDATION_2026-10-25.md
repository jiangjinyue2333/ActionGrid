# 0.4.15 任意桌面页、图标拖动与翻页播报真机验证

2026-10-25，0.4.15 Debug 包已安装在 Android 17 / HyperOS 4、小米桌面 `8.01.02.7748` 真机上。保留手机现有 TalkBack 服务，使用 Debug 专用 `NativeActionsProbe`、真实 UHID 触摸/滑动和桌面无障碍树验证；探针使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`。

## 当前版本结果

- 冷启动后 7 页桌面节点数量为 `13、24、19、15、14、11、18`；逐页前进、反向返回、回桌面和重启桌面后，真实图标节点均重新出现，焦点分别恢复到相册、手机管家、DeepSeek、晨光阅读、蚂蚁阿福、UU 跑腿和夸克网盘。
- 页码公告按当前页项目数播报，例如“第 2 页共 24 项”“第 3 页共 19 项”“第 7 页共 18 项”；正向、反向和冷启动都没有回退为“第 X 页共 X 页”，底栏和文件夹成员不计入。
- 第 4 页 24 个虚拟格已连续右滑、反向左滑，空格可触摸、获取无障碍焦点并双击放置；放置后焦点落回真实图标，取消、系统返回和全局返回都清除虚拟网格并恢复源焦点。
- 已通过图标前移、后移、第四页文件夹移到第三页、创建文件夹并移入应用、文件夹内前移/后移、首成员/末成员移出和空文件夹自动消失；每次都检查桌面节点、数据库位置和重新打开后的内容。
- 重新安装后运行 `scripts/verify-native-probe.ps1 -Serial 192.168.1.36:33043` 通过，输出 `PASS: lifted paging and live occupied placement were confirmed; Home process remained stable.`

当前设备布局允许保留，用户不要求还原。设备保持唤醒用于后续正式签名构建，结束收尾时才恢复原来的屏幕超时和锁屏状态。

## 离线与构建回归

- `gradlew.bat clean test lint assembleDebug assembleRelease --console=plain`：通过，186 个 Gradle task 成功；Java/C++ 测试、Lint、Debug 和 Release 均通过。Lint 保留 6 条既有构建配置/资源/SharedPreferences 警告，零错误。
- `scripts/test-native-grid.ps1 -Serial 192.168.1.36:33043`：77 项通过，直接执行生产 C++ 网格、触摸、移动和焦点回归。
- `scripts/test-native-policy.ps1`、`scripts/test-apk-abi.ps1 -ApkPath app/build/outputs/apk/release/app-release-unsigned.apk`、`scripts/verify-project-structure.ps1`：全部通过。
- `verify-launcher-contract.py`：7748 加 Flutter 语义 ABI、8565 独立 profile 均通过；把 7748 语义结构套到 8565 的负例按预期拒绝。

## 0.4.14 历史记录

以下内容保留 0.4.14 的发布前记录；它的历史边界不代表 0.4.15 当前验收范围。

## 原因与修复

- 冷启动时，在字符串构造器解析之前检查了页码重写能力，导致能力未启用。现在在依赖就绪后启用，并兼容公告首尾的换行和窗口焦点短暂丢失。
- TalkBack 不读取 synthetic Flutter source 上滚动事件的替换文本。自动翻页改用已有的公告事件，保留去重和页码指示器的系统描述。
- 页码公告先更新当前页后，桌面复用节点编号，空位列表仍可能停在上一页。节点查询现在同时校验网格所属页面，跨页清除旧空位焦点。
- “系统工具”的数据库名称实际为 `com.miui.home:string/default_folder_title_tools`。对这类资源名文件夹，通过已校准的当前页格子和节点边界确认 favorite；缺少边界、跨页网格或重复记录均拒绝猜测。
- 托起源格可以重新放置，但只释放已确认的来源。无障碍树可能缺少可见图标，不能用缺失节点推断空位；数据库占用和小部件跨度继续生效。
- 放置后、取消动作及同页返回键取消，使用读屏下一次查询所持有的有效 provider 恢复真实图标焦点。恢复只尝试一次，并有超时；不向后台线程保留 provider。

原生焦点动作必须传入 `{1, 0}` 的空参数封装。真机原始动作的首字低位为 1；7748 `perform_action` 在 `0xa3d03c` 检查该位，为 0 时调用 `Bundle_drop`。全零会释放无效 Bundle，调试时已捕获此崩溃；修复后的放置、显式取消、返回键取消均重新通过真机验证。

## 真机结果

| 场景 | 结果 |
| --- | --- |
| 连续第 1 → 8 → 1 页，共 14 次翻页 | 每次 TTS 日志恰好一条“第 X 页共 Y 项”，零条自动“共 N 页” |
| 各页原始项目数 | 16、24、17、1、17、14、11、18；底栏和文件夹内成员不计入 |
| 返回桌面、重启桌面后翻页 | 项目数播报继续生效，页码指示器仍保留“共 8 页” |
| Gemini 同页移动 | 第 1 页第 5 行第 3 列 → 第 4 行第 2 列，并还原 |
| “系统工具”文件夹同页移动 | 第 1 页第 3 行第 3 列 → 第 4 行第 2 列，并还原 |
| Gemini 跨页移动 | 第 1 页原位 → 第 4 页第 1 行第 2 列 → 第 1 页原位 |
| 移动确认 | 实际 op 855，数据库保存及重载后位置一致，目标节点出现，`findFocus` 返回目标图标 |
| 空位生命周期 | 第 2 页满页时 0 个空位，第 4 页原有 1 项时 23 个空位；跨页旧焦点失效 |
| 显式取消、返回键取消 | 虚拟空位消失，焦点回到 Gemini，布局未变化 |
| 布局恢复 | Gemini 和“系统工具”均在原格，重启后仍正确 |

跨页返回第 1 页时，Gemini 尚未放回，实际播报为“第 1 页共 15 项”，与数据库一致。移动回原位后恢复为 16 项。

上述是实际桌面动作、节点、持久化和 TTS 合成文本的自动验证；没有把离线夹具结果当作真人听感验收。未改动手机原有读屏服务配置。调试期间定时唤醒，屏幕超时临时延长至 30 分钟，结束恢复原来的 10 分钟。

## 构建与回归

- `gradlew.bat clean test lint assembleDebug assembleRelease --console=plain`：通过。Java 123 项，零失败、错误、跳过；Lint 零错误，6 条现有构建配置、资源及 SharedPreferences 警告。
- `scripts/test-native-grid.ps1 -Serial <已连接真机>`：67 项通过。该项在设备上执行生产 C++，系统接口使用夹具，与上面的真实桌面验收分别统计。
- `scripts/test-native-policy.ps1`：通过。
- `scripts/test-apk-abi.ps1 -ApkPath app/build/outputs/apk/release/app-release-unsigned.apk`：通过，含错误 APK 拒绝样例。
- `scripts/verify-project-structure.ps1`、`git diff --check`：通过。

新回归覆盖默认文件夹资源名、重复记录拒绝、托起源格释放、不完整节点树的占用保护、公告记录类型、初始化顺序、复用页面节点、焦点恢复次数与空 Bundle 封装。探针也修正了空位描述识别、文件夹识别、翻页完成判断和指定目标格。

本机证据保存在忽略目录 `artifacts/device-20261025/`：`sequence-*.log`、`cross-fixed-move.log`、`cross-return.log`、`final-folder.log`、`cancel.log`、`back-fixed.log`、`final-native.log`、`final-build.log`。临时诊断代码已移除。

## 边界

7.x Java 桌面及 8565/7653/7722 未进行本次真机验收；未知原生 ABI 的能力降级保持生效。多选、占用位置重排、文件夹内部项目和不同读屏配置未进行本次设备测试。Release 为未签名构建，手机安装的是本次 Debug 修复包。
