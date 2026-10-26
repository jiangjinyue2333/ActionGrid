# 系统点按音效接口证据

0.4.17 的正常图标和文件夹点击通过系统音效接口补齐点按提示音。`TYPE_VIEW_CLICKED` 仍保留无障碍反馈，但它本身不等于系统点按音效。模块不包含音频文件，不修改音量或用户的点按提示音设置。

## 原生桌面

本地 7748、8565、8584 的 `PlatformHandler::play_sound` 在 click 分支均将 `w0=0`、`w1=1` 传给 `audio_manager_play_sound_effect`。对应入口分别为 `0xa5f28c`、`0xa65ab8`、`0xa6c0c8`；这些地址仅记录证据，运行时通过导出符号解析。`scripts/verify-launcher-contract.py` 校验完整五条指令及 PLT 对应的真实导入名称。

2026-10-26 从 8584 真机读取的 `/system_ext/lib64/libhyper_os_media_public.so`：

- SHA-256：`58394d6273811da99d2a5d989e6d0b023e7b7be0a1280823b90abaedc063e9c6`。
- 项目 ELF 结构指纹：`495cd5c5204db7db2854eccdacdd03b5`。
- 导出 `audio_manager_play_sound_effect` 位于 `0x26988`。它从 `x1` 拆解低位 Option 判别和高 32 位用户 ID，再调用系统 media private 的 `AudioManager::play_sound_effect`。因此函数参数是 `(int32_t effect, uint64_t optionalUser)`；`1` 表示不指定用户，并由下层选择当前用户 `-2`，不是音量或布尔开关。
- private 方法位于 `0x631e0`，向 IAudioService 的 `playSoundEffect(effect,userId)` 传递系统音效编号与用户 ID。

同一真机 `/system/framework/services.jar`（SHA-256 `29f51dfd9e59705628d8c9d05db459d204ef609c58428a23b5b05ab63ef3e3ee`）中的 `AudioService.playSoundEffect` 先读取指定用户的 `sound_effects_enabled`，开启时才调用 `playSoundEffectVolume`；后者还检查系统音量流静音状态。

运行时只在已验证桌面和系统 ABI 配对、media public 结构指纹匹配、音效 Hook 成功时补调用。未知 media ABI 保持原有点击行为。使用同一点击调用期间的系统音效计数，避免桌面已经调用系统音效时再补一次。

## 7.x View 路径

使用 `View.playSoundEffect(SoundEffectConstants.CLICK)`，沿用 Android 的 View 音效开关、用户点按提示音设置与系统音量。在原点击已经调用 `playSoundEffect(CLICK)` 时不重复播放。只有点击返回成功才补音效；托起、探索和焦点动作不会触发。

## 验证边界

以上接口证据来自 LLVM、Capstone 和 JADX 的本地静态读取。REA 当前 Windows Ghidra provider 不支持 ARM64 ELF，因此未把普通工具输出标为 REA Evidence。JADX 的单类输出包含一个其他方法的反编译错误；上述两个音效方法成功输出，原始 jar 与输出保存在忽略目录 `artifacts/click-*`。

`native_grid_test.cpp` 通过生产 `onPerformAction` 检查正常图标点击恰好调用一次系统接口、已有音效不重复、失败点击不播放和未知系统 ABI 不调用。真实音效播放及关闭开关后的静音情况还须以设备验收记录为准，静态证据不代表人工听感验收。
