package com.rtls.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rtls.app.BuildConfig
import com.rtls.app.R
import com.rtls.app.ui.components.InfoRow
import com.rtls.app.ui.components.PermissionStatusRow
import com.rtls.app.ui.components.SectionCard
import com.rtls.core.location.PermissionState

@Composable
fun SettingsScreen(
    permissions: PermissionState,
    onRequestPermissions: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard {
            Text("权限", style = MaterialTheme.typography.titleLarge)
            PermissionStatusRow(
                title = "前台定位",
                description = if (permissions.foregroundGranted) "已授予" else "必需，用于采集位置",
                granted = permissions.foregroundGranted,
                actionLabel = "授予",
                onAction = onRequestPermissions,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            PermissionStatusRow(
                title = "通知",
                description = if (permissions.notificationsGranted) "已授予" else "显示定位服务常驻通知",
                granted = permissions.notificationsGranted,
                actionLabel = "允许",
                onAction = onRequestNotifications,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            PermissionStatusRow(
                title = "后台定位",
                description = if (permissions.backgroundGranted) {
                    "已授予“始终允许”"
                } else {
                    "可选，App 不可见时继续共享"
                },
                granted = permissions.backgroundGranted,
                actionLabel = "设置",
                onAction = onOpenSettings,
            )
        }

        SectionCard {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            InfoRow(stringResource(R.string.settings_account), stringResource(R.string.settings_account_value))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            InfoRow("服务端", BuildConfig.WS_BASE_URL)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            InfoRow(stringResource(R.string.settings_queue), "1,000 条 / 24 小时")
        }

        SectionCard {
            Text("隐私", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "位置以 WGS-84 上传到你的服务端；仅互为好友且目标开启分享时可被查看。关闭分享、解除好友或拉黑会立即撤销订阅。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow("Token 存储", "EncryptedSharedPreferences")
            InfoRow("生产传输", "仅 WSS，Token 只走 Header")
        }

        SectionCard {
            Text("关于", style = MaterialTheme.typography.titleLarge)
            InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("构建类型", if (BuildConfig.DEBUG) "debug" else "release")
            InfoRow("协议版本", "v1")
        }
    }
}
