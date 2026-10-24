# 澎湃 3 页码指示器倒序

本功能移植自 fanqiang1995 提交的 [PR #2](https://github.com/jiangjinyue2333/ActionGrid/pull/2)，保留默认关闭、本地保存和 LSP 远程配置同步。按本次集成要求，仅在包含 DEX 的 7.x 小米桌面安装，澎湃 4 不安装此 Hook。

## 文件与边界

- `app/.../PageOrderSettings.java`：管理应用的本地开关和远程配置写入；服务连接时补同步。
- `app/.../MainActivity.java`、`activity_main.xml`、`strings.xml`：原生 Switch、适用范围说明和保存状态。未知版本、无 DEX 或 8.x 桌面禁用开关且显示未启用原因，不显示已生效。
- `hook/.../PageOrderProtocol.java`：配置键与两侧共用的 7.x/HAS_CODE 门控。
- `hook/.../PageOrderHook.java`：安装系统无障碍单节点、列表及可选预取响应与动作入口，读取偏好，仅识别小米桌面可滚动页码节点。所需反射签名缺失时单独退出，不阻断图标整理；预取签名变化只跳过该可选入口。
- `hook/.../PageIndicatorOrder.java`：无 Android 运行时依赖的标签匹配、节点缓存与前后滚动动作交换。缓存按 controller/window 隔离，20 秒过期；节点失去页码语义或窗口变化即失效。无障碍焦点、点击及其他动作保持原样。
- `hook/.../HookMethodLookup.java`：可选 Hook 方法沿父类查找，包括非 public 声明；无法解析则返回不可用。
- `hook/.../ActionGridModule.java`：先隔离原生桌面路径，再按 7.x 门控安装页码 Hook。旧桌面 hover Hook 使用继承查找、方法去重和实际宿主对象过滤，单个可选 Hook 失败不终止图标 Hook。

## 验证

`PageOrderProtocolTest`、`PageIndicatorOrderTest` 和 `HookMethodLookupTest` 覆盖 7.x/HAS_CODE 范围、中文页码标签、正反方向、关闭与非滚动动作放行、跨窗口同 ID 隔离、节点重用、缓存过期，以及父类声明方法查找。已使用 JDK 21、Android 37 SDK 与 libxposed 102 编译新增代码，9 个独立 JUnit 回归通过；完整 Gradle 检查由本次主任务统一运行。

真机还需在 7.50 桌面使用 TalkBack 验证：切换开关后页码上下滑方向互换；触摸探索和焦点移动不翻页；CellLayout 未声明 hover 方法时核心操作与空位探索仍可用。澎湃 4 应显示该选项不可用且日志中无页码 Hook 安装记录。
