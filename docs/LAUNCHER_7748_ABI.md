# 7748 FRB 与语义接口契约核对

本报告的离线部分核对本地 7748/8565 `libapp_launcher.so` 及 7748 `libhyper_os_flutter.so`，不连接设备，不执行目标库。末节另记录主调试流程取得的真机语义恢复证据；这些局部结果不能替代完整的放置、数据库一致性或 TalkBack 验收。

## 文件结构

- `scripts/verify-launcher-contract.py`：标准库 ELF64/AArch64 解析、PT_LOAD 虚拟地址到文件偏移映射、`.gnu_debugdata` XZ 有界解压、调用指定 `llvm-nm` 读取符号，再核对导出跳转、FRB 跳转表、opcode 目标、reload 序言、batch 到 `Vec<ItemInfo>`、单项 move 的完整参数、7748 insert/delete 的单项解码及网格和触摸调用链。可选 `--flutter` 额外核对 7748 语义开关、桥对象持有关系和引擎任务调用链。
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
| insert item opcode / 实现 | `827` / `0x876608` | 未核对，不启用 |
| delete item opcode / 实现 | `818` / `0x875bf8` | 未核对，不启用 |
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

根据实际调用及条件分支，35 个可选字段的顺序如下。名称栏沿用 `appendSparseItemInfo` 的基线命名；二进制直接证明类型、顺序、存在标记和解码路径。2026-10-25 另通过数据库列名确认第 14、15 字段分别为 title、label，证据见下文；其余未逐项核对的业务名称仍属于基线命名。

| 字段序号 | 解码类型 | 移动时的稀疏写入 |
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

上述移动格式每项有 35 个存在标记、两个 i64 值和六个 i32 值，合计 **75 字节**，批量数据为 **4 + 75 × 项目数** 字节。7748 op 831 / 8565 op 833 单项 move 使用 `ItemInfo + i64 container + i32 screen + i32 cellX + i32 cellY`，不带 vector 长度，合计 **95 字节**。这四个尾参数没有 Option 标记，不能省略。7748 op 855 batch 必须带 vector 长度。

单项尾参数解码调用点：7748 为 `0x87bca4`、`0x87bcb0`、`0x87bcbc`、`0x87bcc8`，结束检查在 `0x87bcd4`；8565 对应记录由脚本 JSON 输出。稀疏 ItemInfo 中缺省的 title、label、intent 在转成数据库更新字段时被跳过，不会被写成空字符串。

网格 getter 的 ABI 为 `i32 ()`，序言为 `d10083ff a9017bfd 910043fd`。motion 的 ABI 为 `void(scene*, event*)`，事件位于 x1；虽然符号名带有 SafeLauncherUi，普通 LauncherUi 的普通和 v2 入口也调用相同地址，属于编译器合并的实现，不能据符号名认定它只处理安全模式。脚本同时追踪 SceneDelegate、AccessibilityPlugin，并通过 PLT/GOT 证明 `input_MotionEvent_clone`、`input_MotionEvent_setAction` 和 `input_MotionEvent_drop` 的调用及参数。

还逐条比较了 7748 与 8565 的 ItemInfo 解码函数：两者长度均为 `0x4c4`（305 条 ARM64 指令），所有非 BL 指令字完全相同。改变位移的 38 个内部 BL 均指向相同的解码函数名；唯一没有 debug 函数符号的差异调用为 `0x967fe4 -> 0xdfb930` 与 `0x96dec0 -> 0xdf95e0`，`llvm-objdump` 分别将目标标为 `memcpy@plt`。其他 BL 位移本身相同。因此 7748 的该解码器与现有 8565 基线保持同一字段和布局协议。

反汇编复查入口示例：

```powershell
$objdump = 'D:/Android/Sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objdump.exe'
& $objdump -d --start-address=0x967cd4 --stop-address=0x968198 tmp/abi-7748/libapp_launcher.so
& $objdump -d --start-address=0x96dbb0 --stop-address=0x96e074 tmp/abi-8565/libapp_launcher.so
```

## 7748 文件夹插入、删除与刷新证据

以下核对于 2026-10-25，使用上表中的 7748 样本及 NDK `28.2.13676358` 的 LLVM 工具。只为 7748 增加基线，不能按操作号差值推导 8565 的接口。

### 单项数据库接口

