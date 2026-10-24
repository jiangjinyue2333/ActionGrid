# 7748 离线 FRB 契约核对

本报告核对本地 `tmp/abi-7748/libapp_launcher.so` 与 `tmp/abi-8565/libapp_launcher.so`，不连接设备，不执行目标库。结论只覆盖所给二进制的静态接口，不能替代真机放置、数据库一致性或 TalkBack 验收。

## 文件结构

- `scripts/verify-launcher-contract.py`：标准库 ELF64/AArch64 解析、PT_LOAD 虚拟地址到文件偏移映射、`.gnu_debugdata` XZ 有界解压、调用指定 `llvm-nm` 读取符号，再核对导出跳转、FRB 跳转表、opcode 目标、reload 序言、batch 到 `Vec<ItemInfo>`、单项 move 的完整参数及网格和触摸调用链。
- 本报告记录样本身份、验证命令与解码证据。临时样本和反汇编输出均位于忽略目录 `tmp/`。
- 此工具只验证显式选择的 7748/8565 离线基线，不生成或猜测运行时 profile，不修改原生实现。

## 定位结果

| 项目 | 7748 | 8565 |
| --- | --- | --- |
| SHA-256 | `89302282d70e3b40126ccce05fef94560529c0543ff6e05107724dade0d104a7` | `771498f45bd11d93be10fe14a36d8d796225c82be3ae4c4da00724291ba9cfd1` |
| dispatcher 导出 | `0x9b750c` | `0x9be128` |
| dispatcher 实现 | `0x7ee954` | `0x7f5844` |
| 半字跳转表 | `0x186cb2` | `0x186cfe` |
| stub 基址 | `0x7ee978` | `0x7f586c` |
| 单项目 move opcode / 实现 | `831` / `0x87bb20` | `833` / `0x881cb8` |
| batch update opcode / 实现 | `855` / `0x89bca8` | `857` / `0x8a1dd8` |
| reload | `0x7a35a4` | `0x7aa050` |
| Vec<ItemInfo> 解码器 | `0x9702c4` | `0x976fe4` |
| ItemInfo 解码器 | `0x967cd4` | `0x96dbb0` |
| 网格列数 getter | `0x6c5a74` | `0x6c9588` |
| 网格行数 getter | `0x6c5b20` | `0x6c9634` |
| 共享 motion 入口 | `0xd37904` | `0xd40fd8` |
| LauncherUi 普通 / v2 调用点 | `0x743a9c` / `0x743e64` | `0x75df38` / `0x75e300` |

7748 的 **op 854 是单个 `update_item_database_impl`**（`0x877310`），不能接收本模块批量格式。脚本必须用真实跳转表证明 op 855 指向 `launcher_model_update_item_database_batch_impl`，并在把预期 batch opcode 错写为 854 时返回失败。

两个样本的 reload 前四条指令均为 `d10343ff a90b7bfd a90c4ff4 9102c3fd`。虚拟地址映射来自 ELF 的 PT_LOAD；这些样本的执行段为 `file_offset = vaddr - 0x4000`，只读表段为 `file_offset = vaddr`，工具不硬编码该换算。

## 验证与解码记录

2026-10-19 实际执行：7748 和 8565 均通过；7748 显式使用 `--batch-op 854` 返回退出码 1，错误目标为单项 `update_item_database_impl`。这不是对原生源码字符串进行匹配，而是从样本真实跳转表读取条目、解释 ARM64 跳转并对照 `.gnu_debugdata` 的精确符号。

```powershell
$nm = 'D:/Android/Sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe'
python scripts/verify-launcher-contract.py tmp/abi-7748/libapp_launcher.so --profile 7748 --llvm-nm $nm --json
python scripts/verify-launcher-contract.py tmp/abi-8565/libapp_launcher.so --profile 8565 --llvm-nm $nm --json
# 此命令预期失败，退出码必须为 1：
python scripts/verify-launcher-contract.py tmp/abi-7748/libapp_launcher.so --profile 7748 --llvm-nm $nm --batch-op 854
```

7748 batch 包装函数在 `0x89be08` 调用 `Vec<ItemInfo>::sse_decode`（`0x9702c4`）。后者在 `0x9702ec` 调用 `i32::sse_decode` 读长度，在 `0x97031c` 循环调用 `ItemInfo::sse_decode`（`0x967cd4`）。这是 `i32 count + ItemInfo[count]`，与 `native_init.cpp` 先写入所有最终位置的数量，再逐项 `appendSparseItemInfo` 的格式一致；插入已有图标前后时包含被顺移的项目。

