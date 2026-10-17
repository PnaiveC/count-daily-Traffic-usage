# 轻量级 Android 流量审计 App：可执行技术实施方案

**版本**：实施方案 v1.0（基于需求文档 v0.3）\
**目标**：可直接交给代码生成工具或 Android 开发者实现 MVP\
**平台**：Android 6.0+（minSdk 23）\
**应用定位**：本地历史流量统计/审计；不抓包、不承诺运营商账单级精度\
**隐私边界**：不申请 `INTERNET`，不接入广告、分析、崩溃上报或云服务 SDK

> 实施原则：先做统计 API 的最小真机验证，再完成页面。系统 API
> 查询失败必须与真实零流量区分。统计结果以 Android
> 系统提供的数据和本应用明确口径为准。

------------------------------------------------------------------------

## 0. 给代码生成工具的总指令

请按本文创建一个**单模块 Android 原生 Kotlin
项目**。不要引入网络权限、后台常驻服务、数据库、DI 框架、广告/分析
SDK、多模块架构或不在本文列出的依赖。使用 XML + ViewBinding，单
Activity + ViewModel + Repository，Coroutines + StateFlow。

实现顺序： 1. 工程与 Manifest； 2. 权限检查及系统设置跳转； 3.
`NetworkStatsManager` 数据源和可测试的聚合逻辑； 4.
状态模型、Repository、ViewModel； 5.
首页、应用详情、权限引导、关于页面； 6. 单元测试、仪器测试、Release
Manifest/体积检查。

不要将查询异常、权限拒绝或无法读取的数据伪装成
`0 bytes`。所有系统查询和包信息读取均在主线程之外执行。

------------------------------------------------------------------------

## 1. 工程与 Gradle 配置

### 1.1 建议基线

使用 Android Studio 当前稳定版创建项目，并采用该 IDE 推荐且相互兼容的
Android Gradle Plugin、Gradle、Kotlin
版本。版本号由创建工程时的稳定模板确定，避免在实施文档中锁死可能过期的版本组合。

配置要求：

-   `minSdk = 23`
-   `targetSdk` 使用开发时当前稳定 Android SDK；构建工具按 AGP 默认
-   `compileSdk` 使用当前稳定 SDK
-   Java/Kotlin JVM target 17（若所用 AGP/模板要求更高，遵从模板并统一）
-   开启 ViewBinding
-   开启 Java API desugaring，以便 API 23 使用 `java.time`
-   Release 开启 R8；是否启用资源压缩由实测决定
-   不配置 product flavors，MVP 仅一个 app variant

### 1.2 `settings.gradle.kts`

``` kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "TrafficAudit"
include(":app")
```

### 1.3 根 `build.gradle.kts`

插件版本使用 Android Studio 当前稳定模板所生成的版本；示意：

``` kotlin
plugins {
    id("com.android.application") version "<由稳定模板提供>" apply false
    id("org.jetbrains.kotlin.android") version "<由稳定模板提供>" apply false
}
```

### 1.4 `app/build.gradle.kts`

``` kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.trafficaudit"
    compileSdk = <当前稳定 compileSdk>

    defaultConfig {
        applicationId = "com.example.trafficaudit"
        minSdk = 23
        targetSdk = <当前稳定 targetSdk>
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 按 APK 实测决定是否启用：
            // isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    // 版本号应采用 Android Studio 当前稳定模板 / 官方稳定版本。
    implementation("androidx.core:core-ktx:<stable>")
    implementation("androidx.appcompat:appcompat:<stable>")
    implementation("com.google.android.material:material:<stable>")
    implementation("androidx.activity:activity-ktx:<stable>")
    implementation("androidx.fragment:fragment-ktx:<stable>")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:<stable>")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:<stable>")
    implementation("androidx.recyclerview:recyclerview:<stable>")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<stable>")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:<stable>")

    testImplementation("junit:junit:<stable>")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<stable>")
    testImplementation("app.cash.turbine:turbine:<stable>")

    androidTestImplementation("androidx.test:runner:<stable>")
    androidTestImplementation("androidx.test.ext:junit:<stable>")
    androidTestImplementation("androidx.test.espresso:espresso-core:<stable>")
}
```

