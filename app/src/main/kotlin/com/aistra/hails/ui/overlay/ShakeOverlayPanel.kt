package com.aistra.hails.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.aistra.hails.BuildConfig
import com.aistra.hails.R
import com.aistra.hails.app.AppManager
import com.aistra.hails.app.HailData
import com.aistra.hails.app.UnfrozenStore
import com.aistra.hails.utils.HUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 悬浮面板里一条应用记录，图标已预先解码成 Bitmap，避免在组合期反复加载 */
internal data class OverlayApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val frozen: Boolean,
    val pinned: Boolean
)

/**
 * 摇一摇后弹出的悬浮面板：首页已收录的应用列表 + 快速冻结/解冻。
 * 点击条目直接启动该应用（冻结的会先解冻），右上角可打开完整界面或关闭。
 */
@Composable
internal fun ShakeOverlayPanel(
    onClose: () -> Unit,
    onOpenApp: (String?) -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<OverlayApp>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        apps = loadOverlayApps(context)
        loaded = true
    }

    /** 统一失败提示：未选工作模式时不要误报成「权限不足」 */
    fun notifyFailure() {
        HUI.showToast(
            if (HailData.workingMode == HailData.MODE_DEFAULT) R.string.msg_guide else R.string.permission_denied
        )
    }

    fun toggle(app: OverlayApp) {
        scope.launch {
            val frozen = !app.frozen
            val result = withContext(Dispatchers.IO) { AppManager.setAppFrozen(app.packageName, frozen) }
            if (result) refreshKey++ else notifyFailure()
        }
    }

    fun setAll(frozen: Boolean) {
        scope.launch {
            val targets = apps.filter { it.frozen != frozen }.map { it.packageName }
            if (targets.isEmpty()) return@launch
            val changed = withContext(Dispatchers.IO) {
                targets.count { AppManager.setAppFrozen(it, frozen) }
            }
            if (changed == 0) notifyFailure() else refreshKey++
        }
    }

    /** 冻结的应用没有可用的启动 Intent，先解冻再启动，并记入首页「冻结」栏 */
    fun launchApp(app: OverlayApp) {
        scope.launch {
            if (app.frozen) {
                val unfrozen = withContext(Dispatchers.IO) {
                    AppManager.setAppFrozen(app.packageName, false).also {
                        if (it) UnfrozenStore.add(app.packageName)
                    }
                }
                if (!unfrozen) {
                    notifyFailure()
                    return@launch
                }
            }
            onOpenApp(app.packageName)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 按住标题栏可拖动整个窗口
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Vibration,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onOpenApp(null) }) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = stringResource(R.string.shake_open_app))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.shake_close))
                }
            }
            HorizontalDivider()
            when {
                !loaded -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }

                apps.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.shake_overlay_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        OverlayAppRow(
                            app = app,
                            onLaunch = { launchApp(app) },
                            onToggle = { toggle(app) }
                        )
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { setAll(true) }, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.action_freeze_all),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { setAll(false) }, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.action_unfreeze_all),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayAppRow(app: OverlayApp, onLaunch: () -> Unit, onToggle: () -> Unit) {
    val grayscale = remember { ColorMatrix().apply { setToSaturation(0f) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLaunch)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                // 冻结应用显示为灰度，与首页一致
                colorFilter = if (app.frozen) ColorFilter.colorMatrix(grayscale) else null
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Apps,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (app.frozen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(if (app.frozen) R.string.shake_state_frozen else R.string.shake_state_unfrozen),
                style = MaterialTheme.typography.labelSmall,
                color = if (app.frozen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (app.frozen) Icons.Outlined.WbSunny else Icons.Outlined.AcUnit,
                contentDescription = stringResource(if (app.frozen) R.string.action_unfreeze else R.string.action_freeze),
                tint = if (app.frozen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 读取首页已收录的应用并解析图标、冻结状态（耗时操作，放 IO 线程） */
private suspend fun loadOverlayApps(context: Context): List<OverlayApp> = withContext(Dispatchers.IO) {
    val packageManager = context.packageManager
    val iconSize = (48 * context.resources.displayMetrics.density).toInt()
    HailData.checkedList.mapNotNull { info ->
        if (info.packageName == BuildConfig.APPLICATION_ID) return@mapNotNull null
        val applicationInfo = info.applicationInfo ?: return@mapNotNull null
        val icon = runCatching {
            applicationInfo.loadIcon(packageManager)
                .toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }.getOrNull()
        OverlayApp(
            packageName = info.packageName,
            label = applicationInfo.loadLabel(packageManager).toString(),
            icon = icon,
            frozen = AppManager.isAppFrozen(info.packageName),
            pinned = info.pinned
        )
    }.sortedWith(compareByDescending<OverlayApp> { it.pinned }.thenBy { it.label })
}
