# 每日流量 MVP 实现与交接说明

版本：1.2（2026-10-17）  
读者：后续修复 bug、适配设备或扩展功能的开发者/代码生成代理。

## 1. 当前状态

- v0.2 已实现：日期切换、权限流程、设备总量、网络筛选、帮助、按 UID/应用明细、
  共享 UID 的底部“其它”、逐层展开、最近 7 日趋势、刷新和错误状态。
- 旧版长文档位于 `docs/archive/`，与当前 MVP 冲突时一律以
  `docs/01-requirements.md` 和本文为准。
- 可安装测试包：`dist/DailyTraffic-0.2.0-debug.apk`。

本次已完成的本机验证：

- `.\gradlew.bat test`：10 个测试通过。
- `.\gradlew.bat lintDebug assembleDebug assembleRelease`：通过。
- API 36.1 模拟器：未授权、授权读取、唯一 UID 直接显示、共享 UID 汇总到“其它”、
  共享 UID 组与包名展开、七日趋势、网络筛选、帮助弹窗和前一天切换均正常。
- Debug APK：约 961 KiB；Release APK：约 51 KiB。
- 合并 Manifest：包含 `PACKAGE_USAGE_STATS`、`QUERY_ALL_PACKAGES`，不含
  `INTERNET`。
- Debug APK 已用 Android Debug 证书签名，SHA-256：
  `2A26A72E002CA19AE7EAB14FB2717A1F0838A4DBF193C34BC0BFB03571FCE24F`。

尚未完成：真机测试、厂商 ROM 兼容性和 API 23～28 运行验证。

## 2. 已冻结的技术决策

| 项目 | 决策 |
|---|---|
| 包名 | `com.example.dailytraffic` |
| 语言 | Kotlin |
| UI | 单 Activity + 平台控件 + ViewBinding |
| 页面 | 只有每日明细页 |
| 异步 | 单个 `ExecutorService`，结果回主线程 |
| 日期 | `Calendar`，不使用 `java.time`/desugaring |
| 数据库 | 无 |
| 网络 | 无 `INTERNET` |
| 包可见性 | `QUERY_ALL_PACKAGES`，仅用于 UID 到本机应用名称/图标的映射 |
| 共享 UID | 唯一 UID 直接显示；共享 UID 全部进入底部“其它”，可逐层展开 |
| 七日趋势 | 按需查询、缓存；结束日为当前选中日期；只允许一个展开项 |
| 依赖 | 仅 JUnit（测试）；运行时无第三方库 |
| 构建 | AGP 8.13.2、Kotlin 2.3.10、Gradle 8.13 |
| SDK | compile/target 36，min 23，JVM target 17 |

不要为这个小应用引入 AndroidX、Compose、Material、RecyclerView、Coroutines、
Room、Hilt、Flow 或多模块结构。它们会增加依赖、构建时间和体积，但不解决当前问题。

## 3. 本机环境

- Android Studio：`C:\Users\Prologue\AppData\Local\Programs\Android Studio`
- SDK：`C:\Users\Prologue\AppData\Local\Android\Sdk`
- 已安装 Platform 36、Build Tools 36.1.0、Platform Tools 36.0.2。
- JDK 17/21/25 已安装；Gradle 使用 JDK 21 已实际验证。
- 模拟器 AVD：`Medium_Phone_API_36.1`，WHPX 加速可用。
- 当前没有物理设备连接，`adb devices` 为空。
- 命令行 `adb`、`gradle` 不在 PATH；使用绝对路径或直接使用项目 Wrapper。
- 本机网络可访问 Google Maven 和 Maven Central。

项目根目录的 `local.properties` 已指向本机 SDK，且已被 `.gitignore` 忽略。

## 4. 实际文件结构

