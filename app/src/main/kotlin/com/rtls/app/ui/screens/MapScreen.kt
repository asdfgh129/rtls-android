package com.rtls.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtls.app.ui.components.HeroPanel
import com.rtls.app.ui.components.InfoRow
import com.rtls.app.ui.components.SectionCard
import com.rtls.app.ui.format.RelativeTime
import com.rtls.app.ui.format.formatAccuracy
import com.rtls.app.ui.format.formatCoordinate
import com.rtls.core.location.PermissionState
import com.rtls.core.location.TrackingStatus
import com.rtls.core.map.MapCoordinates
import com.rtls.core.model.LocationSample

@Composable
fun MapScreen(
    sample: LocationSample?,
    status: TrackingStatus,
    sourceId: String?,
    useFake: Boolean,
    allowFakeToggle: Boolean,
    errorText: String?,
    permissions: PermissionState,
    onToggleFake: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = status == TrackingStatus.RUNNING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroPanel(
            title = when (status) {
                TrackingStatus.RUNNING -> "正在共享位置"
                TrackingStatus.ERROR -> "定位异常"
                TrackingStatus.STOPPED -> "尚未开始共享"
            },
            subtitle = errorText ?: "位置以 WGS-84 上传，仅你授权的好友可见。",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = if (running) onStop else onStart,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(if (running) "停止共享" else "开始共享", fontWeight = FontWeight.SemiBold)
                }
                if (allowFakeToggle) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(
                            checked = useFake,
                            onCheckedChange = onToggleFake,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.onPrimary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                uncheckedTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
                                uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                        Text(
                            text = "模拟",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }

        MapPreviewCard(sample = sample)

        if (!permissions.notificationsGranted) {
            InlinePermissionCard(
                title = "通知权限未开启",
                body = "开启通知后可以看到定位服务的常驻状态。",
                action = "允许通知",
                onAction = onRequestNotifications,
            )
        }

        if (!permissions.backgroundGranted) {
            InlinePermissionCard(
                title = "后台定位（可选）",
                body = "需要在 App 不可见时继续共享，请到系统设置选择“始终允许”。",
                action = "去设置",
                onAction = onOpenSettings,
            )
        }

        CoordinatesCard(sample = sample, sourceId = sourceId)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MapPreviewCard(sample: LocationSample?) {
    val wgs84 = sample?.point
    val bd09 = sample?.let { MapCoordinates.fromWgs84(it.point) }
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "map-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "map-pulse-value",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF0F2B27),
                        Color(0xFF164E45),
                        Color(0xFF0B1F1C),
                    ),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 46.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(
                    color = Color.White.copy(alpha = 0.06f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(
                    color = Color.White.copy(alpha = 0.06f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += step
            }

            val center = Offset(size.width / 2f, size.height * 0.44f)
            drawCircle(primary.copy(alpha = 0.10f), size.minDimension * 0.34f, center)
            drawCircle(primary.copy(alpha = 0.18f), size.minDimension * 0.26f * pulse, center)
            drawCircle(primary.copy(alpha = 0.26f), size.minDimension * 0.10f, center)

            val roadOne = Path().apply {
                moveTo(0f, size.height * 0.86f)
                cubicTo(
                    size.width * 0.25f,
                    size.height * 0.62f,
                    size.width * 0.55f,
                    size.height * 1.02f,
                    size.width,
                    size.height * 0.58f,
                )
            }
            drawPath(
                path = roadOne,
                color = Color.White.copy(alpha = 0.13f),
                style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round),
            )
            val roadTwo = Path().apply {
                moveTo(size.width * 0.12f, 0f)
                cubicTo(
                    size.width * 0.36f,
                    size.height * 0.26f,
                    size.width * 0.22f,
                    size.height * 0.72f,
                    size.width * 0.52f,
                    size.height,
                )
            }
            drawPath(
                path = roadTwo,
                color = Color.White.copy(alpha = 0.08f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.30f),
        ) {
            Text(
                text = "地图预览 · 未接入百度 SDK",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.86f),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(3.dp, primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(primary),
                )
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White,
            ) {
                Text(
                    text = if (sample == null) "等待定位…" else "你",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF0F2B27),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = wgs84?.let {
                    "WGS-84  ${formatCoordinate(it.latitude)}, ${formatCoordinate(it.longitude)}"
                } ?: "WGS-84  等待定位…",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = bd09?.let {
                    "BD-09   ${formatCoordinate(it.latitude)}, ${formatCoordinate(it.longitude)}"
                } ?: "BD-09   —",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun CoordinatesCard(sample: LocationSample?, sourceId: String?) {
    SectionCard {
        Text(
            text = "当前位置",
            style = MaterialTheme.typography.titleLarge,
        )
        if (sample == null) {
            Text(
                text = "尚未获取到位置。点击“开始共享”并授予定位权限后再试。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        val bd09 = MapCoordinates.fromWgs84(sample.point)
        InfoRow(
            label = "WGS-84",
            value = "${formatCoordinate(sample.latitude)}, ${formatCoordinate(sample.longitude)}",
        )
        InfoRow(
            label = "BD-09",
            value = "${formatCoordinate(bd09.latitude)}, ${formatCoordinate(bd09.longitude)}",
        )
        InfoRow(label = "精度", value = formatAccuracy(sample.accuracyMeters))
        InfoRow(
            label = "定位源",
            value = buildString {
                append(sample.provider.wireValue)
                sourceId?.let { append(" · ").append(it) }
            },
        )
        InfoRow(
            label = "最近更新",
            value = RelativeTime.formatSince(sample.timestampMs),
        )
        if (sample.isMock) {
            Text(
                text = "模拟位置已开启，数据会标记 isMock = true。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun InlinePermissionCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    SectionCard {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onAction) {
            Text(action)
        }
    }
}
