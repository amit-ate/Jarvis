package com.jarvis.edge.hud

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val BgDark = Jx.Void
private val SurfaceCard = Jx.Panel
private val SurfaceElevated = Jx.PanelHi
private val BorderSubtle = Jx.LineHi
private val TextPrimary = Jx.Text
private val TextSecondary = Jx.TextDim
private val CyanAccent = Jx.Cyan
private val EmeraldHealthy = Jx.Mint
private val AmberWarning = Jx.Amber
private val CoralAlert = Jx.Red

private val MindDarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    secondary = EmeraldHealthy,
    tertiary = AmberWarning,
    error = CoralAlert,
    background = BgDark,
    surface = SurfaceCard,
    surfaceVariant = SurfaceElevated,
    onPrimary = BgDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
)

private enum class MindTab(val title: String) {
    JOURNAL("Journal"),
    FACTS("Facts"),
    WORDS("Words"),
    SHORTCUTS("Shortcuts"),
    EXPERTS("Experts"),
    RESOURCES("Resources"),
}

@Composable
fun MindScreen(
    state: MindUiState,
    onBack: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = MindTab.entries

    MaterialTheme(colorScheme = MindDarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark),
        ) {
            MindHeader(
                learningPaused = state.learningPaused,
                statusNotice = state.resources.statusNotice,
                onBack = onBack,
                onTogglePaused = MindUiBridge::setLearningPaused,
                onReflect = MindUiBridge::triggerReflection,
                onExport = MindUiBridge::exportJson,
            )

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Jx.Void,
                contentColor = CyanAccent,
                edgePadding = 12.dp,
                divider = { HorizontalDivider(color = Jx.Line) },
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = CyanAccent,
                    )
                },
            ) {
                tabs.forEachIndexed { index, tab ->
                    val count = when (tab) {
                        MindTab.JOURNAL -> state.journal.size
                        MindTab.FACTS -> state.facts.size
                        MindTab.WORDS -> state.vocab.size + state.corrections.size
                        MindTab.SHORTCUTS -> state.shortcuts.size
                        MindTab.EXPERTS -> state.versions.size
                        MindTab.RESOURCES -> null
                    }
                    val label = if (count != null) "${tab.title} ($count)" else tab.title
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selectedTab == index) CyanAccent else TextSecondary,
                            )
                        },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                when (tabs[selectedTab]) {
                    MindTab.JOURNAL -> JournalTab(state.journal)
                    MindTab.FACTS -> FactsTab(state.facts)
                    MindTab.WORDS -> WordsTab(state.vocab, state.corrections)
                    MindTab.SHORTCUTS -> ShortcutsTab(state.shortcuts)
                    MindTab.EXPERTS -> ExpertsTab(state.resources, state.versions)
                    MindTab.RESOURCES -> ResourcesTab(state.resources)
                }
            }
        }
    }
}

