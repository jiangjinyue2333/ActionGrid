# 澎湃 4 移动网格与放置确认修复

2026-10-19。本次修改针对澎湃 4 的无 DEX 原生桌面；澎湃 3 仅用于参考“移至第 N 行第 M 列”的提示语。实现位于 `app/src/main/cpp/native_init.cpp`。

以下保留最初修复的验证记录。0.4.11 已将托起、放置成功文案改为系统桌面原文，并修正翻页重复播报与桌面文件夹来源解析，当前行为见 [0.4.11 验证记录](RELEASE_0.4.11.md)。

## 样本身份

直接读取工作区 APK 内的 `lib/arm64-v8a/libapp_launcher.so`，其 SHA-256 与分析文件逐一匹配。两个 APK 均没有 DEX。

| 工作区 APK | 分析文件 | 原生库 SHA-256 |
| --- | --- | --- |
| `系统桌面-com.miui.home-RELEASE-8.01.02.7748-260904-10091435-R.apk` | `tmp/abi-7748/libapp_launcher.so` | `89302282d70e3b40126ccce05fef94560529c0543ff6e05107724dade0d104a7` |
| `系统桌面_RELEASE-8.01.02.8565-260918-10121010-R.apk` | `tmp/abi-8565/libapp_launcher.so` | `771498f45bd11d93be10fe14a36d8d796225c82be3ae4c4da00724291ba9cfd1` |

## 问题与改动

| 问题 | 修复 |
| --- | --- |
| 旧算法按已有图标的坐标排序推算网格，空行、空列被压缩；默认七行还可能产生屏幕外位置 | 7748/8565 使用已核对的桌面行列接口，以可见页面边界建立完整网格，再用已知位置校准；小部件跨度按占用处理，写入前再次拒绝越界位置 |
| 手摸空位没有坐标提示；抬手后丢失双击目标 | 使用实际的双参数 `void(scene*, event*)` 触摸入口；接管空格的 hover，移回已有图标时交还桌面；抬手保留无障碍焦点 |
| 8565 单项移动请求缺少目标参数 | 在 75 字节 ItemInfo 后补齐 container、screen、cellX、cellY 共 20 字节；不再用数据库兜底掩盖原生请求失败 |
| 7748 插到已有图标前后时只发送托起项目，遗漏顺移项目 | 批量请求包含所有最终位置，让被顺移图标也参与同一次原生更新 |
| 页码项目数变化被当成放置成功，重载返回也不保证界面刷新 | 先核对数据库位置，再等待目标页对应名称和物理格子的实际图标节点；重载延迟时有限重试，刷新节点并尝试将焦点交给图标 |
| 跨页复用节点编号导致目标图标被当作托起来源 | 来源判断同时检查托起页面 |

空位提示示例：`移至第 1 行第 2 列`。0.4.11 起，确认数据库位置和图标出现后使用系统桌面原文：`已放置在第3页，第1行，第2列`。

只确认数据库保存而未找到目标图标时，提示 `位置已保存，但未确认桌面图标刷新，请重新进入桌面检查`；连数据库位置也未确认时，提示 `未能确认移动，请检查图标位置后重试`。不再把页码计数变化作为成功证据。

所有内部地址仍受已有 ELF 指纹和函数序言校验约束。未扩大最近任务、小窗、分屏的兼容范围。7653/7722 本次没有可用原生样本，行列接口不套用其他版本地址，沿用可观察单元格边界推算。

## 已执行验证

复用了磁盘上已有的 `D:/Android/Avd/one_recorder_api37.avd`，序列号 `emulator-5556`，Android 17 / API 37 / x86_64。没有创建新 AVD。另一台 `emulator-5554` 是 Android 9 的雷电实例，只做了只读识别。

`scripts/test-native-grid.ps1` 必须显式指定设备，不会创建或启动模拟器。它用已安装 NDK 编译实际生产 `native_init.cpp`，只在系统 ABI 边界提供测试节点、事件和数据库行，然后通过 ADB 在指定模拟器执行。

