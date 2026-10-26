# 原生桌面托起期间的边缘返回

## 根因和入口证据

8584 真机托起后，右边缘 `input swipe 1170 1200 670 1200 400` 触发翻页，托起没有取消。返回键和 TalkBack 下再左会进入返回流程，因此只修 `on_back_invoke` 不能解决边缘入口。

检查三份实际 ARM64 `libapp_launcher.so` 的 `.gnu_debugdata`、指令和调用关系，找到原生入口：

`<app_launcher::recents::base_recents_impl::BaseRecentsImpl>::update_window_motion_event_redict`

它的 ABI 是 `void (bool)`，唯一参数在 `w0`，没有 `self` 参数。函数保存参数到 `w19`，遍历左右 GestureStub 窗口，再将该值写入窗口已有的 `need_request_redict`。模块调用这个 setter，不直接写结构字段。

| 桌面 | 入口 | 函数长度 | ELF SHA-256 |
| --- | --- | --- | --- |
| 7748 | `0x6ae0d8` | `0x15c` | `89302282d70e3b40126ccce05fef94560529c0543ff6e05107724dade0d104a7` |
| 8565 | `0x6b1978` | `0x15c` | `771498f45bd11d93be10fe14a36d8d796225c82be3ae4c4da00724291ba9cfd1` |
| 8584 | `0x6b57f4` | `0x15c` | `f70a2d15505abc83bbc2f03cbe9e90b635a8add6d89ae0fef764100aea1c6d8b` |

三份入口的前八条指令相同：

`d10343ff a9077bfd a9086ffc a90967fa a90a5ff8 a90b57f6 a90c4ff4 9101c3fd`

`+0x28` 是 `mov w19,w0`；`+0x94` 读取原生窗口的 Cell；`+0x104` 将 `w19` 写入该 Cell。`scripts/verify-launcher-contract.py` 复核精确符号、地址、长度、序言和这些指令。运行时另检查既有完整 ELF 指纹、系统 ABI 配对和入口序言；未知 ABI 不启用。

## 8584 调用链

- `set_regions_enable` (`0x6b2450`) 在 `0x6b26bc..0x6b26d0` 取反可用的 back 标志，再调用 setter。
- `handle_home_or_no_back_activity` (`0x6b51b4`) 在 Home 没有其他覆盖场景时，以侧边栏是否启用作为 back 标志。侧边栏关闭会使 `needRedirect=true`。
- `GestureStubViewWindow::on_window_motion_event` (`0x778d24`) 仍先验证边缘区域、系统手势排除区和 DOWN 初始化。在 `0x77957c` 判断转发，真值进入 `0x779c68` 的 `request_redirect`，触摸被交给底层桌面翻页。
- DOWN 的 `try_before_handle_down_event` 可再次适配当前活动，重新写入 Home 路由；窗口可见性 looper 回调 (`0x5f6790`) 也会写入。因此只在托起时修改一次不能可靠生效。
- 路由为 false 时，后续处理继续使用原生手势识别；提交返回时从 `0x70910c` 调用已有 `GestureInputBackHelper::on_back_invoke` (`0x729d14`)。

## 修复边界

Hook setter，记住系统最近请求的值。只有已验证 profile、Home 获得焦点且托起有效时，才暂时传入 false。托起开始主动应用；取消、成功放置和失焦恢复系统最近请求。没有自写边缘手势识别，也不改变普通桌面翻页路径。

真机随后确认手势已进入识别，但松手时还有 `BackGesture BLOCKED by classic nav: invoke_back_enable=false`，系统设置实际为全面屏手势（`navigation_mode=2`）。第二个入口是原生 `handle_back_gesture(window*, event*)`：7748 为 `0x6fe948` / 长度 `0x20ac`，8565 为 `0x7039c0` / `0x2100`，8584 为 `0x707a04` / `0x2100`。其前八条指令为 `6db82beb 6d0123e9 a9027bfd a9036ffc a90467fa a9055ff8 a90657f6 a9074ff4`。

三份样本均从窗口的 Arc 基址 `+0xc0`（窗口数据 `+0xb0`）取 `Arc<Cell<bool>>`，再从 Cell 的 `+0x10` 读取 `invoke_back_enable`。读取在 7748 入口 `+0x560`，8565/8584 入口 `+0x584`，机器码是 `f9406288` 和随后 `39404108`。8584 原生 UP 流程在 `0x707eac` 按同一窗口方向获取 Arc，`0x707eb8` 保留基址，`0x707f88..0x707fa0` 读取该开关，最终决定是否提交。该关系同时写入离线接口验证。

只在托起有效的同步识别回调内临时启用这个已验证字段，原生识别完成后恢复原值。原始 DOWN/MOVE/UP/CANCEL、距离和速度阈值、排除区与原生清理继续执行；没有托起或 ABI 未知时直接透传。路由 setter 和识别 Hook 必须同时可用才启用此功能。

原生对象属于 Home looper。无障碍动作和放置完成可能在其他线程，使用 Android 公共 `ALooper_addFd` 与非阻塞 `eventfd` 投递当前状态；回调只在发生状态变化时执行，没有轮询、定时器、保活线程或系统唤醒操作。连续开始/结束只应用回调执行时的最新状态。

文件夹第一返回仍关闭文件夹并保留托起，第二返回取消。Flutter 会将未处理的 DOWN/UP 再分发一次，且边缘返回合成的 DOWN 与 UP 的 downTime 不同；分别记住两个事件的时间标识，允许同一事件继续完成原生关闭流程。新的按键周期不再受 600 毫秒时间去重影响。

事件时间使用已配对 UI public 库导出的 `input_KeyEvent_getDownTime`，ABI 为 `i64 (event*)`。实际公共库函数在 `0x7a798`，从事件 `+0x18` 读入 `x0` 并返回；模块调用导出函数，不直接读取事件字段。没有该接口时保留原时间窗口兼容路径。原生返回回调的重复只按转发调用范围判断，因此第二次完整边缘手势可以立即取消。

## 验证边界

原生回归调用生产托起、返回、失焦入口，检查 Home 路由覆盖、系统回写、恢复最近值、后台放置完成经 looper 恢复，以及未知 ABI 降级。快速两次返回的回归已移除原来人为等待一秒的条件。

离线接口分析使用实际样本、LLVM 符号和 Capstone。当前 REA Windows provider 不支持该 ARM64 ELF，没有将静态分析写成运行结果。完整本地证据在忽略目录 `artifacts/edge-*.asm` 和 `artifacts/edge-redirect-contract.json`；真机验收结果另见 [0.4.17 记录](RELEASE_0.4.17.md)。