@Composable
private fun MindHeader(
    learningPaused: Boolean,
    statusNotice: String,
    onBack: () -> Unit,
    onTogglePaused: (Boolean) -> Unit,
    onReflect: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Jx.Deep, Jx.Void)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RingButton(onClick = onBack, size = 40.dp) { Glyph(GlyphKind.BACK, TextPrimary, 18.dp) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Mind", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                HudLabel(
                    if (learningPaused) "learning paused" else "learning from you",
                    color = if (learningPaused) AmberWarning else EmeraldHealthy,
                    size = 10,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Pause",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = learningPaused,
                    onCheckedChange = onTogglePaused,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BgDark,
                        checkedTrackColor = AmberWarning,
                        uncheckedThumbColor = TextPrimary,
                        uncheckedTrackColor = SurfaceElevated,
                    ),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onReflect,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.6f)),
            ) {
                Text("Reflect", color = CyanAccent, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, BorderSubtle),
            ) {
                Text("Export", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            }
        }

        if (statusNotice.isNotBlank()) {
            Surface(
                color = CyanAccent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = statusNotice,
                    color = CyanAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun JournalTab(items: List<JournalItemUi>) {
    val sorted = remember(items) { items.sortedByDescending { it.timestampMs } }
    // Safety net: undoing forgets what Jarvis learned, so ask first.
    var confirm by remember { mutableStateOf<JournalItemUi?>(null) }
    confirm?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Jx.PanelHi,
            title = { Text("Undo this?", color = TextPrimary) },
            text = { Text(item.summary, color = TextSecondary, fontSize = 14.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { MindUiBridge.undoJournal(item.id); confirm = null }) {
                    Text("Undo", color = CoralAlert, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = null }) { Text("Keep", color = CyanAccent) }
            },
        )
    }
    if (sorted.isEmpty()) {
        EmptyStateCard(
            title = "No journal events yet",
            subtitle = "Facts, corrections, shortcuts, and reflection rollbacks will appear here.",
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sorted, key = { it.id }) { item ->
            val badgeColor = journalBadgeColor(item.type, item.undone)
            // Timeline row: a coloured node on a rail, then the event.
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(22.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Jx.Line))
                    Box(
                        Modifier.padding(top = 6.dp).size(10.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(badgeColor.copy(alpha = if (item.undone) 0.4f else 1f)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HudLabel(item.type.replace('_', ' '), color = badgeColor, size = 10)
                        Spacer(Modifier.width(8.dp))
                        HudLabel(formatTimestamp(item.timestampMs), color = Jx.TextFaint, size = 9, modifier = Modifier.weight(1f))
                        if (item.undone) {
                            HudLabel("undone", color = TextSecondary, size = 9)
                        } else {
                            Text(
                                "Undo",
                                color = CoralAlert.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { confirm = item }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.summary,
                        color = if (item.undone) TextSecondary else TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FactsTab(facts: List<FactItemUi>) {
    if (facts.isEmpty()) {
        EmptyStateCard(
            title = "No learned facts yet",
            subtitle = "Personal facts extracted from conversations will show here for review.",
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(facts, key = { it.id }) { fact ->
            val confPct = (fact.confidence.coerceIn(0f, 1f) * 100f).roundToInt()
            val confColor = when {
                confPct >= 80 -> EmeraldHealthy
                confPct >= 55 -> AmberWarning
                else -> CoralAlert
            }
            MindCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(text = "$confPct% confidence", color = confColor)
                    Text(
                        text = formatTimestamp(fact.createdAtMs),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = fact.text,
                    color = TextPrimary,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ActionButton(
                        label = "Forget",
                        accent = CoralAlert,
                        onClick = { MindUiBridge.deleteFact(fact.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WordsTab(
    vocab: List<VocabItemUi>,
    corrections: List<CorrectionItemUi>,
) {
    if (vocab.isEmpty() && corrections.isEmpty()) {
        EmptyStateCard(
            title = "No learned words or corrections",
            subtitle = "Custom hotwords and speech-recognition corrections appear here.",
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (vocab.isNotEmpty()) {
            item(key = "header-vocab") {
                SectionTitle("Learned Vocabulary (${vocab.size})")
            }
            items(vocab, key = { "vocab-${it.id}" }) { item ->
                MindCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.phrase,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Source: ${item.source}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        ActionButton(
                            label = "Delete",
                            accent = CoralAlert,
                            onClick = { MindUiBridge.deleteVocab(item.id) },
                        )
                    }
                }
            }
        }

        if (corrections.isNotEmpty()) {
            item(key = "header-corrections") {
                Spacer(Modifier.height(4.dp))
                SectionTitle("ASR Corrections (${corrections.size})")
            }
            items(corrections, key = { "corr-${it.id}-${it.idKey}" }) { item ->
                MindCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "\"${item.heard}\" → \"${item.meant}\"",
                                color = CyanAccent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${item.hitCount} ${if (item.hitCount == 1) "hit" else "hits"}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        ActionButton(
                            label = "Delete",
                            accent = CoralAlert,
                            onClick = { MindUiBridge.deleteCorrection(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutsTab(shortcuts: List<ShortcutItemUi>) {
    if (shortcuts.isEmpty()) {
        EmptyStateCard(
            title = "No promoted shortcuts yet",
            subtitle = "Repeated voice commands promoted to fast tool execution will appear here.",
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(shortcuts, key = { it.id }) { shortcut ->
            MindCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        text = "${shortcut.hitCount} ${if (shortcut.hitCount == 1) "hit" else "hits"}",
                        color = EmeraldHealthy,
                    )
                    ActionButton(
                        label = "Delete",
                        accent = CoralAlert,
                        onClick = { MindUiBridge.deleteShortcut(shortcut.id) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "\"${shortcut.utterance}\" → ${shortcut.toolName}(${shortcut.argsSummary})",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpertsTab(
    resources: ResourceStatusUi,
    versions: List<BrainVersionUi>,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "active-runtime") {
            MindCard {
                SectionTitle("Active Runtime")
                Spacer(Modifier.height(8.dp))
                MetricRow("Base Model", resources.activeModel, CyanAccent)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Active Expert Domains",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                if (resources.activeExperts.isEmpty()) {
                    Text(
                        text = "Base router (no specialized experts loaded)",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        resources.activeExperts.forEach { domain ->
                            StatusPill(text = domain, color = EmeraldHealthy)
                        }
                    }
                }
            }
        }

        item(key = "versions-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Brain Versions (${versions.size})")
                ActionButton(
                    label = "Evolve",
                    accent = EmeraldHealthy,
                    onClick = { MindUiBridge.triggerEvolution() },
                )
            }
        }

        if (versions.isEmpty()) {
            item(key = "versions-empty") {
                EmptyStateCard(
                    title = "No saved brain snapshots",
                    subtitle = "Eval-gated reflection checkpoints and LoRA adapter snapshots appear here.",
                    fillScreen = false,
                )
            }
        } else {
            items(versions, key = { it.id }) { version ->
                MindCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = version.label,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!version.adapterPath.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = version.adapterPath,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        if (version.active) {
                            StatusPill(text = "Active", color = EmeraldHealthy)
                        } else {
                            ActionButton(
                                label = "Activate",
                                accent = CyanAccent,
                                onClick = { MindUiBridge.activateVersion(version.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricChip(
                            label = "Eval",
                            value = formatPercent(version.evalScore),
                            color = CyanAccent,
                            modifier = Modifier.weight(1f),
                        )
                        MetricChip(
                            label = "Echo",
                            value = formatPercent(version.echoRate),
                            color = if (version.echoRate <= 0.05f) EmeraldHealthy else CoralAlert,
                            modifier = Modifier.weight(1f),
                        )
                        MetricChip(
                            label = "Tools",
                            value = formatPercent(version.toolAccuracy),
                            color = if (version.toolAccuracy >= 0.80f) EmeraldHealthy else AmberWarning,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourcesTab(resources: ResourceStatusUi) {
    val usedRamMb = (resources.totalRamMb - resources.availRamMb).coerceAtLeast(0L)
    val ramFraction = if (resources.totalRamMb > 0L) {
        (usedRamMb.toFloat() / resources.totalRamMb.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val healthColor = when (resources.healthLevel.uppercase(Locale.US)) {
        "NORMAL", "OK", "HEALTHY" -> EmeraldHealthy
        "WARM", "THROTTLED" -> AmberWarning
        else -> CoralAlert
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "power-thermal") {
            MindCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle("Power & Thermals")
                    StatusPill(text = resources.healthLevel, color = healthColor)
                }
                Spacer(Modifier.height(10.dp))
                MetricRow(
                    label = "Battery",
                    value = "${resources.batteryPct}% (${if (resources.charging) "Charging" else "Discharging"})",
                    valueColor = if (resources.batteryPct >= 25 || resources.charging) EmeraldHealthy else CoralAlert,
                )
                MetricRow(
                    label = "Battery Temp",
                    value = String.format(Locale.US, "%.1f °C", resources.batteryTempC),
                    valueColor = if (resources.batteryTempC < 40f) TextPrimary else AmberWarning,
                )
                MetricRow(
                    label = "Max CPU Temp",
                    value = String.format(Locale.US, "%.1f °C", resources.maxCpuTempC),
                    valueColor = if (resources.maxCpuTempC < 65f) TextPrimary else CoralAlert,
                )
            }
        }

        item(key = "memory") {
            MindCard {
                SectionTitle("Memory (RAM)")
                Spacer(Modifier.height(8.dp))
                MetricRow(
                    label = "Available / Total",
                    value = "${resources.availRamMb} / ${resources.totalRamMb} MB",
                    valueColor = CyanAccent,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { ramFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        ramFraction >= 0.85f -> CoralAlert
                        ramFraction >= 0.70f -> AmberWarning
                        else -> CyanAccent
                    },
                    trackColor = SurfaceElevated,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${(ramFraction * 100f).roundToInt()}% allocated ($usedRamMb MB in use)",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        item(key = "cpu-clusters") {
            MindCard {
                SectionTitle("CPU Clusters")
                Spacer(Modifier.height(8.dp))
                MetricRow(
                    label = "Big Cores (cpu4-7)",
                    value = "${resources.bigCpuMhz} MHz",
                    valueColor = CyanAccent,
                )
                MetricRow(
                    label = "Little Cores (cpu0-3)",
                    value = "${resources.littleCpuMhz} MHz",
                    valueColor = EmeraldHealthy,
                )
            }
        }

        item(key = "sensors-reflection") {
            MindCard {
                SectionTitle("Sensors & Reflection")
                Spacer(Modifier.height(8.dp))
                MetricRow(
                    label = "Ambient Light",
                    value = String.format(Locale.US, "%.1f lux", resources.lux),
                )
                MetricRow(
                    label = "Proximity",
                    value = if (resources.nearProximity) "Near" else "Clear",
                    valueColor = if (resources.nearProximity) AmberWarning else EmeraldHealthy,
                )
                MetricRow(
                    label = "Episodes Reflected",
                    value = resources.episodeCount.toString(),
                    valueColor = CyanAccent,
                )
                MetricRow(
                    label = "Last Reflection",
                    value = if (resources.lastReflectionMs > 0L) {
                        formatTimestamp(resources.lastReflectionMs)
                    } else {
                        "Never"
                    },
                )
            }
        }
    }
}

@Composable
private fun MindCard(
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (dimmed) SurfaceCard.copy(alpha = 0.55f) else SurfaceCard.copy(alpha = 0.85f),
        ),
        border = BorderStroke(1.dp, Jx.Line),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    subtitle: String,
    fillScreen: Boolean = true,
) {
    Box(
        modifier = if (fillScreen) {
            Modifier
                .fillMaxSize()
                .padding(24.dp)
        } else {
            Modifier.fillMaxWidth()
        },
        contentAlignment = Alignment.Center,
    ) {
        MindCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HudLabel(text, color = CyanAccent, size = 11)
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = TextSecondary, fontSize = 13.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = label, color = TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun journalBadgeColor(type: String, undone: Boolean): Color {
    if (undone) return TextSecondary
    return when (type.lowercase(Locale.US)) {
        "fact" -> CyanAccent
        "vocab", "correction" -> EmeraldHealthy
        "shortcut" -> AmberWarning
        "rollback", "undo", "error" -> CoralAlert
        else -> CyanAccent
    }
}

private fun formatTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return "—"
    val formatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestampMs))
}

private fun formatPercent(fraction: Float): String {
    val pct = if (fraction <= 1f) fraction * 100f else fraction
    return String.format(Locale.US, "%.1f%%", pct)
}
