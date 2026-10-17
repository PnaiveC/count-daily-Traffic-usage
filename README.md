# 每日流量

本地、离线、单页的 Android 每日流量明细工具。

当前状态：MVP 已实现，可安装测试。功能：

- 选择今天或历史日期；
- 查看当天移动数据 + Wi-Fi 的设备总流量；
- 查看按应用/UID 汇总的当天用量，按流量降序排列；
- 系统保留多久就查询多久，不建立本地历史数据库。

可安装 APK：[DailyTraffic-0.1.0-debug.apk](dist/DailyTraffic-0.1.0-debug.apk)

技术文档：

- [需求基线](docs/01-requirements.md)
- [实现与交接说明](docs/02-implementation-handoff.md)

构建命令：

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

本机 SDK 已写入 `local.properties`。Release APK 输出在
`app/build/outputs/apk/release/app-release-unsigned.apk`。

本机验证：8 个 JVM 单元测试通过；Debug/Release 构建成功；API 36.1 模拟器完成
未授权、授权后读取、日期切换和包名映射验证。

当前 Debug APK 约 911 KiB，Release APK 约 37 KiB。

旧版长文档已移动到 `docs/archive/`，仅作历史参考，不再作为实现依据。
