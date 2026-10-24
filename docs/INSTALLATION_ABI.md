# 安装 ABI 约束与验证

ActionGrid 的原生后端只构建 `arm64-v8a`。`app/build.gradle.kts` 的 `abiFilters` 与 `app/src/main/cpp/Application.mk` 的 `APP_ABI` 必须一致，APK 中的库必须是实际的 ELF64 ARM64 共享库。

## 安装失败原因

反馈错误：

```text
INSTALL_FAILED_INTERNAL_ERROR: Error deriving application ABI:
The multiArch app's native libs don't support all the natively supported ABIs of the device.
```

修复前的 `app/src/main/AndroidManifest.xml` 同时声明了 `android:multiArch="true"`。这会要求安装器按多架构应用检查原生库，而实际包只有 arm64 库，在需要另一原生 ABI 的设备上与错误描述直接吻合。现已去掉该声明，使用 Android 默认的单 ABI 选择，让支持 arm64 的设备选择 APK 内的 arm64 库。

这是一处已确认的模块打包缺陷，已通过下述 Android 17 模拟器实验复现安装器的相同错误。尚未取得反馈用户失败的 APK 和设备 ABI 列表，因此不声称已在该用户设备复现。本地旧 `ActionGrid-0.3.apk` 既没有 native 库，也没有 `multiArch` 声明，不能代表此次失败包。

7.x 桌面的 Java Hook 分支不调用本模块的 native 库；去掉 `multiArch` 不改变该分支。当前 APK 仍以支持 arm64 的设备为安装前提，不为纯 32 位或其他架构添加空 `.so`，也不宣称已验证 32 位桌面进程加载。

## 安装器机制验证

2026-10-19，复用本机 Android 17（SDK 37）AVD，以 `-read-only -no-snapshot-save -no-window -port 5556` 启动独立实例。设备报告 `abilist=x86_64,arm64-v8a`，没有 32 位 ABI；`libndk_translation.so` 将 arm64 映射至 x86_64。这能验证原生 ABI 和翻译 ABI 不一致时的多架构检查，不能解释为只有 32/64 位混合设备才会失败。

独立测试包为 `io.github.jiangjinyue2333.actiongrid.abifixture`，`minSdkVersion=34`，`targetSdkVersion=37`，只放入 SDK 现有的真实 ARM64 ELF 库，不包含 ActionGrid 业务代码。

| APK 声明（库内容相同） | 安装器结果 |
| --- | --- |
| `android:multiArch="true"` | `INSTALL_FAILED_INTERNAL_ERROR: Error deriving application ABI: The multiArch app's native libs don't support all the natively supported ABIs of the device.` |
| 不声明 `multiArch` | `Success`；`primaryCpuAbi=arm64-v8a`，`secondaryCpuAbi=null` |

测试结束已卸载测试包并关闭该只读实例；未更改用户手机和原有在线模拟器。该实验直接验证了安装失败原因和声明修复，完整模块构建仍由下面的实际 APK 检查覆盖。

## 自动检查入口

- `scripts/verify-project-structure.ps1`：源 manifest 不得声明 `multiArch=true` 或强制 32 位 ABI。
- `scripts/verify-apk-abi.ps1`：读取最终 APK 中的二进制 manifest，检查单 ABI 安装声明、必须存在的 native 模块、所有 native 库的目录和 ELF 架构；unsigned 和 signed APK 均可验证。
- `scripts/test-apk-abi.ps1`：先验证实际构建 APK，再从该包取出真实 native 库，编译最小 APK 样例，验证默认/显式单 ABI 能通过，多架构错误声明、缺库、空库、错误 ELF 架构和额外 ABI 均被拒绝。样例只写入 `app/build/abi-regression`，不安装设备。
- 普通 push/PR 在 unsigned build 后运行回归检查；`scripts/verify-release.ps1` 对签名包复用同一 ABI 检查。

```powershell
./scripts/verify-project-structure.ps1
./scripts/test-apk-abi.ps1 -ApkPath app/build/outputs/apk/release/app-release-unsigned.apk
./scripts/verify-release.ps1 -ApkPath artifacts/ActionGrid-<version>.apk
```

上述脚本使用已有 Android SDK 37 的 `aapt`，不会安装工具。回归脚本读取 SDK 的 `android-37` 或 `android-37.0` 平台目录，兼容本地与 CI 的命名。

APK 检查覆盖安装声明与库架构；安装器和 LSPosed 的设备行为仍需在对应真机验收，不能从离线 APK 检查推断所有设备都已验证。
