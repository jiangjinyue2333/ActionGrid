# ActionGrid 0.4.12 验证记录

版本 `0.4.12` / `28`。

## 更新来源

关于页新增当前更新来源摘要和“选择更新来源”按钮。默认使用 GitHub Release，保留原有 GitCode 清单；来源切换使用系统单选对话框，TalkBack 可以读取当前来源、选中状态和切换结果。忽略版本按来源分别保存。

GitHub 正式工作流从最终签名 APK 的实际包名、版本、文件大小和 SHA-256 生成 `latest.json`，与 APK 一起上传到对应 Release。应用读取 `releases/latest/download/latest.json`，并限制 APK 必须来自 `jiangjinyue2333/ActionGrid` 的 Release 下载路径。

## 验证

发布前执行：

- `gradlew.bat test lint assembleRelease`
- `scripts/verify-project-structure.ps1`
- `scripts/test-native-policy.ps1`
- `scripts/test-apk-abi.ps1 -ApkPath app/build/outputs/apk/release/app-release-unsigned.apk`
- `git diff --check`

推送 `v0.4.12` 后，GitHub Actions 会执行正式签名、固定证书复核并上传 `ActionGrid-0.4.12.apk` 与 `latest.json`。应用内切换到 GitHub 后检查更新，确认能读取该清单并进入下载校验流程。
