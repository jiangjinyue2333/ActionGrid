# ActionGrid 0.4.5 验证记录

2026-10-20。`versionName = 0.4.5`，`versionCode = 21`。保留工作区已有的兼容性和网格修复，新增变更集中在 `app/src/main/cpp/native_init.cpp`。

## 修复

| 用户问题 | 原因与处理 |
| --- | --- |
| 连续从应用返回桌面，只有第一次播报“系统桌面” | 原逻辑只观察无障碍树，桌面在后台仍保留工作区节点，无法可靠观察离开。新增原生窗口焦点 Hook：失焦重置页面状态、拒绝后台缓存节点；回焦后由新节点或有限次数扫描确认桌面，每次返回只播报一次。 |
| 返回手势未关闭拖动虚拟网格 | 原逻辑只有“取消托起”动作负责清理。返回键、已提交返回手势和取消动作共用取消流程；清除虚拟焦点、网格和来源，刷新父节点并尝试恢复真实图标焦点。手势保留桌面原生回调收尾；离开桌面同样清理。 |
| 空位和真实图标分别浏览 | 原网格缓存真实节点旧编号，只写 traversalAfter。现在更新真实节点身份，并设置前后双向顺序，让“QQ、第 1 行第 2 列空位、微信”在同一序列。桌面重建父节点时重新读取实际布局。 |
| 双指翻页播报等待 | 发现已确认的新页码时直接发送页码及项目数，后到的 Flutter/滚动事件去重；空页播报 0 项。去掉仅凭手指位移提前改页码的推测，取消滑动不会误报或改变放置页。未知项目数或发送失败时保留桌面原生播报。 |

## 模拟器回归

使用已运行的 `emulator-5556`，标准 Android 17 / API 37 / x86_64，未创建 AVD。

```powershell
./scripts/test-native-grid.ps1 -Serial emulator-5556
```

22 项通过。测试直接编译生产 `native_init.cpp` 并在 Android 执行，只在桌面系统 ABI 边界提供节点、事件和数据库输入。新增覆盖：

- 10 轮失焦、后台缓存节点查询、回焦、重复查询，恰好 10 次“系统桌面”事件；保留全部原生焦点回调。
- 返回键按下/抬起、正常按键透传、已提交返回手势、离开桌面时的网格清理。
- 真实图标编号更新，以及“QQ → 空位 → 微信”的正反方向关系。
- 新页码节点到达时立即发送事件；稍后的 Flutter 和滚动事件均不重复。
- 空白页、来回翻页、取消滑动、项目数未知和事件发送失败时的原生回退。
- 原有稀疏网格、触摸探索、跨页放置参数、移动结果确认等 12 项。

修复前失败日志：`artifacts/hyperos4-grid/interaction-before-fix.log`、`lifecycle-before-fix.log`。最终日志：`interaction-final.log`。

## 二进制接口核对

`scripts/verify-launcher-contract.py` 使用实际桌面 ELF、内嵌符号和指令字节验证，无需执行目标库。

| Hook | 7748 地址 | 8565 地址 | ABI |
| --- | --- | --- | --- |
| MainUiWindow focus | `0x757b34` | `0x772360` | `void(window*, bool)` |
| LauncherUi key | `0x9736ec` | `0x97a484` | `bool(scene*, event*)` |
| GestureInputBackHelper commit | `0x720ffc` | `0x725c54` | `void(helper*)` |

两个版本的符号、序言、实参寄存器、返回值、调用链和手势 callback/drop 均通过；8 个内存中故障变异被拒绝。证据保存在 `contract-7748-0.4.5.json` 和 `contract-8565-0.4.5.json`。

`traversalAfter`/`traversalBefore` 实际是三参数 `(node*, hostViewId, virtualId)`。已核对的 public 库只导出 after，before 使用同一已验证 AIDL 库的导出；没有对未知系统结构直接写偏移。新增生命周期 Hook 仅对实际核对过的 7748/8565 安装，7653/7722 保留已有功能。

## 构建与正式包

- 项目结构检查、native ABI 策略回归通过。
- 正式构建脚本执行 `clean test lint assembleRelease` 成功；102 项 Java 测试，0 失败、0 错误、0 跳过。
- 补充执行 `assembleDebug lintRelease` 成功。Debug/Release 的 Lint 均无错误；各保留 8 条警告，涉及已有反射、SDK/构建版本建议等。
- APK ABI 正反例通过，包括拒绝错误 multiArch、缺库、空库、错误 ELF 和意外架构。
- 正式签名复用受保护的本地发布脚本和现有密钥；没有将密钥或密码写入工作区。
- `verify-release.ps1 -ExpectedTag v0.4.5` 通过：包名、版本 0.4.5 / 21、Xposed 元数据、DEX、arm64 ABI 与固定发布证书一致。

产物：`artifacts/ActionGrid-0.4.5.apk`，407447 字节。SHA-256：

```text
5ed1d4261b371d957f01ad1eadf21261705447b4ea371a97ac8ea0e3c5d9b764
```

构建日志：`artifacts/hyperos4-grid/release-0.4.5-build.log`、`release-0.4.5-additional-checks.log`。仅生成本地正式包，未创建远程 Release 或修改在线更新清单。

安装检查另用 `emulator-5558` / `actiongrid_045_verify` 临时 AVD，复用 D 盘已安装的 Android 17 系统镜像。原 AVD 中的旧包使用不同证书，为保留其数据而没有卸载或替换。该正式 APK 安装结果为 `Success`，启动 `.MainActivity` 返回 `Status: ok`，系统确认 `versionName=0.4.5`、`versionCode=21`、`primaryCpuAbi=arm64-v8a`；日志为 `release-install.log`。验证完成后已停止并删除临时 AVD。这项检查只覆盖管理应用安装与启动。

## 验证边界

模拟器没有澎湃 4 桌面及其专用系统库。通过的是 Android 上的生产原生逻辑回归和真实桌面文件的静态 ABI 核对，未在模拟器中启动小米桌面，未测量真实 TalkBack/解说语音合成延迟。连续返回的 10/10 指播报事件次数，不等于已完成真机听感测试。

真机仍需确认：任意应用分别用返回和 Home 回桌面各 10 次；拖动中用返回手势取消；稀疏布局按“图标、空位、图标”正反浏览；双指翻页的实际语音时延和去重。
