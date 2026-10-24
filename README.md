# ActionGrid

为小米桌面补充 TalkBack 可执行的图标整理操作。

0.4.13 修复澎湃 4 文件夹托起时 favorite 位置解析短暂失败后无法放置的问题；节点重建后仍复用稳定 favorite ID，并让原生无障碍套件在 workspace 来源变化后继续播报“第 X 页共 Y 项”。

0.4.12 新增应用内更新来源选择：默认从 GitHub Release 获取由 GitHub Actions 构建的正式包，也保留之前的 GitCode 清单。关于页会显示当前来源，点击“选择更新来源”即可切换；GitHub Release 同时附带 `latest.json`，应用会校验包名、版本、大小、SHA-256 和正式签名后才提供安装。

0.4.11 修复澎湃 4 翻页重复播报：自动翻页提示使用“第 X 页共 Y 项”，页码指示器保留系统默认描述。Y 为当前页桌面项目数，底栏和文件夹内部项目不计入。同时修复桌面文件夹托起后无法确认来源的问题；托起、放置成功保留语音反馈，文案采用系统桌面原文。详见 [0.4.11 验证记录](docs/RELEASE_0.4.11.md)。

澎湃 4 网格修复以工作区 7748/8565 原生库为依据，补齐空位触摸坐标、移动参数与放置结果确认。已有录制模拟器的原生回归已通过，真机读屏验收仍待完成，详见 [修复与测试记录](docs/HYPEROS4_GRID_REPAIR.md)。

作者：江浸月

## 使用前提

- Android 14–17，LSPosed Modern API 102。
- 作用域只勾选 `com.miui.home`，不要勾选 `android`。
- 启用模块并重启小米桌面后，在管理页确认作用域、桌面进程和版本状态。
- TalkBack 操作依赖小米桌面原有无障碍节点；native 会按 ELF 结构指纹校验 ABI，未知结构只保留安全动作，不会冒险套用偏移。

## 桌面版本

澎湃 4 原生路径使用一个 APK，在运行时按桌面 ELF 结构指纹选择 profile；Build ID 只用于诊断日志：

| 桌面版本 | 原生路径 |
| --- | --- |
| `8.01.02.8565` (`801028565`) | 当前 FRB profile，空位单项移动走 op 833 |
| `8.01.02.7748` (`801027748`) | 批量移动走 op 855，已通过离线接口校验，尚待真机移动验收 |
| `8.01.02.7722` (`801027722`) | 旧 FRB profile，批量移动走 op 854 |
| `8.01.02.7653` (`801027653`) | 旧 FRB profile，批量移动走 op 854 |

7.x 继续使用 Java 桥接路径。原生后端按实际 `libapp_launcher.so` 接口选择能力；版本号、Dart 或 Build ID 更新不再单独关闭相同的 Rust 接口。未知 FRB 接口会关闭移动/重排，基础动作也须先通过完整系统 ABI 校验；真实接口变化仍需适配，不会猜新的操作号。管理页无法读取桌面进程状态不代表桌面未运行。

澎湃 3 提供默认关闭的“页码指示器倒序翻页”开关，来自 PR #2；澎湃 4 不启用此功能。7.x 的文件夹拖出修复保留桌面原生取消恢复流程；已有重复图标不会自动删除。澎湃 4 的文件夹成员事务尚未验证，暂不支持通过模块移动文件夹内项目或创建文件夹。详见 `docs/PAGE_ORDER.md`、`docs/FOLDER_REPAIR.md` 和 `docs/LAUNCHER_7748_ABI.md`。

TalkBack 只能操作当前可见的桌面节点；跨页、文件夹和占用位置由桌面状态确认，确认失败时会保留原位置并播报失败。快捷入口中的微信扫码、付款和我的二维码可被第三方显式调用；卸载入口只接受小米桌面 native 调用。

## 手机端排查日志

覆盖安装后重启小米桌面，在 ActionGrid 的“关于”页开启“记录日志”，再返回桌面正常使用。出现操作丢失或放置失败后，回到“关于”页点“发送日志”即可，不需要连接电脑。请先导出，关闭或重新开启记录都会清除上一轮日志。

“原生桌面日志”会显示最近连接时间；如果一直显示“等待小米桌面连接”，也请导出日志，其中的版本和状态摘要可以区分通道未接通与没有产生操作记录。当前原生传输 ABI 已核对 7748/8565；手机上的实际连接仍需验收。7.x 原有 Java 日志通道保留。

日志在本机循环保留最近约 2 MiB，包含应用名称、操作节点、位置、错误及模块/系统/桌面版本。分享文件是独立快照，新的记录不会改变正在发送的文件。桌面重启前已收到的日志会保留；突然崩溃时尚未传出的少量记录可能丢失。

## GitHub Actions 签名构建

普通 push/PR 只运行结构检查、测试、Lint 和 unsigned build，不读取签名 Secrets。只有推送形如 `v<version>` 的 tag 才会读取签名 Secrets，生成并验证对应的 `ActionGrid-<version>.apk`，然后自动创建 GitHub Release。

发布新版本时只需：

1. 在 `app/build.gradle.kts` 中修改 `versionName`，并把 `versionCode` 递增为大于已发布版本的正整数。
2. 运行项目结构检查、测试、Lint 和构建。
3. 创建与 `versionName` 完全一致的 tag，例如 `versionName = "1.2.0"` 时推送 `v1.2.0`。

工作流会从 APK 的实际元数据读取版本，并拒绝 tag 与 `versionName` 不一致的发布。正式签名必须永久复用当前发布 keystore；更换证书会导致旧安装无法覆盖升级。

仓库管理员需在 Settings → Secrets and variables → Actions 配置以下 Repository secrets：

| 名称 | 内容 |
| --- | --- |
| `ACTIONGRID_RELEASE_KEYSTORE_BASE64` | 当前正式版签名文件的 Base64 内容 |
| `ACTIONGRID_RELEASE_STORE_PASSWORD` | 签名文件密码 |
| `ACTIONGRID_RELEASE_KEY_ALIAS` | 当前发布密钥别名 |
| `ACTIONGRID_RELEASE_KEY_PASSWORD` | 发布密钥密码 |

`scripts/verify-release.ps1` 会核对固定证书摘要、包名、版本、Xposed 元数据、native ABI 和 DEX 入口；错误证书签出的 APK 不会发布。密钥只在 Runner 临时目录还原，构建结束后清理，不提交源码仓库。

应用内在线更新仍要求 `versionCode` 大于已安装版本。工作流会把签名 APK 和由实际 APK 元数据生成的 `latest.json` 一起上传到 GitHub Release；GitCode 清单继续由原有发布流程维护。

应用内更新来源默认是 GitHub。关于页会显示当前来源；点击“选择更新来源”可以在 GitHub 和 GitCode 之间切换。GitHub 来源读取 `https://github.com/jiangjinyue2333/ActionGrid/releases/latest/download/latest.json`，GitCode 来源保留原有清单地址。忽略更新的版本号按来源分别保存，切换来源不会误屏蔽另一来源的版本。
