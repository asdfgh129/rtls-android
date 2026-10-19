# ADR-007：Android 定位源抽象

- 状态：Accepted
- 日期：2026-10-19
- 关联：[初始方案（归档）](../../archive/plan-2026-10-17.md) 3.1、[初始步骤（归档）](../../archive/step-2026-10-17.md) 阶段二

## 背景

`Plan.md` 以 `FusedLocationProviderClient`（FLP）作为唯一方案。但 FLP 依赖 Google Play Services：

- 国内大量 Android 设备没有 GMS，FLP 可能不可用；
- 即使有 GMS，部分厂商的后台策略也会影响定位；
- 文档已经意识到“WiFi 定位在国内失效”，但没有意识到 FLP 本身也可能不可用。

如果定位源不可用，整个 App 的核心功能失效。

## 决策

引入 **`LocationSource` 抽象**，运行时选择可用实现：

```kotlin
interface LocationSource {
    val id: String
    fun isAvailable(context: Context): Boolean
    fun start(config: LocationConfig): Flow<LocationSample>
    fun stop()
}
```

实现：

| 实现 | 说明 | 优先级 |
|---|---|---|
| `GmsFusedLocationSource` | Google Play Services FLP，WGS-84 | 1 |
| `BaiduLocationSource` | 百度定位 SDK，GCJ-02 → 设备端转 WGS-84 | 2 |
| `AmapLocationSource` | 高德定位 SDK，作为备选 | 3（可选） |
| `FakeLocationSource` | 测试/模拟器 | 仅 debug/test |

选择逻辑：

1. `GoogleApiAvailability.isGooglePlayServicesAvailable(context)` 可用 → FLP；
2. 否则检测百度/高德 SDK 是否初始化成功 → 对应实现；
3. 都不可用 → 明确错误状态，引导用户检查设备/权限，不静默失败。

### 统一数据模型

```kotlin
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val crs: Crs,
    val accuracyMeters: Float,
    val provider: String,
    val timestampMs: Long,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val isMock: Boolean
)
```

上传前统一转换为 WGS-84（见 ADR-006），并在 payload 中带 `sourceCrs`。

### 前台服务

- `LocationForegroundService` 持有当前 `LocationSource`；
- `foregroundServiceType="location"`；
- 通知渠道在 Android 8+ 创建；
- Android 13+ 请求 `POST_NOTIFICATIONS`；
- Android 14+ 声明 `FOREGROUND_SERVICE_LOCATION`；
- 在 App 可见时启动服务，避免后台启动限制；
- `START_STICKY` + 进程死亡后恢复策略。

### 权限决策树（不要写成“必须”）

1. 请求 `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`；
2. 请求 `POST_NOTIFICATIONS`（Android 13+）；
3. 如果产品需要在没有前台服务时也定位，或需要从后台启动定位：
   - 引导用户到系统设置授予 `ACCESS_BACKGROUND_LOCATION`；
   - Android 11+ 不能在权限弹窗中直接选择“始终允许”；
4. 如果定位始终由用户可见时启动的 location 前台服务提供，通常持有 while-in-use 权限即可；是否需要后台权限取决于实际启动路径，必须按目标 Android 版本验证。

### 电量策略

- 静止/低速：`PRIORITY_BALANCED_POWER_ACCURACY`；
- 移动：`PRIORITY_HIGH_ACCURACY`；
- `setMinUpdateDistanceMeters(10~20)`；
- `setMaxUpdateDelayMillis(30s)` 批量上传；
- 避免固定 5 秒高精度常驻；提供“省电/实时”档位。

## 理由

- 把“定位从哪来”和“怎么上传/展示”解耦，避免 GMS 缺失导致整体不可用。
- 测试可以注入 `FakeLocationSource`，不需要真机 GPS。
- 未来接入高德/百度只需新增实现，不改 FGS 与网络层。

## 后果

### 正面

- 国内设备可用性显著提升。
- 定位算法可单元测试。
- 多家 SDK 可以灰度切换。

### 负面 / 需要注意

- 增加百度/高德 SDK 依赖、AK 配置和隐私合规工作。
- 需要处理 GCJ-02 → WGS-84 的近似误差。
- 不同厂商的定位频率/精度行为不同，需要真机测试矩阵。
- 后台定位权限流程更复杂，需要 UI 引导与拒绝后的降级。

## 备选方案

| 方案 | 结论 | 原因 |
|---|---|---|
| 只用 FLP | 不采用 | 国内 GMS 缺失时不可用 |
| 只用百度定位 SDK | 不采用 | 海外/GMS 设备体验较差；绑定单一厂商 |
| 自建 WiFi/基站定位 | 不采用 | 数据源与合规成本过高 |
| 仅前台定位 | 不采用 | 不满足持续追踪需求 |

## 验证方式

- 单元测试：`FakeLocationSource` 驱动 FGS 与上传逻辑。
- 设备测试：有 GMS / 无 GMS 设备各至少 1 台；百度/高德兜底可启动。
- 权限测试：拒绝通知、拒绝后台定位、仅前台权限三种路径。
- 电量基线：1 小时追踪耗电与定位成功率记录在案。