7748 的两个可选数值解码器分别位于 `0x971958`（Option<i32>）和 `0x971990`（Option<i64>），先调用 `bool::sse_decode`（`0x95a764`）消耗一个存在标记字节，非零时再读 4 或 8 字节。`i32::sse_decode`（`0x967a24`）与 `i64::sse_decode`（`0x967a94`）分别增加游标 4/8 并直接加载小端值。由此可以核实 `WireWriter.none/someI32/someI64` 的标记及字节宽度。

根据实际调用及条件分支，35 个可选字段的顺序如下。名称栏沿用现有 `appendSparseItemInfo` 的基线命名；二进制直接证明的是类型、顺序、存在标记和解码路径，不声称从无字段名的机器码恢复了所有业务含义。

| 字段序号 | 解码类型 | 当前稀疏写入 |
| --- | --- | --- |
| 1 | Option<i64> | id，Some |
| 2 | Option<Intent> | None |
| 3 | Option<i32> | itemType，Some |
| 4 | Option<i32> | user/profile，None |
| 5–9 | Option<i32> × 5 | spanX、spanY、screen、cellX、cellY，Some |
| 10 | Option<i64> | container，Some |
| 11 | Option<i64> | rank/order，None |
| 12–13 | Option<i32> × 2 | None |
| 14–15 | Option<String> × 2 | title/label，None |
| 16 | Option<bool> | None |
| 17–19 | Option<i32> × 3 | None |
| 20–22 | Option<String> × 3 | None |
| 23 | Option<i32> | None |
| 24 | Option<String> | None |
| 25–26 | Option<i32> × 2 | None |
| 27 | Option<String> | None |
| 28 | Option<i32> | None |
| 29 | Option<String> | None |
| 30 | Option<Vec<u8>> | None |
| 31 | Option<i32> | None |
| 32 | Option<bool> | None |
| 33–35 | Option<i32> × 3 | None |

当前每项有 35 个存在标记、两个 i64 值和六个 i32 值，合计 **75 字节**，批量数据为 **4 + 75 × 项目数** 字节。7748 op 831 / 8565 op 833 单项 move 使用 `ItemInfo + i64 container + i32 screen + i32 cellX + i32 cellY`，不带 vector 长度，合计 **95 字节**。这四个尾参数没有 Option 标记，不能省略。7748 op 855 batch 必须带 vector 长度。

单项尾参数解码调用点：7748 为 `0x87bca4`、`0x87bcb0`、`0x87bcbc`、`0x87bcc8`，结束检查在 `0x87bcd4`；8565 对应记录由脚本 JSON 输出。稀疏 ItemInfo 中缺省的 title、label、intent 在转成数据库更新字段时被跳过，不会被写成空字符串。

网格 getter 的 ABI 为 `i32 ()`，序言为 `d10083ff a9017bfd 910043fd`。motion 的 ABI 为 `void(scene*, event*)`，事件位于 x1；虽然符号名带有 SafeLauncherUi，普通 LauncherUi 的普通和 v2 入口也调用相同地址，属于编译器合并的实现，不能据符号名认定它只处理安全模式。脚本同时追踪 SceneDelegate、AccessibilityPlugin，并通过 PLT/GOT 证明 `input_MotionEvent_clone`、`input_MotionEvent_setAction` 和 `input_MotionEvent_drop` 的调用及参数。

还逐条比较了 7748 与 8565 的 ItemInfo 解码函数：两者长度均为 `0x4c4`（305 条 ARM64 指令），所有非 BL 指令字完全相同。改变位移的 38 个内部 BL 均指向相同的解码函数名；唯一没有 debug 函数符号的差异调用为 `0x967fe4 -> 0xdfb930` 与 `0x96dec0 -> 0xdf95e0`，`llvm-objdump` 分别将目标标为 `memcpy@plt`。其他 BL 位移本身相同。因此 7748 的该解码器与现有 8565 基线保持同一字段和布局协议。

反汇编复查入口示例：

```powershell
$objdump = 'D:/Android/Sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objdump.exe'
& $objdump -d --start-address=0x967cd4 --stop-address=0x968198 tmp/abi-7748/libapp_launcher.so
& $objdump -d --start-address=0x96dbb0 --stop-address=0x96e074 tmp/abi-8565/libapp_launcher.so
```

以上离线证据来自本机 LLVM 与标准库脚本。REA 会话报告当前 Windows Ghidra 路径只接纳 x86/x64 PE，未为这个 ARM64 ELF 创建分析会话。该脚本不使用 ADB，也不运行库内函数；另行执行的 Android 模拟器原生回归及其验证边界见 [网格修复记录](HYPEROS4_GRID_REPAIR.md)。实际模型事务及跨文件夹移动结果仍需独立设备验收。