**依赖管理要求**：生成代码时将 `<stable>`
替换为同一套当前稳定且兼容的版本，或使用 Version Catalog
(`gradle/libs.versions.toml`) 统一管理。不得原样保留占位符提交。若使用
Material Components，主题应继承
`Theme.Material3`（所选版本支持时）或兼容的 Material Components
主题；不必为本 MVP 引入 Compose。

------------------------------------------------------------------------

## 2. Manifest 与权限

### 2.1 `app/src/main/AndroidManifest.xml`

``` xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- 特殊访问权限：用户必须在系统设置中手动授予 -->
    <uses-permission
        android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />

    <!-- 不声明 INTERNET、READ_PHONE_STATE、QUERY_ALL_PACKAGES、
         ACCESS_NETWORK_STATE、后台服务或通知权限。 -->

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@style/Theme.TrafficAudit"
        android:supportsRtl="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

注意事项：

-   `PACKAGE_USAGE_STATS`
    是特殊访问能力，不是普通运行时弹窗权限；在设置中由用户授权。
-   不为了枚举所有应用而默认申请 `QUERY_ALL_PACKAGES`。先按 UID
    获取可见包；若包可见性导致某些包无法映射，优先显示
    UID/"系统或未知应用"，并在确有产品必要且符合 Play/平台规则时再评估
    `<queries>` 声明。
-   构建后检查 **merged manifest**，确认没有传递依赖引入
    `INTERNET`。若意外引入，先移除引入它的依赖；只有在确认不会破坏库功能后才用
    manifest merger 的 remove 规则。
-   `allowBackup=false`
    与"数据只留本机"的目标一致；如未来增加本地历史，应明确备份政策。

### 2.2 使用情况访问权限检查

建议实现：

``` kotlin
interface UsageAccessChecker {
    fun hasUsageAccess(): Boolean
}
```

Android 版本分支：

-   API 29+：调用 `AppOpsManager.unsafeCheckOpNoThrow(...)`
-   API 23--28：调用
    `AppOpsManager.checkOpNoThrow(...)`（deprecated，局部 suppress）
-   只有返回 `AppOpsManager.MODE_ALLOWED` 才认为 AppOps 已允许
-   实际 NetworkStats 查询仍需捕获 `SecurityException`，AppOps
    检查不能替代查询时的异常兜底

示例：

``` kotlin
class AndroidUsageAccessChecker(
    private val context: Context
) : UsageAccessChecker {
    override fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val op = AppOpsManager.OPSTR_GET_USAGE_STATS
        val uid = Process.myUid()
        val pkg = context.packageName

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(op, uid, pkg)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(op, uid, pkg)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
```

系统设置跳转：

``` kotlin
fun openUsageAccessSettings(activity: Activity) {
    val primary = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    try {
        activity.startActivity(primary)
    } catch (_: ActivityNotFoundException) {
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}")
        )
        try {
            activity.startActivity(fallback)
        } catch (_: ActivityNotFoundException) {
            // UI 显示厂商设置路径说明；不崩溃
        }
    }
}
```

在 `MainActivity.onResume()` 中触发 ViewModel
的权限重检与刷新；不要只在首次启动检查。

------------------------------------------------------------------------

## 3. 目录结构

``` text
TrafficAudit/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
│   └── libs.versions.toml                 # 可选，推荐统一版本
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/example/trafficaudit/
        │   │   ├── TrafficAuditApp.kt     # 可省略；不需自定义 Application 时不创建
        │   │   ├── domain/
        │   │   │   ├── model/
        │   │   │   │   ├── TrafficPeriod.kt
        │   │   │   │   ├── TrafficTotals.kt
        │   │   │   │   ├── AppTraffic.kt
        │   │   │   │   └── QueryState.kt
        │   │   │   └── time/TimeRangeProvider.kt
        │   │   ├── data/
        │   │   │   ├── NetworkStatsDataSource.kt
        │   │   │   ├── PackageDataSource.kt
        │   │   │   ├── NetworkStatsRepository.kt
        │   │   │   └── AndroidUsageAccessChecker.kt
        │   │   ├── ui/
        │   │   │   ├── MainActivity.kt
        │   │   │   ├── home/HomeViewModel.kt
        │   │   │   ├── home/HomeFragment.kt
        │   │   │   ├── detail/AppDetailFragment.kt
        │   │   │   ├── permission/PermissionFragment.kt
        │   │   │   ├── about/AboutFragment.kt
        │   │   │   └── adapter/AppTrafficAdapter.kt
        │   │   └── util/TrafficFormatter.kt
        │   └── res/
        │       ├── layout/
        │       ├── values/
        │       ├── drawable/
        │       └── mipmap-*/
        ├── test/java/com/example/trafficaudit/
        │   ├── TimeRangeProviderTest.kt
        │   ├── TrafficAggregatorTest.kt
        │   └── NetworkStatsRepositoryTest.kt
        └── androidTest/java/com/example/trafficaudit/
            ├── UsageAccessCheckerTest.kt
            └── NetworkStatsDeviceTest.kt