| 操作 | dispatcher stub | wire 函数 | ItemInfo 解码调用 | 紧接的 end 调用 |
| --- | --- | --- | --- | --- |
| op 827 insert | `0x7f1038` | `launcher_model_insert_item_database_impl`，`0x876608`，长度 `0x47c` | `0x876788 → 0x967cd4` | `0x876790 → 0xaa5b20` |
| op 818 delete | `0x7f1074` | `launcher_model_delete_item_database_impl`，`0x875bf8`，长度 `0x47c` | `0x875d78 → 0x967cd4` | `0x875d80 → 0xaa5b20` |

两者 wire 都只读一个 ItemInfo，没有 vector 长度、额外 bool 或坐标尾参数。脚本从 dispatcher 跳转表验证目标，再要求 wire 中唯一的 `SseDecode` 调用为 ItemInfo，下一次直接调用在其后 8 字节且必须为 `SseDeserializer::end`。因此错误 opcode、额外解码或缺失结束检查都会失败；JSON 中 `decoder.single_item_database` 记录这两个调用位置。8565 的此项为空，表示未核对。

本次离线验证：7748 和 8565 正常样本均通过。另在内存中分别对 insert/delete 交换 dispatcher 条目、插入额外 bool 解码调用、用 NOP 替换 end 调用，共六个反例，均被相应断言拒绝；磁盘样本未修改。

```powershell
$nm = 'D:/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe'
python scripts/verify-launcher-contract.py tmp/abi-7748/libapp_launcher.so --profile 7748 --llvm-nm $nm
python scripts/verify-launcher-contract.py tmp/abi-8565/libapp_launcher.so --profile 8565 --llvm-nm $nm
```

insert 的执行器 `0x55d3ac` 在 `0x55d448` 调用 `LauncherModelInternal::insert_item_database 0x79b050`：先将 Some 字段转换为 ContentValues（`0x79b078 → 0x902e2c`），再调用 `generate_max_id`（`0x79b080 → 0x725b98`）。该函数用 `LDAXR/STLXR` 对 `0xf1b0d8` 的计数原子加一，返回新 ID。`0x79b09c` 将新 ID 写入 `_id`，`0x79b0b8` 调用 `DatabaseManager::insert 0x71c060` 插入 `favorites`，`0x79b138` 将 `Some(newId)` 写回返回 ItemInfo。传入的 ID 会被覆盖，不应自行预测新 ID。

插入的数据库 Result 只被记录日志并释放，返回 ItemInfo 不等于数据库已成功插入。调用方必须重新查询新行，再确认成员移动；若没有接收 FRB 返回值，识别新行时也不能仅假定 `MAX(_id)+1` 或凭同名文件夹认定成功。

delete 的执行器 `0x55ccfc` 在 `0x55cdf8` 调用 `DatabaseManager::delete 0x4fd96c`。表名为 `favorites`（字符串 `0x1f380c`，长度 9）。选择条件通过 `format_inner 0x9d9768` 构造：`0xe961a0` 的相对重定位指向 `0x286712`，长度 3，内容 `_id`；格式数据 `0x19d2e6` 为 `c0 02 3d 3f 00`，代入后得到 `_id=?`。唯一参数取 ItemInfo.id，缺省值为 0。此路径没有 container 条件、空夹检查或递归成员删除。

itemType=2 不在 `0x55ce98` 起的快捷方式类型位图内，会直接跳到 `0x55d12c`，不执行快捷方式移除与 unpin 分支。删除空夹必须由调用方先确认成员已移出、父项类型为 2、该 ID 的成员数为零；调用后还须确认父项消失，不能只根据返回 ItemInfo 播报成功。

### 创建文件夹字段

ItemInfo 的第 14、15 个字段分别写到结构体 `+0x30`、`+0x48`。第 14 字段在 `0x967dc4` 解码，`0x968008` 写入 `+0x30`；`item_info_to_content_values` 在 `0x903004` 使用列名 `0x28377a`（长度 5，`title`）。第 15 字段在 `0x967de8` 解码，`0x968024` 写入 `+0x48`；`0x903028` 使用列名 `0x271d97`（长度 5，`label`）。

`Option<String>` 的 Some 编码是一个值为 1 的存在字节、一个小端 i32 UTF-8 字节数、对应的 UTF-8 字节。`String::sse_decode 0x967910` 调用 `Vec<u8>::sse_decode 0x970590`；后者在 `0x9705b4` 读 i32 长度，在 `0x9705dc` 循环读 u8，随后 `0x967940` 调用 `core::str::converts::from_utf8 0xa235d0`。长度是字节数，不是中文字符数。

