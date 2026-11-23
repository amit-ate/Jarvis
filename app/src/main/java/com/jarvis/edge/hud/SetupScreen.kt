package com.jarvis.edge.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.setup.ItemPhase
import com.jarvis.edge.setup.ItemProgress
import com.jarvis.edge.setup.NetState
import com.jarvis.edge.setup.SetupProgress
import java.util.Locale

private const val BYTES_PER_GB = 1_000_000_000.0

/** "1.1 of 2.3 GB": decimal gigabytes, one decimal. */
fun gbText(done: Long, total: Long): String =
    String.format(Locale.US, "%.1f of %.1f GB", done / BYTES_PER_GB, total / BYTES_PER_GB)

/** "412 MB" under a thousand decimal megabytes (at least "1 MB" for anything non-zero), else "1.07 GB". */
fun sizeText(bytes: Long): String {
    val mb = Math.round(bytes / 1_000_000.0)
    return when {
        bytes > 0L && mb == 0L -> "1 MB"
        mb < 1_000L -> "$mb MB"
        else -> String.format(Locale.US, "%.2f GB", bytes / BYTES_PER_GB)
    }
}

/**
 * The size shown at the right of a module row: "done / total" while the item moves, else the total alone.
 */
fun rowSizeText(item: ItemProgress): String = when (item.phase) {
    ItemPhase.DOWNLOADING, ItemPhase.VERIFYING, ItemPhase.UNPACKING -> "${sizeText(item.bytes)} / ${sizeText(item.size)}"
    ItemPhase.DONE, ItemPhase.QUEUED, ItemPhase.FAILED -> sizeText(item.size)
}

/** "4.0 GB free": the free space on the model volume. */
fun freeText(bytes: Long): String = String.format(Locale.US, "%.1f GB free", bytes / BYTES_PER_GB)

/** "~9 min left", "<1 min left" under a minute, and "" while the speed is unknown. */
fun etaText(sec: Long?): String = when {
    sec == null -> ""
    sec < 60L -> "<1 min left"
    else -> "~${sec / 60L} min left"
}

/** One line on what the setup is doing right now. */
fun stepText(p: SetupProgress): String {
    if (p.finished) return "Ready"
    when (p.net) {
        NetState.WAITING_FOR_WIFI -> return "waiting for Wi-Fi"
        NetState.OFFLINE -> return "offline"
        NetState.ALLOWED -> Unit
    }
    val active = activeItem(p.items)
        ?: return if (p.items.any { it.phase == ItemPhase.FAILED }) "paused" else "starting"
    val verb = when (active.phase) {
        ItemPhase.DOWNLOADING -> "downloading"
        ItemPhase.VERIFYING -> "verifying"
        else -> "installing"
    }
    return "$verb ${active.label.lowercase(Locale.US)}"
}

/** The item being downloaded, checked or unpacked right now, if any. */
private fun activeItem(items: List<ItemProgress>): ItemProgress? = items.firstOrNull {
    it.phase == ItemPhase.DOWNLOADING || it.phase == ItemPhase.VERIFYING || it.phase == ItemPhase.UNPACKING
}

/** The rows shown while the list is collapsed: the active item, the next two queued and every failed one, in order. */
fun visibleItems(items: List<ItemProgress>): List<ItemProgress> {
    val shown = (listOfNotNull(activeItem(items)) + items.filter { it.phase == ItemPhase.QUEUED }.take(2) +
        items.filter { it.phase == ItemPhase.FAILED }).map { it.id }.toSet()
    return items.filter { it.id in shown }
}

