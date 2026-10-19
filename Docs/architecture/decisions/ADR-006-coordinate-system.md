# ADR-006：坐标系统与转换

- 状态：Accepted
- 日期：2026-10-19
- 关联：[初始方案（归档）](../../archive/plan-2026-10-17.md) 3.3、[初始步骤（归档）](../../archive/step-2026-10-17.md) 阶段三

## 背景

现有文档写：

> FLP 返回 WGS-84，百度地图使用 BD-09。如果直接使用百度地图 SDK，SDK 内部会自动处理转换。

这个结论对 **WGS-84 不成立**。百度官方文档说明：

- 国内地图 SDK 输入/输出默认使用 BD-09LL；
- `SDKInitializer.setCoordType(CoordType.GCJ02)` 只对 **GCJ-02 输入**做全局自动转换；
- WGS-84 必须手动调用 `CoordinateConverter().from(CoordinateConverter.CoordType.GPS)` 转换。

参考：<https://lbs.baidu.com/docs/android?title=androidsdk%2Fguide%2Ftool%2Fcoordinate>

如果按 Step 的 `setCoordType(BD09LL)` + FLP 原始 WGS-84 直接画 Marker，会产生明显偏移。

## 决策

**以 WGS-84 作为全链路规范坐标；只在 Android 百度地图渲染层转换为 BD-09。**

### 规则

1. Android 定位源在**上传前**将坐标规范化为 WGS-84。
2. 服务端只存储 WGS-84，不做坐标转换。
3. 历史接口返回 WGS-84。
4. Android 渲染百度地图时：
   - WGS-84 → BD-09：`CoordinateConverter().from(CoordinateConverter.CoordType.GPS).coord(point).convert()`；
   - GCJ-02 → BD-09：使用 `CoordinateConverter.CoordType.COMMON`，或全局 `SDKInitializer.setCoordType(CoordType.GCJ02)`（仅当输入统一为 GCJ-02 时）。
5. 每个位置点记录 `source_crs`（`WGS84` / `GCJ02` / `BD09`），用于审计和排查。
6. 禁止在代码中传递“裸 `double lat/lng`”而不带 CRS 标签。

### 类型建议

```kotlin
enum class Crs { WGS84, GCJ02, BD09 }

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val crs: Crs
)
```

Android 地图层只接受 `GeoPoint(crs = BD09)`，编译期/测试期强制转换。

### 定位源兜底

- GMS FLP：输出 WGS-84，直接使用。
- 百度/高德定位 SDK：通常输出 GCJ-02；在设备端转换为 WGS-84 后上传，并记录 `sourceCrs = GCJ02`。
- 如果转换算法无法保证精度，必须在上线前用已知点校准；不能静默混用。

### 展示与精度过滤

- 存储时保留 `accuracy`，不要因为精度差直接丢弃；
- 展示/轨迹绘制时过滤 `accuracy > 100m` 的点（可配置）；
- 抽稀用服务端时间桶或客户端 Douglas-Peucker，不能改变原始历史数据。

### 国内/海外

- 国内（含港澳台）：百度地图使用 BD-09LL；
- 海外：百度地图使用 WGS-84，无需转换；
- MVP 假设只服务国内；若支持海外，需要按区域切换 CRS 策略并增加测试。

## 理由

- WGS-84 是 GPS 原始坐标，作为存储规范最稳定。
- BD-09 是国内百度地图的展示坐标，不应污染数据库。
- 显式 CRS 可以避免“某个接口以为自己是 BD-09、另一个以为是 WGS-84”的隐蔽 bug。
- 使用百度官方转换接口，避免第三方算法误差与合规风险。

## 后果

### 正面

- 地图标记与真实位置一致。
- 多定位源（FLP/百度/高德）可以共存，只要上传前规范化。
- 历史数据可迁移到其他地图（高德/腾讯）而无需重算。

### 负面 / 需要注意

- Android 端需要维护转换模块与已知点测试。
- GCJ-02 → WGS-84 是近似转换，精度有上限；必须在 UI 上接受这一误差。
- 海外地图行为不同，不能一套逻辑硬编码。

## 备选方案

| 方案 | 结论 | 原因 |
|---|---|---|
| 直接存 BD-09 | 不采用 | 绑定百度；其他地图/分析难复用 |
| 直接存 GCJ-02 | 不采用 | FLP 是 WGS-84，仍需转换；且 GCJ-02 是加密坐标 |
| 依赖 SDK 自动转换 | 不采用 | 只对 GCJ-02 生效，WGS-84 会偏移 |
| 服务端统一转 BD-09 | 不采用 | 服务端不应耦合地图厂商 |

## 验证方式

- 单元测试：北京天安门、上海人民广场、广州塔等已知点的 WGS-84 → BD-09 结果与百度官方工具一致。
- 真机测试：FLP 位置与百度地图“我的位置”偏差在可接受范围（目标 < 20m，视设备而定）。
- 历史轨迹：同一段轨迹在百度地图上不出现整体平移。