```text
app/src/main/java/com/example/dailytraffic/
├── MainActivity.kt
├── data/
│   ├── DailyTrafficRepository.kt
│   ├── NetworkStatsDataSource.kt
│   └── PackageResolver.kt
├── domain/
│   ├── TrafficAggregator.kt
│   └── TrafficModels.kt
├── permissions/
│   └── UsageAccessChecker.kt
├── time/
│   └── DayRangeProvider.kt
├── ui/
│   ├── TrafficListAdapter.kt
│   ├── TrafficUiState.kt
│   └── UsageBarView.kt
└── util/
    └── TrafficFormatter.kt

app/src/test/java/com/example/dailytraffic/
├── DayRangeProviderTest.kt
├── TrafficAggregatorTest.kt
└── TrafficFormatterTest.kt
```

实现时保持单页，不新增 Fragment、导航图或第二 Activity。

## 5. 数据模型

已实现的数据模型位于 `domain/TrafficModels.kt`：

```kotlin
data class TrafficBytes(val rx: Long, val tx: Long) {
    val total: Long get() = rx + tx
}

enum class NetworkFilter { ALL, MOBILE, WIFI }

data class TrafficRow(
    val uid: Int,
    val label: String,
    val packageNames: List<String>,
    val mobileBytes: TrafficBytes,
    val wifiBytes: TrafficBytes,
    val icon: Drawable?
) {
    fun bytesFor(filter: NetworkFilter): TrafficBytes
}

data class SharedUidGroup(
    val uid: Int,
    val packageNames: List<String>,
    val mobileBytes: TrafficBytes,
    val wifiBytes: TrafficBytes
)

data class DailyTraffic(
    val dayStartMillis: Long,
    val dayEndMillis: Long,
    val deviceMobile: TrafficBytes,
    val deviceWifi: TrafficBytes,
    val appRows: List<TrafficRow>,
    val sharedGroups: List<SharedUidGroup>
)

data class DailyUsagePoint(val dayStartMillis: Long, val bytes: TrafficBytes?)
data class WeeklyUsage(
    val uid: Int,
    val endDayStartMillis: Long,
    val filter: NetworkFilter,
    val points: List<DailyUsagePoint>
)

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Success<T>(val value: T) : LoadState<T>
    data object PermissionDenied : LoadState<Nothing>
    data class Failure(val kind: ErrorKind) : LoadState<Nothing>
}

enum class ErrorKind {
    SECURITY,
    REMOTE_SERVICE,
    INVALID_RANGE,
    UNKNOWN
}
```

当前实现为控制代码量，直接把已解析的 `Drawable` 放入行模型。图标只在工作线程
解析，适配器只负责显示。

## 6. 日期范围和查询

### 6.1 日期范围

使用设备当前时区：

```kotlin
val calendar = Calendar.getInstance()
calendar.timeInMillis = selectedDayMillis
calendar.set(Calendar.HOUR_OF_DAY, 0)
calendar.set(Calendar.MINUTE, 0)
calendar.set(Calendar.SECOND, 0)
calendar.set(Calendar.MILLISECOND, 0)
val start = calendar.timeInMillis
calendar.add(Calendar.DAY_OF_MONTH, 1)
val nextStart = calendar.timeInMillis
val end = if (isToday) minOf(now, nextStart) else nextStart
```

必须用当地日历加一天，不能用固定 24 小时，避免夏令时和时区变化错误。
禁止未来日期。`start >= end` 时返回 `INVALID_RANGE`。

### 6.2 设备总量

分别查询：

- `ConnectivityManager.TYPE_MOBILE`
- `ConnectivityManager.TYPE_WIFI`
- `subscriberId = null`

两项都成功才展示完整总量。任意一项为 `null`、抛出 `SecurityException`、
`RemoteException` 或其他运行时异常，都进入失败/未授权状态。

### 6.3 按 UID 聚合

分别查询移动和 Wi-Fi 的 `NetworkStats`，遍历 bucket：