脚本生成的 `native-grid-test` 是 Android ELF 测试二进制（按设备 ABI 编译），不是 Windows 程序。不要在 Windows 资源管理器中直接打开它；必须通过下面的脚本推送到在线 Android 设备运行。

```powershell
./scripts/test-native-grid.ps1 -Serial emulator-5556
```

11 项原生回归全部通过：

1. 稀疏布局保留空行、空列的真实位置。
2. 触摸第一行第二列的空位可取得坐标提示。
3. 抬手保留双击目标，重新摸图标清除虚拟焦点。
4. 实际六行覆盖旧七行数据，不暴露第七行。
5. 小部件跨度、格子接缝及页面边界正确。
6. 真实触摸函数签名、事件克隆释放、hover 交接顺序正确。
7. 第三页 11 项、第四页 10 项不能单独证明美团图标可浏览；必须出现正确页面和格子的图标节点。
8. 实际单项 dispatcher 请求包含完整 95 字节，拒绝过期越界位置。
9. 实际批量 dispatcher 请求包含托起项和所有被顺移项及其正确坐标。
10. 跨页相同节点编号不再误判来源。
11. 取消清理虚拟网格及焦点。

修复前已捕获空行列压缩、触摸空位找不到节点、抬手丢失焦点、单项请求缺少目标参数的失败；新增的批量插入用例也先失败再通过，失败记录保存在 `artifacts/hyperos4-grid/native-batch-before-fix.log`。

其他已执行检查：

| 检查 | 结果 |
| --- | --- |
| 项目结构检查 | 通过 |
| Gradle Java 回归 | 102 项，0 失败、0 错误、0 跳过 |
| Lint、Debug 与 Release 构建 | 通过；Release 为未签名产物 |
| native 能力策略与 APK ABI 正反例 | 通过 |
| 7748/8565 离线 ELF 契约 | 通过，覆盖单项尾参数、批量解码、网格 getter、真实触摸调用链和克隆/释放接口 |

构建及验证命令：

```powershell
./scripts/verify-project-structure.ps1
./gradlew.bat clean test lint assembleDebug assembleRelease --console=plain
./scripts/test-native-policy.ps1
./scripts/test-apk-abi.ps1 -ApkPath app/build/outputs/apk/release/app-release-unsigned.apk
$nm = 'D:/Android/Sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe'
python scripts/verify-launcher-contract.py tmp/abi-7748/libapp_launcher.so --profile 7748 --llvm-nm $nm --json
python scripts/verify-launcher-contract.py tmp/abi-8565/libapp_launcher.so --profile 8565 --llvm-nm $nm --json
```

初次执行 clean 后完成了全套构建；最终 C++ 变更后又执行了原生回归与增量构建。源码契约测试读取 C++ 文件，为避免 Gradle 缓存掩盖变化，另强制重跑 `:app:testDebugUnitTest --rerun-tasks`。

本机日志位于忽略目录 `artifacts/hyperos4-grid/`：`build.log`、`native-tests.log`、`contract-7748.json`、`contract-8565.json`。没有提交样本 APK、原生库或本机构建产物。

## 验证边界

该 AVD 是标准 Android 镜像，没有澎湃 4 桌面及其专用系统库。通过的是实际生产 C++ 在 Android 上的回归，以及真实 8.X 文件的静态接口核对；没有在模拟器里运行小米原生桌面，也没有完成真实读屏端到端测试。

设备端仍需核验：把第四页美团外卖移到第三页空位，确认触摸和顺序浏览都能找到图标，播报正确页码与坐标，重启桌面后仍在原位；另核验摸空位后抬手双击、返回已有图标、翻页与取消。

当前可见节点确认覆盖同页目标。多选溢出到不同页面或底栏目标不会仅凭数据库更新播报全部成功，仍可能提示未确认刷新，需另做设备验收。本次没有连接用户的澎湃 4 手机，不能确认截图中此前移走的美团图标已恢复，也没有自动修改手机上的旧越界记录。