/** The first-setup screen: a ring with the orb in it, the step, and one row per module. */
@Composable
fun SetupScreen(
    progress: SetupProgress,
    freeBytes: Long,
    onRetry: (String) -> Unit,
    onStartWithout: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val collapsed = visibleItems(progress.items)
    val shown = if (expanded) progress.items else collapsed
    val (netLabel, netColor) = when (progress.net) {
        NetState.ALLOWED -> "online" to Jx.Mint
        NetState.WAITING_FOR_WIFI -> "waiting for Wi-Fi" to Jx.Amber
        NetState.OFFLINE -> "offline" to Jx.Red
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawHudBackdrop(Jx.Cyan) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Bringing Jarvis online",
                color = Jx.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("on-device", Jx.Cyan)
                StatusChip(netLabel, netColor)
                StatusChip(freeText(freeBytes), Jx.TextDim, dot = false)
            }
            Spacer(Modifier.height(18.dp))
            ProgressRing(progress)
            Spacer(Modifier.height(12.dp))
            Text(stepText(progress), color = Jx.Text, fontSize = 15.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            HudLabel(
                listOf(gbText(progress.doneBytes, progress.totalBytes), etaText(progress.etaSec))
                    .filter { it.isNotEmpty() }
                    .joinToString("  ·  "),
                color = Jx.TextDim,
                size = 11,
            )
            Spacer(Modifier.height(18.dp))

            progress.blockedBy?.let {
                Text(it, color = Jx.Red, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }

            GlassPanel(Modifier.fillMaxWidth(), accent = Jx.Cyan, padding = PaddingValues(14.dp)) {
                val ready = progress.items.count { it.phase == ItemPhase.DONE }
                HudLabel("${ready} of ${progress.items.size} modules ready", color = Jx.Mint, size = 10)
                shown.forEach { item -> ItemRow(item, onRetry) }
                if (progress.items.size > collapsed.size) {
                    Spacer(Modifier.height(6.dp))
                    HudLabel(
                        if (expanded) "show less" else "tap to expand",
                        color = Jx.Cyan,
                        size = 10,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (progress.canStartWithout) {
                    PillButton("Start without it", onClick = onStartWithout, color = Jx.Amber)
                }
                Spacer(Modifier.weight(1f))
                Text("Wi-Fi only", color = Jx.TextDim, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Switch(checked = progress.wifiOnly, onCheckedChange = onWifiOnly)
            }
            Spacer(Modifier.height(14.dp))
            HudLabel("Jarvis wakes when every module checks out", modifier = Modifier.fillMaxWidth(), size = 10)
        }
    }
}

/** The ring around the orb: sweep is the overall fraction, the centre shows the percent or "Ready". */
@Composable
private fun ProgressRing(progress: SetupProgress) {
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(260.dp)) {
            val stroke = 4.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Jx.Line,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(width = stroke),
            )
            if (progress.fraction > 0f) {
                drawArc(
                    color = Jx.Cyan,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arc,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        JarvisOrb(state = AssistantState.IDLE, micLevel = 0f, size = 220.dp)
        Text(
            if (progress.finished) "Ready" else "${(progress.fraction * 100).toInt()}%",
            color = Jx.Text,
            style = TextStyle(fontSize = 34.sp, fontFamily = Jx.Mono, fontWeight = FontWeight.Light),
        )
    }
}

@Composable
private fun ItemRow(item: ItemProgress, onRetry: (String) -> Unit) {
    val (mark, markColor) = when (item.phase) {
        ItemPhase.DONE -> "✓" to Jx.Mint
        ItemPhase.FAILED -> "!" to Jx.Red
        ItemPhase.QUEUED -> "○" to Jx.TextFaint
        else -> "●" to Jx.Cyan
    }
    val active = item.phase == ItemPhase.DOWNLOADING || item.phase == ItemPhase.VERIFYING || item.phase == ItemPhase.UNPACKING
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mark, color = markColor, fontFamily = Jx.Mono, fontSize = 13.sp, modifier = Modifier.width(20.dp))
            Text(
                item.label,
                color = if (item.phase == ItemPhase.QUEUED) Jx.TextFaint else Jx.Text,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                rowSizeText(item),
                color = when (item.phase) {
                    ItemPhase.DONE -> Jx.Mint.copy(alpha = 0.6f)
                    ItemPhase.QUEUED -> Jx.TextFaint
                    else -> Jx.TextDim
                },
                fontFamily = Jx.Mono,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
            if (item.phase == ItemPhase.FAILED) {
                PillButton("Retry", onClick = { onRetry(item.id) }, color = Jx.Red)
            }
        }
        if (active) {
            val fraction = if (item.size > 0L) (item.bytes.toFloat() / item.size).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { fraction },
                color = Jx.Cyan,
                trackColor = Jx.Line,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp),
            )
        }
        if (item.phase == ItemPhase.FAILED && item.reason != null) {
            Text(item.reason, color = Jx.Red, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 2.dp))
        }
    }
}