- 按 `bucket.uid` 累加 `rxBytes + txBytes`；
- 两个网络类型按 UID 取并集；
- 过滤总字节为 0 的 UID；
- 唯一 UID 进入普通应用列表，按当前网络筛选值降序；
- 共享 UID 不进入普通列表，单独按当前网络筛选值排序；
- 同用量时按 label、uid 稳定排序。

`NetworkStats` 必须关闭。不得捕获后返回伪造的成功 0。

### 6.4 特殊 UID

- 忽略 `NetworkStats.Bucket.UID_ALL`；
- `UID_TETHERING`：`网络共享`；
- `UID_REMOVED`：`已卸载应用`；
- `Process.SYSTEM_UID`：尝试解析名称，否则 `系统 (UID n)`；
- 其他无法映射 UID：`未知应用 (UID n)`；
- 唯一 UID：直接显示应用名称和图标；
- 共享 UID：全部合并到列表底部“其它”，不再显示“多个应用”；
- “其它”展开后按共享 UID 分组，再点组显示包名和七日趋势。

不要把特殊 UID 转成普通应用名，也不要静默丢弃。

### 6.5 最近七日

- 以当前选中日期为结束日，向前生成 7 个本地日区间。
- 每天调用与主列表相同的 `querySummary`，再取目标 UID 的移动/Wi-Fi 数据；
  不使用 `queryDetailsForUid`，因为实测系统 UID 可能返回全 0。
- “全部网络”需要移动和 Wi-Fi 都成功；任一项失败，该天记为不可用并显示“—”。
- `SecurityException` 终止七日加载并回到权限提示。
- 结果按 `(uid, endDayStart, filter)` 缓存；切换日期或筛选时清空。
- 当前只允许一个应用/共享 UID 组展开七日面板。

## 7. 权限

使用 `AppOpsManager`：

- API 29+：`unsafeCheckOpNoThrow`；
- API 23～28：`checkOpNoThrow`；
- 仅 `MODE_ALLOWED` 视为已授权；
- 查询时仍必须捕获 `SecurityException`。

设置跳转：

```kotlin
Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
```

失败时回退：

```kotlin
Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:$packageName")
)
```

`onResume` 中重检权限。若从未授权变为已授权，立即加载当前日期。

## 8. 线程与生命周期

- `NetworkStatsDataSource`、`PackageResolver`、主数据加载和七日加载都在
  `Executors.newSingleThreadExecutor()`。
- UI 更新通过 `Handler(Looper.getMainLooper())` 或 `runOnUiThread`。
- Activity 销毁时 `shutdownNow()`；主数据和七日各有请求序号，旧结果不得覆盖新状态。
- 七日结果按 `(uid, endDayStart, filter)` 缓存；切换日期或网络筛选时清空。
- 不捕获并吞掉 `InterruptedException`；线程退出时停止后续 UI 更新。

## 9. UI 行为

现有 `activity_main.xml` 已包含所需控件，使用 ViewBinding：

- 点击前一天/后一天：更新日期并刷新；后一天不能超过今天。
- 点击日期：`DatePickerDialog`，最大日期为今天。
- 漏斗：打开“全部网络 / 移动数据 / Wi-Fi”单选弹窗，只改变展示口径，不重新请求系统。
- 帮助：打开说明弹窗，解释统计口径、共享 UID、七日趋势和历史保留限制。
- 点击刷新：重新加载当前日期。
- 加载时显示 `ProgressBar`，隐藏列表和错误文本。
- 成功后隐藏状态面板，显示设备总流量和列表。
- 权限不足时显示说明和“去授权”。
- 读取失败时显示“读取失败，请稍后重试”，并提供刷新。
- 列表为空时显示“当天无流量记录”；设备总流量仍可显示。
- 唯一 UID 行点击后展开最近 7 日；共享 UID 的“其它”先展开 UID 组，再展开组内包名
  和七日趋势。
- 同一时间只展开一个应用或共享 UID 组；展开状态变化时保持当前列表滚动位置。