系统自己的 `init_folder 0x9006dc` 为工作资料创建文件夹时，实际写入新 ID（`0x9008b8` 生成）、`container=-100`（`0x9008e8`）、相同的 title/label（`0x900904/0x900920`）、`itemType=2`（`0x900938`）及 `spanX=spanY=1`（`0x900950/0x900968`），最后在 `0x900984` 插入 favorites。这是字段初始化的原生证据；该函数有工作资料条件，不能把它当作通用创建文件夹入口调用。

模块可使用已核对的 op 827 插入上述字段及目标数据库 screen/cellX/cellY，再确认新 ID，并通过 op 855 更新两个成员的 container 和顺序。应在父项与成员一致后统一刷新；失败时先恢复成员，仅清理确认无成员的新父夹。这里描述接口支持的流程，未凭静态分析宣称整套创建、回滚或读屏操作已通过真机验收。

### reload 与已打开文件夹

`maybe_trigger_workspace_reload 0x7a35a4` 会调用 `load_and_bind_workspace 0x79c644`。后者从 favorites 查询 30 列；列名表位于 `0xe3b940`，从 0 开始的索引 7、10、11、12、18 分别是 container、screen、cellX、cellY、sortMode。查询没有 workspace container 过滤或排序。每行重建 ItemInfo，包含 type2 文件夹；`0x79e454` 调用 `TrackedStreamSink<Vec<ItemInfo>>::add 0x6a60a0` 将向量传回 Dart。Rust 这条链没有构造文件夹 children 或按文件夹顺序排序。

reload 忙时可提前返回，调用返回不能作为绑定已完成的信号。这条链只能证明桌面项会被重新下发，不能证明已打开文件夹的 children、顺序或焦点同步更新；这些状态仍须在真实节点上独立验收，不能用数据库写入成功替代界面验收。

原生字符串 `delete_empty_folder failed:`（`0x191780`，引用 `0x936350`）属于 `UpgraderV34`，不在 reload 链上。Dart 中虽存在 `_removeEmptyFolders: folder id=`，目前没有静态证据证明其触发条件或每次 reload 都会清理空夹。父夹清理应依赖明确的成员计数、op 818 和结果复查。

以上离线证据来自本机 LLVM 与标准库脚本。REA 会话报告当前 Windows Ghidra 路径只接纳 x86/x64 PE，未为这个 ARM64 ELF 创建分析会话。该脚本不使用 ADB，也不运行库内函数；另行执行的 Android 模拟器原生回归及其验证边界见 [网格修复记录](HYPEROS4_GRID_REPAIR.md)。实际模型事务及跨文件夹移动结果仍需独立设备验收。

## 7748 语义树恢复接口与真机对照

7748 APK 内 `libhyper_os_flutter.so` 的 SHA-256 为 `78769d2aad2dc2f2641b221dd3226e11051642d987572a3eef0e893d2df68cda`，GNU Build ID 为 `4ae44cae83c5140c46697ecdf193338e17aa8668`。按项目 `fingerprintElfBytes` 算法排除 PT_NOTE、`.note.*`、`.comment` 后，双 hash 为：

```cpp
KnownFingerprint{0xbf47eb419843a27cULL, 0x13d458eb64bb5168ULL}
```

此值直接用 `scripts/verify-native-abi.ps1` 中现有 `ActionGridElfFingerprint.Compute` 计算；同次对 7748 launcher 的计算得到 `0x6018f13c7a9d6274 / 0xd0b4054c1d248904`，与现有基线一致。恢复能力必须同时匹配 7748 launcher、系统无障碍库及这个引擎指纹，不能只根据 Build ID 或版本名称启用，也不能套用于 8565。

`HYPER_FLUTTER_INTERFACE` 是导出的结构体本体，RVA `0x10c1b40`，符号大小和首个 u64 均为 `0x268`。`dlsym` 得到的就是表地址，不应再解引用成另一个表。launcher 在 `0xb79d80` 复制 `min(size, 0x268)` 字节到缓存表 `0xf216c8`。

| 表偏移 | 引擎 RVA | 已核实功能 |
| --- | --- | --- |
| `+0x48` | `0x66a5d0` | 销毁 holder |
| `+0x90` | `0x66ba80` | 设置 accessibility features |
| `+0xf0` | `0x66db90` | 启用无障碍语义 |
| `+0xf8` | `0x66dc58` | 停用无障碍语义 |
| `+0x158` | `0x66e548` | 高对比文字，不能当作语义开关 |

