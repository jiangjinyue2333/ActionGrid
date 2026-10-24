# ActionGrid 0.4.7 发布验证

2026-10-21。版本 `0.4.7` / `23`，在 0.4.6 的澎湃 4 拖动、空位提示、托起解说和启动公告修复基础上递增小版本并生成正式签名包。

## 构建与校验

- 正式构建入口：仓库外的本机签名包装脚本调用 `scripts/build-release.ps1`，签名参数只从受保护的本机属性文件临时注入。
- `clean test lint assembleRelease` 通过。
- `scripts/verify-project-structure.ps1` 通过。
- `scripts/test-native-policy.ps1` 通过。
- `scripts/test-apk-abi.ps1 -ApkPath app/build/outputs/apk/release/app-release-unsigned.apk` 通过。
- `scripts/verify-release.ps1` 通过：包名、版本、Xposed 元数据、DEX 入口、arm64 ABI 和正式证书均正确。

正式产物为 `artifacts/ActionGrid-0.4.7.apk`，大小 `419735` 字节，SHA-256：

```text
4de8cfda33df515a722722f3de9efbb4e4493a15344c5740096d018993aba260
```

APK 元数据为 `io.github.jiangjinyue2333.actiongrid`、`versionName=0.4.7`、`versionCode=23`、`compileSdk=37`、`targetSdk=37`，签名证书 SHA-256 为：

```text
e000d876a95db3c3ea565e8650d19b81d75a674f1926e9889d4cf5a54aeb1f05
```

本次只生成本地正式包，没有创建远程 Release 或修改在线更新清单。真实设备上的桌面拖动验收仍需在目标小米设备上完成。
