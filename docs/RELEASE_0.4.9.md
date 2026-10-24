# ActionGrid 0.4.9 验证记录

2026-10-22，版本 `0.4.9` / `25`。保留 0.4.8 对返回桌面丢失操作、单应用双击空位误报失败的修复，补齐无需电脑的详细日志。

## 问题与实现

用户提供的旧导出文件只有管理应用记录。澎湃 4 桌面是没有 Java VM 的原生 Rust 进程，原来的 `ActionGridNative` 输出停留在 logcat，没有进入应用的分享文件。

- 原生日志先进入最多 256 行的内存队列，工作线程通过桌面已有的 `content_resolver_operation_call` 调用诊断 Provider。输入、触摸和节点回调不执行 Binder 或文件写入。
- 每批最多 24 KiB / 64 行，单行最多 1 KiB，截断保留完整 UTF-8 字符。失败保留待发送内容并退避重试；队列溢出保留最近记录并标明丢弃数量。收到与当前 session 匹配的确认后才消费队列。
- 日志包括模块版本、PID、时间和顺序号、ABI profile、能力与 Hook 状态、窗口焦点、返回后的少量图标操作注入结果、操作开始/结果、页面、网格占用图、托起源、快照状态和现有放置失败原因。每次返回最多记录前 8 个图标查询，周期状态约每 30 秒一次。
- Provider 按实际 Binder UID 校验自身或 `com.miui.home`，不相信调用方声称的包名。启用状态与 session、批次校验和写入共用存储锁；关闭后旧批次被拒收，重新启用获得新 session。
- `RotatingLog` 保存当前文件和三个历史文件，每份最多 512 KiB。升级保留原有当前文件及日志开关。关闭/重新开启清理上一轮记录；已收到的历史不会因桌面进程重启而清空。
- “发送日志”在后台整理独立快照，按旧到新合并，并加入模块、系统、设备型号、桌面版本、框架/作用域、无障碍服务和原生最近连接时间。导出继续使用平台 URI 读授权。开关、准备状态与分享按钮保持原生控件和读屏支持。

## 原生 ABI 证据

核对本地 `libapp_launcher.so` 的 7748 与 8565 样本。SHA-256 分别为：

```text
7748 89302282d70e3b40126ccce05fef94560529c0543ff6e05107724dade0d104a7
8565 771498f45bd11d93be10fe14a36d8d796225c82be3ae4c4da00724291ba9cfd1
```

`ContentResolver::call` 包装器位于 `0xb78dd0` / `0xb80620`，相应调用和所有权路径位于 `0x8fd778` / `0x90486c`。核对内容包含：authority 不带 `content://`、构造器接收保留的 `Shared<Runtime>` 地址、extras 借用与 Bundle 释放、48 字节返回的两个 Option 分支、错误 RString 释放、`Bundle_get_i64` 和 `Bundle_insert_i64` 的参数约定。原生传输仅对已验证的 7748/8565 profile 及匹配系统 ABI 启用。

## 已执行验证

- `scripts/test-native-grid.ps1 -Serial emulator-5556`：45 项通过。包含原有 39 项桌面行为回归，以及队列开关与 session 隔离、溢出/重试、UTF-8 和批次上限、实际日志函数到模拟 ContentResolver 的确认/失败处理、返回值所有权，以及返回桌面和空位失败的日志内容。
- `gradlew.bat clean test lint assembleDebug assembleRelease assembleDebugAndroidTest --console=plain`：成功。118 项 Java 单元测试，0 失败、0 错误、0 跳过；Lint 为 0 错误、4 项警告。Debug、Release 与测试 APK 构建成功。
- `DiagnosticsInstrumentation` 在 Android 17 模拟器执行生产诊断 Java 类，验证真实 Android preferences、Provider 调用、文件描述符、中文批次、独立导出快照、关闭清理、重新开启和旧 session 拒收。为避免覆盖已有异证书应用，使用独立包名/authority 的测试夹具；诊断生产逻辑未替换。
- 从 shell 的独立 UID 调用 `state` 被 `Untrusted diagnostics caller` 拒绝；未获 URI 授权的文件读取被平台拒绝。
- 结构检查、native 能力策略检查、有效/错误 APK ABI 样例和正式签名发布检查全部通过。
- 测试 runner 清理顺序调整后，重新构建 `:app:assembleDebugAndroidTest` 成功。生产 APK 内容未因该测试调整发生变化。

完整构建与 Provider 集成输出位于忽略目录 `artifacts/diagnostics-20261022/`。没有删除或替换模拟器中原有的同包名应用。

## 安装与使用

正式签名包：`artifacts/ActionGrid-0.4.9.apk`，436119 字节。

```text
SHA-256 baa0aa8aed966a91b798de4d4d4018560d8bff0a1c564e8ff1eda1976bc1ae3c
```

证书 SHA-256 仍为 `e000d876a95db3c3ea565e8650d19b81d75a674f1926e9889d4cf5a54aeb1f05`。

1. 覆盖安装并重启小米桌面一次，让进程加载新版模块。
2. 在“关于”页开启或保持“记录日志”，返回桌面正常使用。
3. 出问题后直接回“关于”页点“发送日志”，先导出再关闭记录。
4. 如果“原生桌面日志”一直显示等待连接，仍可发送导出文件，用版本/状态摘要继续排查传输未接通的原因。

## 验证边界

尚未在用户的 7748 手机上验证 Rust ContentResolver 到应用 Provider 的实际 Binder/SELinux 通路，也没有完成真机读屏端到端验收。静态 ABI、原生模拟边界和 Android 存储/Provider 回归不能替代这一步；界面以实际最近连接时间反馈通道状态。

这不是系统全量 logcat 或崩溃转储。仅收集本模块诊断；突然崩溃时内存中尚未送达的最后几条可能丢失，响应丢失后的重试可能产生相同 PID/顺序号的重复记录。日志可能包含桌面应用名称与位置，只有用户点击分享后才交给所选接收应用。