列表行布局为：

- 左侧图标；
- 中间名称和可选副标题；
- 右侧当前筛选口径的总用量与展开箭头；
- 展开区放在行下方，使用简单数字、横条和“—”表示缺失数据。

不要显示图表、百分比、广告位、设置入口或关于入口。

### Edge-to-edge

`targetSdk 36` 在 Android 15+ 默认 edge-to-edge。占位 Activity 已对 API 30+
应用系统栏 Insets；实现时保留该逻辑，避免日期栏被状态栏遮挡。

## 10. 格式化

- 使用 1024 进制，界面显示 `KB/MB/GB`。
- 0 显示 `0 B`。
- 保留一位小数，整数时去掉 `.0`。
- 单元测试覆盖 0、1023、1024、1 MiB、1 GiB 和超大值。

## 11. 测试

### JVM 单元测试

- 今天范围：当天 00:00 到 now；
- 过去日期：当天 00:00 到次日 00:00；
- 周一、月初、跨年、闰年；
- 使用固定 `TimeZone` 的 DST 场景；
- 移动/Wi-Fi 同 UID 合并；
- 仅移动或仅 Wi-Fi 的 UID；
- 0 用量过滤、降序和稳定排序；
- 特殊 UID 与共享 UID 排序；
- 网络筛选后的排序；
- 格式化边界。

查询层使用接口/fake 注入，单元测试不能依赖设备权限。

### 模拟器验证

```powershell
$adb = "C:\Users\Prologue\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$env:ANDROID_HOME = "C:\Users\Prologue\AppData\Local\Android\Sdk"
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Medium_Phone_API_36.1
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell appops set com.example.dailytraffic GET_USAGE_STATS allow
& $adb shell appops set com.example.dailytraffic GET_USAGE_STATS default
```

注意：模拟器的系统流量记录可能与真机不同，只能用于流程和崩溃检查。

## 12. 构建与验收

```powershell
.\gradlew.bat test
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Release 检查：

- `app/build/outputs/apk/release/app-release-unsigned.apk` 能生成；
- 合并 Manifest 无 `INTERNET`；
- 依赖树无广告/分析 SDK；
- 记录实际 APK 大小；
- 将可安装 Debug APK 复制到 `dist/`；
- 不声称真机验证通过，除非确实在设备上运行并记录型号、Android 版本和结果。

## 13. 后续修复与真机验证

- 真机安装：`adb install -r dist/DailyTraffic-0.2.0-debug.apk`。
- 首次打开后在系统设置中授予“使用情况访问”；USB 调试时可临时使用：
  `adb shell appops set com.example.dailytraffic GET_USAGE_STATS allow`。
- 复现 bug 时记录设备品牌、Android 版本、系统 ROM 版本、所选日期、页面状态，
  并提供 `adb logcat -d` 中的崩溃栈。
- 优先检查 `MainActivity` 的权限/日期/筛选/展开状态、`DayRangeProvider` 时区边界、
  `PackageResolver` 的共享 UID 判断和 `DailyTrafficRepository` 的七日查询。
- 修改后必须重新运行 10 个 JVM 测试、`lintDebug`、Debug/Release 构建，并更新
  `dist/` 中的测试 APK。
- 当前 APK 使用 Android Debug 证书签名。后续若继续覆盖安装，必须继续使用同一
  Debug 证书；否则需先卸载旧版本。

## 14. 完成标准

- 单页可切换日期并显示设备总量、网络筛选和按应用明细；
- 唯一 UID 直接显示；共享 UID 只出现在底部“其它”，可逐层展开；
- 应用和共享 UID 可以按需展开最近 7 日；
- 权限、失败、空数据和真实零流量可区分；
- 所有查询在 UI 线程外；
- JVM 测试和 Debug/Release 构建通过；
- 无 `INTERNET`、无广告、无数据库、无后台服务；
- 文档与实际实现一致。