启用、停用入口的操作 ABI 都是 `void (holder*)`，不应根据返回寄存器判断成功。两者先保留 holder `+0x670/+0x678` 的平台视图引用，再取 `delegate = *(*(holder+0x670)+8)`，通过 delegate 虚表 `+0x60` 传入 bool 1/0。2026-10-25 20:55:08 的真机原始 enabled hook（PID/TID 8114）记录该虚表为引擎 `+0x10fbbe8`，目标为 `+0xdaf54c`，与离线样本一致。

该目标取 delegate `+0x98` 的任务队列。它先判断当前线程是否可直接执行；是则同步调用闭包，否则投递闭包。因此调用方不能假定它一定延迟执行，也不能在持有模块锁时调用。闭包 `0xdbc4a4` 保存 Engine 的弱引用，先检查控制对象和 alive 字节，再写 RuntimeController `+0x130`，经 `0xe26b38` 调用 Dart `_updateSemanticsEnabled`。函数名在 `0xe95474` 初始化，缓存位于 `PlatformConfiguration+0xf8`，persistent handle 位于该缓存 `+0x10`，与调用处读取 `+0x108` 对应。原生路径没有新旧 bool 相等时退出的分支；Dart 内部短路逻辑尚未静态还原。

holder 必须来自仍活着的同步 provider 调用。private provider 参数 q 的虚表 `+0x20` 必须实际等于 launcher `+0xa3c3d8`；该 adapter 在 `0xa3c3e0` 解引用参数，然后调用原始 getter `0xa3d70c`。匹配后才可按以下关系获取 holder：

```text
bridgeArc = **(q + 8)
groupedArc = *(bridgeArc + 0x38)
innerArc = *(groupedArc + 0x10)
holder = *(innerArc + 0x38)
```

原始 `dispatch_semantics_action` 使用相同链；bridge 的 Arc 销毁路径 `0xa3bcd4..0xa3bcf4` 释放 `+0x38` 的强引用，inner 的销毁路径在 `0xb8e6f8` 调用 holder destroy。这个证据支持同步查询期间的生命周期，不能据此把裸 holder 保存给后台线程。应在原始 getter 返回后、合法调用尚未结束且未持模块锁时执行切换。ARM64 tagged heap pointer 需先去除 top byte 后做 maps 地址范围检查，实际读取仍应遵循平台指针规则。

新增离线校验命令：

```powershell
$nm = 'D:/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe'
python scripts/verify-launcher-contract.py tmp/abi-7748/libapp_launcher.so --profile 7748 --llvm-nm $nm --flutter tmp/abi-7748/libhyper_os_flutter.so --json
# 此命令必须失败，防止把 7748 语义指针链套用于 8565：
python scripts/verify-launcher-contract.py tmp/abi-8565/libapp_launcher.so --profile 8565 --llvm-nm $nm --flutter tmp/abi-7748/libhyper_os_flutter.so
```

2026-10-25 实际验证：7748 加 `--flutter` 通过；8565 原有校验仍通过，携带 `--flutter` 返回退出码 1。另对真实样本的内存副本分别改动表大小、enabled slot、enabled/disabled 的 bool、holder 字段、weak alive 检查、任务投递调用、Dart handle 字段、delegate 目标、bridge 强引用字段和 adapter 解引用，共 11 个反例，均被对应断言拒绝。原始 ELF 文件未修改。JSON 记录 `semantics` 字段；未给 `--flutter` 时不验证这项能力。

真机对照已取得以下结果，原始记录保存在 `artifacts/device-20261025/`：

- `page-node-count-red.txt`：第三页期望 18 项，实际 `DESKTOP_ITEMS 1`，断言失败。
- 同步 provider 路径实际 callback 为 launcher `+0xa3c3d8`，取到的 holder 与 enabled hook 记录相同；执行一次 disabled→enabled 后，`reset-page3-tree.txt` 为 `DESKTOP_ITEMS 18`。
- `reset-touch-page3.txt`：经真实 UHID 触摸 ChatGPT 后，节点 `com.miui.home:id/icon_icon | ChatGPT` 的 `accessibilityFocused=true`。

这证明该次第三页语义缺失及触摸焦点的对照成立。所有页面持续翻页、虚拟网格触摸和滑动、返回取消、跨页放置、图标前后移动、文件夹内移动、移入移出和创建文件夹的完整验收仍待主调试流程逐项完成，不能由上述局部成功推断全盘通过。