```

可以使用 Activity + 多个 Fragment，也可以用单 Activity 直接切换
View；推荐 Fragment + Navigation
仅在模板已包含且不显著增加依赖时采用。不要为了四个页面引入复杂导航架构。

------------------------------------------------------------------------

## 4. 核心领域模型与接口

### 4.1 领域模型

``` kotlin
enum class TrafficPeriod {
    TODAY, WEEK, MONTH
}

enum class NetworkType {
    MOBILE, WIFI
}

data class TimeRange(
    val startMillis: Long,
    val endMillis: Long
)

data class TrafficBytes(
    val rxBytes: Long,
    val txBytes: Long
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class TrafficTotals(
    val mobile: TrafficBytes,
    val wifi: TrafficBytes
) {
    val totalBytes: Long get() = mobile.totalBytes + wifi.totalBytes
}

data class AppTraffic(
    val uid: Int,
    val label: String,
    val packageNames: List<String>,
    val icon: Drawable?, // 仅 UI 层使用；如需纯 domain，应改为 data 层 UiModel
    val mobileBytes: TrafficBytes,
    val wifiBytes: TrafficBytes
) {
    val totalBytes: Long
        get() = mobileBytes.totalBytes + wifiBytes.totalBytes
}
```

架构洁净性更优的做法是让 domain model 不依赖 `Drawable`：`AppTraffic`
仅包含 UID、名称、包名与流量；UI adapter 通过 `PackageDataSource`
获取/缓存图标，或单独构造
`AppTrafficUiModel`。代码生成应优先采用此做法。

### 4.2 查询状态

``` kotlin
sealed interface DataState<out T> {
    data object Loading : DataState<Nothing>
    data class Success<T>(val value: T) : DataState<T>
    data object NoData : DataState<Nothing>
    data object PermissionDenied : DataState<Nothing>
    data class Error(
        val kind: ErrorKind,
        val userMessage: String
    ) : DataState<Nothing>
}

enum class ErrorKind {
    SECURITY,
    REMOTE_SERVICE,
    UNSUPPORTED_OR_INVALID,
    UNKNOWN
}
```

注意：底层 API 的"空 bucket/0
bytes"不一定足以证明"系统没有历史数据"。DataSource
应尽可能保留原始查询是否成功；Repository 再根据 API 返回及产品语义决定
`Success(0)` 或 `NoData`。不要过度承诺能识别系统数据保留不足。

### 4.3 接口

``` kotlin
interface TimeRangeProvider {
    fun rangeFor(period: TrafficPeriod, nowMillis: Long = System.currentTimeMillis()): TimeRange
}

interface UsageAccessChecker {
    fun hasUsageAccess(): Boolean
}

interface NetworkStatsDataSource {
    suspend fun queryDevice(type: NetworkType, range: TimeRange): Result<TrafficBytes>
    suspend fun queryUid(type: NetworkType, range: TimeRange): Result<Map<Int, TrafficBytes>>
}

interface PackageDataSource {
    suspend fun resolveUid(uid: Int): ResolvedUid
}

data class ResolvedUid(
    val label: String,
    val packageNames: List<String>
)

interface TrafficRepository {
    suspend fun queryTotals(range: TimeRange): DataState<TrafficTotals>
    suspend fun queryApps(range: TimeRange): DataState<List<AppTraffic>>
    suspend fun queryAppDetail(uid: Int, range: TimeRange): DataState<AppTraffic>
}
```

Kotlin `Result` 不能表达所有 UI 语义，因此 Repository 负责将异常映射为
`DataState`。`NetworkStatsDataSource` 应保持较低层，不把 UI
文案放入数据源。

------------------------------------------------------------------------

## 5. 时间范围实现

使用 `java.time`，配合 Gradle
desugaring。时间范围按设备当前时区计算，结束时间是调用时刻。

``` kotlin
class DefaultTimeRangeProvider(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : TimeRangeProvider {
    override fun rangeFor(
        period: TrafficPeriod,
        nowMillis: Long
    ): TimeRange {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val start = when (period) {
            TrafficPeriod.TODAY -> now.toLocalDate().atStartOfDay(zoneId)
            TrafficPeriod.WEEK -> now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zoneId)
            TrafficPeriod.MONTH -> now.toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(zoneId)
        }
        return TimeRange(
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = now.toInstant().toEpochMilli()
        )
    }
}
```

------------------------------------------------------------------------

## 6. NetworkStatsManager 查询实现

### 6.1 实现注意事项

-   使用 `ConnectivityManager.TYPE_MOBILE` 与 `TYPE_WIFI`（按 SDK
    标注处理 deprecated；这些类型是 NetworkStatsManager
    查询所需的历史网络类型参数）。
-   MVP 的 subscriber ID 传 `null`，表示不尝试 SIM
    维度；实际设备覆盖需测试。
-   所有 API 调用放 `Dispatchers.IO`。
-   `querySummaryForDevice()` 用于设备总量；`querySummary()` 迭代
    bucket，按 UID 汇总 RX/TX。
-   捕获 `SecurityException`、`RemoteException`
    和其他异常；不要吞掉后返回伪造的成功 0。
-   `NetworkStats` 是可关闭资源，使用 `use`/`close()` 确保关闭（若 SDK
    类型不支持 Kotlin `use`，显式 `try/finally`）。
-   不应将查询耗时假定为低于 500ms；UI 应显示加载态。

### 6.2 DataSource 参考实现

以下代码是实施骨架，生成时需按项目 compileSdk 对 API 的 Kotlin/Java
签名校验，尤其是 `NetworkStats` 关闭方式和异常声明。

``` kotlin
class AndroidNetworkStatsDataSource(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : NetworkStatsDataSource {

    private val manager =
        context.getSystemService(NetworkStatsManager::class.java)

    override suspend fun queryDevice(
        type: NetworkType,
        range: TimeRange
    ): Result<TrafficBytes> = withContext(ioDispatcher) {
        try {
            val networkType = type.toConnectivityType()
            val bucket = manager.querySummaryForDevice(
                networkType,
                null, // MVP 不区分 subscriber / SIM
                range.startMillis,
                range.endMillis
            )
            if (bucket == null) {
                Result.failure(IllegalStateException("No device bucket"))
            } else {
                Result.success(
                    TrafficBytes(
                        rxBytes = bucket.rxBytes.coerceAtLeast(0L),
                        txBytes = bucket.txBytes.coerceAtLeast(0L)
                    )
                )
            }
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: RemoteException) {
            Result.failure(e)
        } catch (e: RuntimeException) {
            Result.failure(e)
        }
    }

    override suspend fun queryUid(
        type: NetworkType,
        range: TimeRange
    ): Result<Map<Int, TrafficBytes>> = withContext(ioDispatcher) {
        var stats: NetworkStats? = null
        try {
            stats = manager.querySummary(
                type.toConnectivityType(),
                null,
                range.startMillis,
                range.endMillis
            ) ?: return@withContext Result.failure(
                IllegalStateException("NetworkStats returned null")
            )

            val totals = mutableMapOf<Int, MutableTrafficBytes>()
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                if (!stats.getNextBucket(bucket)) break
                val uid = bucket.uid
                val item = totals.getOrPut(uid) { MutableTrafficBytes() }
                item.rxBytes += bucket.rxBytes.coerceAtLeast(0L)
                item.txBytes += bucket.txBytes.coerceAtLeast(0L)
            }

            Result.success(
                totals.mapValues { (_, value) ->
                    TrafficBytes(value.rxBytes, value.txBytes)
                }
            )
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: RemoteException) {
            Result.failure(e)
        } catch (e: RuntimeException) {
            Result.failure(e)
        } finally {
            stats?.close()
        }
    }

    private fun NetworkType.toConnectivityType(): Int = when (this) {
        NetworkType.MOBILE -> ConnectivityManager.TYPE_MOBILE
        NetworkType.WIFI -> ConnectivityManager.TYPE_WIFI
    }

    private data class MutableTrafficBytes(
        var rxBytes: Long = 0L,
        var txBytes: Long = 0L
    )
}
```

**代码生成器必须做的 SDK 校验**：若所选 compileSdk 中
`querySummaryForDevice`/`querySummary` 的参数、异常或 close API
与上述骨架不一致，按实际 Android SDK API
修正，并保留相同语义。不要通过捕获 `Throwable` 隐藏编程错误；可在
Repository 顶层捕获非致命 `Exception` 作为 `UNKNOWN`，但不要吞掉
`CancellationException`。

### 6.3 Repository 聚合逻辑

对设备 totals：

1.  分别查询 MOBILE 与 WIFI。
2.  两项都成功才返回 `Success(TrafficTotals)`。
3.  若一项失败，不要把失败项当成 0
    并报告完整总量；返回错误状态，或设计明确的 partial-result 状态并在
    UI 标注"不完整"。MVP 推荐两项都成功才展示总量。
4.  只有成功结果中的实际 0 才显示 0。

对应用列表：

1.  分别查询 MOBILE UID map 与 WIFI UID map。
2.  若任何网络类型查询失败，列表标记为失败/不完整，不静默当作 0。
3.  对两个 map 的 UID key 取并集；某一网络没有该 UID
    时该网络用成功查询的 0 值。
4.  对每个 UID 调 `PackageDataSource.resolveUid()`；映射失败也保留 UID
    条目。
5.  计算移动 + Wi-Fi 总量，过滤总量为 0 的行，按总量降序。
6.  不要求 UID 列表之和等于设备总量；可在 debug/test 记录差额，不在 UI
    制造"精确对账"承诺。

------------------------------------------------------------------------

## 7. ViewModel 与 UI 状态

### 7.1 状态模型

``` kotlin
data class HomeUiState(
    val selectedPeriod: TrafficPeriod = TrafficPeriod.TODAY,
    val totals: DataState<TrafficTotals> = DataState.Loading,
    val apps: DataState<List<AppTraffic>> = DataState.Loading,
    val refreshing: Boolean = false,
    val hasUsageAccess: Boolean = false
)
```

ViewModel 至少提供：

``` kotlin
fun selectPeriod(period: TrafficPeriod)
fun refresh()
fun onResumePermissionCheck()
fun openAppDetail(uid: Int)
```

要求：

-   通过 `MutableStateFlow` 私有持有、暴露只读 `StateFlow`。
-   查询放 `viewModelScope.launch`，Repository 的 IO dispatcher
    负责线程切换。
-   时间范围切换时重新查询 totals 和 apps。
-   下拉刷新时更新 refreshing 状态。
-   Activity `onResume` 时检查权限；若权限从设置刚授予，刷新数据。
-   页面旋转不重复启动多个并发查询；可取消旧任务或使用
    `flatMapLatest`/job 管理。
-   UI 不直接调用 `NetworkStatsManager`。

### 7.2 页面行为

**首页** - 今日 / 本周 / 本月切换控件。 -
总量卡片：总用量、移动、Wi-Fi。 -
应用列表：图标、名称、总用量，可选移动/Wi-Fi细分，降序。 -
加载、无数据、权限不足、读取失败状态。 - SwipeRefresh 或 Material
下拉刷新。

**详情页** - 应用名称、图标、UID（可在诊断/关于中显示）。 -
当前周期移动/Wi-Fi/RX/TX。 -
允许切换今日/本周/本月，或按需求文档展示三周期。

**权限引导页** - 说明使用情况访问权限用于读取系统网络用量。 -
"前往授权"按钮。 - 从设置返回时重检。

**关于页** - 版本号、权限状态、隐私声明、已知限制。

**格式化** - 使用二进制单位（1 KiB = 1024 bytes）并明确采用 KB/MB/GB
还是 KiB/MiB/GiB。建议 UI 显示 `KB/MB/GB` 时在实现文档中定义为 1024
进制，避免歧义。 - `TrafficFormatter` 单元测试覆盖 0、1023、1024、1
MiB、1 GiB 和大值。

------------------------------------------------------------------------

## 8. 异常与用户提示映射

  -----------------------------------------------------------------------------------------------
  场景                           内部状态                  UI 提示/动作
  ------------------------------ ------------------------- --------------------------------------
  未授予使用情况访问             `PermissionDenied`        "需要使用情况访问权限才能统计流量" +
                                                           设置按钮

  系统查询成功，数值为 0         `Success(0)`              显示 `0 B`

  API 返回 null / 没有可用       `NoData` 或明确的 query   "暂无系统流量记录"
  bucket                         failure（依实现语义）     

  `SecurityException`            `PermissionDenied`        提示检查权限，提供设置跳转

  `RemoteException`              `Error(REMOTE_SERVICE)`   "系统统计服务暂不可用，请重试"

  其他可恢复异常                 `Error(UNKNOWN)`          "读取失败，请稍后重试"

  图标/包名无法解析              UID fallback              显示"未知应用 (UID
                                                           n)"或"系统/多个应用"

  一种网络类型成功，另一种失败   MVP totals/apps           不把失败类型当 0；提示结果不完整/重试
                                 查询失败或明确 partial    

  查询被取消                     取消传播                  不展示错误 Toast，不更新为失败
  -----------------------------------------------------------------------------------------------

日志：可使用 Android
`Log`，不引入日志上传库。避免打印完整包清单或个人流量历史；只记录错误类别、API
路径及必要诊断信息。

------------------------------------------------------------------------

## 9. 测试用例

### 9.1 JVM 单元测试（不依赖设备权限）

**TimeRangeProviderTest** - TODAY：本地当天 00:00 至 now。 -
WEEK：周一当天、周二、周日都回溯到本周周一。 - MONTH：月中回到当月 1
日。 - 跨年：1 月初正确确定周起始日。 - 闰年 2 月、非闰年 2 月。 -
DST/时区变更：使用指定 ZoneId，边界由当地日历计算，不固定加 24 小时。 -
now 早于 start 的防御逻辑（如系统时间异常，按产品策略报错或钳制）。

**TrafficAggregatorTest** 使用 fake 数据源： - MOBILE 与 WIFI 的同 UID
流量正确合并。 - 只存在 MOBILE 或只存在 WIFI 的 UID 仍出现在列表。 -
RX/TX 分开累加，totalBytes 正确。 - 共享 UID
映射成"多个应用"且只出现一行。 - 未映射 UID 保留为 fallback 行。 -
总量降序，稳定处理相同用量。 - 0 用量行过滤。 -
任一来源失败时不伪装成完整结果。 - 设备总量与 UID
合计不一致时不擅自修正。

**Repository/ViewModel tests** - permission denied
不触发统计调用或能正确短路。 - refresh 进入 Loading/refreshing，成功后
Success。 - query exception 映射成适当状态。 - period selection
更新时间范围并重新查询。 - 取消旧请求后不会把旧周期结果覆盖新周期。

### 9.2 Android 仪器测试 / 真机验证

在至少两台不同 Android 版本/厂商设备上执行：

1.  首次安装、权限未授予：显示引导页，不崩溃。
2.  点击设置按钮：进入使用情况访问设置（若系统不提供则回退）。
3.  授权后返回：自动刷新，显示系统数据。
4.  从设置撤销权限后返回：状态变为 PermissionDenied。
5.  设备总量 query 与 UID query 在 MOBILE/WIFI
    类型上执行成功或显示可解释错误。
6.  Wi-Fi only、移动数据 only、两者均开、网络均关。
7.  双 SIM：MVP 不显示 SIM 分项，但验证 aggregate mobile query
    行为并记录设备结果。
8.  共享 UID、系统 UID、无法映射 UID、图标读取失败。
9.  系统无可用历史记录、API 返回 null、服务异常（能模拟的用
    fake，真机记录不可控情况）。
10. 下拉刷新、周期切换、从后台返回；UI 不冻结。
11. 低端/大量安装应用设备的列表滚动与查询耗时。
12. Release APK：检查 merged manifest 不含
    INTERNET；检查依赖树无广告/分析 SDK；记录实际 APK 大小。

### 9.3 建议的验收门槛

-   未授权、查询失败、空数据、真实 0 四种状态可区分。
-   所有查询不在主线程执行。
-   时间范围 JVM 测试通过。
-   聚合逻辑单元测试通过。
-   至少两种设备完成基本权限和查询验证；无法验证的设备/系统范围列入已知限制。
-   Release 构建成功；最终 Manifest 无 `INTERNET`。
-   APK \< 5MB 作为优化目标，不作为功能正确性的阻断条件。

------------------------------------------------------------------------

## 10. 代码生成交付清单

代码生成工具完成后，应输出：

1.  可构建的 Android Studio 项目全部源文件。
2.  `README.md`：打开项目、同步 Gradle、运行测试、授权步骤、已知限制。
3.  JVM 单元测试和至少一个仪器测试骨架。
4.  构建命令：
    -   `./gradlew test`
    -   `./gradlew assembleDebug`
    -   `./gradlew assembleRelease`
    -   `./gradlew :app:dependencies`
5.  Release merged manifest 检查说明。
6.  不得声称"已在真机验证"，除非确实运行过相应设备测试并记录设备型号、Android
    版本、测试结果。

## 11. 官方 API 参考

-   `NetworkStatsManager`：https://developer.android.com/reference/android/app/usage/NetworkStatsManager
-   `NetworkStats`：https://developer.android.com/reference/android/app/usage/NetworkStats
-   `AppOpsManager`：https://developer.android.com/reference/android/app/AppOpsManager
-   `Settings.ACTION_USAGE_ACCESS_SETTINGS`：https://developer.android.com/reference/android/provider/Settings
-   Android package
    visibility：https://developer.android.com/training/package-visibility
-   Java API
    desugaring：https://developer.android.com/studio/write/java8-support
-   Gradle
    项目结构：https://docs.gradle.org/current/userguide/organizing_build_logic.html
