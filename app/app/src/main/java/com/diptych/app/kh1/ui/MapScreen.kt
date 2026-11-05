package com.diptych.app.kh1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diptych.app.kh1.LearnedSpell
import com.diptych.app.kh1.MapView
import com.diptych.app.kh1.StockItem

enum class ConnectionLabel { Connected, Waiting, Unsupported }

enum class Kh1Tab { Map, MagicItems, Party, Journal }

/**
 * The KH1 companion screen (bottom display). Shows a centered banner while [connection] is not Connected, or before the
 * first [view] arrives.
 */
@Composable
fun MapScreen(
    view: MapView?,
    connection: ConnectionLabel,
    onReport: () -> Unit,
    onAction: (mask: Int, durationMs: Int) -> Unit = { _, _ -> },
    initialTab: Kh1Tab = Kh1Tab.Map,
) {
    Box(Modifier.fillMaxSize().background(Kh1Colors.Ground)) {
        when {
            connection == ConnectionLabel.Unsupported -> Banner("This KH1 build isn't supported yet (needs Steam 1.0.0.2)")
            connection == ConnectionLabel.Waiting || view == null -> Banner("Waiting for Kingdom Hearts…")
            else -> MapLayout(view, onReport, onAction, initialTab)
        }
    }
}

@Composable
private fun MapLayout(
    view: MapView,
    onReport: () -> Unit,
    onAction: (mask: Int, durationMs: Int) -> Unit,
    initialTab: Kh1Tab,
) {
    var activeTab by remember { mutableStateOf(initialTab) }
    var showSpoilers by remember { mutableStateOf(true) }
    var pickingTargetFor by remember { mutableStateOf<String?>(null) }

    fun triggerEntry(name: String, needsTarget: Boolean) {
        if (!view.canAct) return
        if (needsTarget && view.party.isNotEmpty()) {
            pickingTargetFor = name
            // Open shortcut/command menu on top screen while user picks target
            onAction(0x10, 150)
        } else {
            pickingTargetFor = null
            onAction(0x14, 240)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(
                view = view,
                activeTab = activeTab,
                showSpoilers = showSpoilers,
                onToggleSpoilers = { showSpoilers = !showSpoilers },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (activeTab) {
                    Kh1Tab.Map -> MapTabContent(
                        view = view,
                        showSpoilers = showSpoilers,
                        onReport = onReport,
                        onQuickTrigger = ::triggerEntry,
                    )
                    Kh1Tab.MagicItems -> MagicItemsTabContent(
                        view = view,
                        pickingTargetFor = pickingTargetFor,
                        onTrigger = ::triggerEntry,
                    )
                    Kh1Tab.Party -> PartyTabContent(view = view, onReport = onReport)
                    Kh1Tab.Journal -> JournalTabContent(view = view)
                }
            }
            Tabs(activeTab = activeTab, onSelect = {
                activeTab = it
                pickingTargetFor = null
            })
        }

        val targetAction = pickingTargetFor
        if (targetAction != null) {
            TargetPickerOverlay(
                actionName = targetAction,
                view = view,
                onPickMember = {
                    pickingTargetFor = null
                    onAction(0x14, 240)
                },
                onCancel = { pickingTargetFor = null },
            )
        }
    }
}

@Composable
private fun MapTabContent(
    view: MapView,
    showSpoilers: Boolean,
    onReport: () -> Unit,
    onQuickTrigger: (name: String, needsTarget: Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoomMap(view, Modifier.weight(1f).fillMaxSize(), showSpoilers = showSpoilers)
        Column(Modifier.width(196.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PartyPanel(view.party, Modifier.fillMaxWidth())
            QuickMagicPanel(view, onQuickTrigger, Modifier.weight(1f).fillMaxWidth())
            ReportButton(onReport)
        }
    }
}

@Composable
private fun Header(
    view: MapView,
    activeTab: Kh1Tab,
    showSpoilers: Boolean,
    onToggleSpoilers: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().kh1Panel().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    append(view.worldName.uppercase())
                    withStyle(SpanStyle(color = Kh1Colors.Muted, fontWeight = FontWeight.W600)) {
                        append(" · ${view.roomName.uppercase()}")
                    }
                },
                color = Kh1Colors.Text, fontFamily = Fredoka, fontWeight = FontWeight.W700, fontSize = 17.sp,
                letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val hint = view.nextHint
            if (activeTab == Kh1Tab.Map && showSpoilers && !hint.isNullOrBlank()) {
                Text(
                    "Next: $hint",
                    color = Kh1Colors.Gold, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else if (!view.canAct && view.busyReason != null) {
                Text(
                    view.busyReason,
                    color = Kh1Colors.Muted, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (activeTab == Kh1Tab.MagicItems) {
            val sora = view.party.firstOrNull()
            if (sora != null) {
                val hpFrac = barFraction(sora.hp, sora.maxHp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            append("${sora.name} ")
                            withStyle(SpanStyle(color = hpColor(hpFrac))) {
                                append("${sora.hp ?: "--"}/${sora.maxHp ?: "--"}")
                            }
                        },
                        color = Kh1Colors.Text, fontFamily = Nunito, fontWeight = FontWeight.W800, fontSize = 13.sp,
                    )
                    Text(
                        buildAnnotatedString {
                            append("MP ")
                            withStyle(SpanStyle(color = Kh1Colors.Mp)) {
                                append("${sora.mp ?: "--"}/${sora.maxMp ?: "--"}")
                            }
                        },
                        color = Kh1Colors.Text, fontFamily = Nunito, fontWeight = FontWeight.W800, fontSize = 13.sp,
                    )
                }
            }
        }
        if (activeTab == Kh1Tab.Map) {
            SpoilerToggleButton(showSpoilers = showSpoilers, onToggle = onToggleSpoilers)
        }
    }
}

@Composable
private fun SpoilerToggleButton(showSpoilers: Boolean, onToggle: () -> Unit) {
    val label = if (showSpoilers) "Hide spoilers" else "Show spoilers"
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(44.dp)
            .clip(shape)
            .background(Kh1Colors.Ground, shape)
            .border(2.dp, if (showSpoilers) Kh1Colors.Rim else Kh1Colors.LockedRim, shape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        val strokeColor = if (showSpoilers) Kh1Colors.Text else Kh1Colors.Dim
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val eye = Path().apply {
                moveTo(w * 0.08f, h * 0.5f)
                cubicTo(w * 0.25f, h * 0.2f, w * 0.75f, h * 0.2f, w * 0.92f, h * 0.5f)
                cubicTo(w * 0.75f, h * 0.8f, w * 0.25f, h * 0.8f, w * 0.08f, h * 0.5f)
                close()
            }
            drawPath(eye, color = strokeColor, style = stroke)
            drawCircle(color = strokeColor, radius = w * 0.125f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
            if (!showSpoilers) {
                drawLine(
                    color = Kh1Colors.Gold,
                    start = Offset(w * 0.15f, h * 0.85f),
                    end = Offset(w * 0.85f, h * 0.15f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private data class QuickEntry(
    val label: String,
    val needsTarget: Boolean,
    val enabled: Boolean,
)

@Composable
private fun QuickMagicPanel(
    view: MapView,
    onTrigger: (name: String, needsTarget: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val soraMp = view.party.firstOrNull()?.mp
    val entries = remember(view.sora, soraMp, view.canAct) {
        val preferredOrder = listOf("cure", "fire", "thunder", "blizzard", "aero", "gravity", "stop")
        val sortedSpells = view.sora.spells.sortedBy { preferredOrder.indexOf(it.key).let { idx -> if (idx < 0) 99 else idx } }
        val firstItem = view.sora.items.firstOrNull()
        val chosen = mutableListOf<QuickEntry>()
        val spellLimit = if (firstItem != null) 3 else 4
        for (s in sortedSpells.take(spellLimit)) {
            val enoughMp = soraMp == null || soraMp >= s.mpCost
            chosen += QuickEntry(s.name, s.needsTarget, view.canAct && enoughMp)
        }
        if (firstItem != null && chosen.size < 4) {
            chosen += QuickEntry("${firstItem.name} ×${firstItem.count}", firstItem.needsTarget, view.canAct && firstItem.count > 0)
        }
        val fallbacks = listOf("Cure" to true, "Fire" to false, "Thunder" to false, "Potion" to true)
        for ((name, target) in fallbacks) {
            if (chosen.size >= 4) break
            if (chosen.none { it.label.startsWith(name.take(3)) }) {
                chosen += QuickEntry(name, target, enabled = false)
            }
        }
        chosen.take(4)
    }

    Column(
        modifier.kh1Panel().padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "QUICK MAGIC",
            color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp,
            letterSpacing = 1.5.sp,
        )
        for (rowEntries in entries.chunked(2)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (entry in rowEntries) {
                    QuickButton(
                        entry = entry,
                        onClick = { onTrigger(entry.label.substringBefore(" ×"), entry.needsTarget) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
                if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickButton(entry: QuickEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val borderMod = if (entry.enabled) {
        Modifier.border(2.dp, Kh1Colors.ActionRim, shape)
    } else {
        Modifier.dashedBorder(1.5.dp, Kh1Colors.LockedRim, 10.dp)
    }
    Box(
        modifier
            .clip(shape)
            .background(if (entry.enabled) Kh1Colors.ActionFill else Kh1Colors.Room, shape)
            .then(borderMod)
            .then(if (entry.enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            entry.label,
            color = if (entry.enabled) Color.White else Kh1Colors.Dim,
            fontFamily = Fredoka, fontWeight = FontWeight.W600, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MagicItemsTabContent(
    view: MapView,
    pickingTargetFor: String?,
    onTrigger: (name: String, needsTarget: Boolean) -> Unit,
) {
    val soraMp = view.party.firstOrNull()?.mp
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // MAGIC · TAP TO CAST
        Column(
            Modifier.weight(1f).fillMaxSize().kh1Panel().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "MAGIC · TAP TO CAST",
                color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp,
                letterSpacing = 1.5.sp,
            )
            if (view.sora.spells.isEmpty()) {
                Text(
                    "No spells learned yet",
                    color = Kh1Colors.Dim, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 13.sp,
                )
            } else {
                for (rowSpells in view.sora.spells.chunked(2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (spell in rowSpells) {
                            SpellTile(
                                spell = spell,
                                soraMp = soraMp,
                                canAct = view.canAct,
                                picking = pickingTargetFor == spell.name,
                                onClick = { onTrigger(spell.name, spell.needsTarget) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowSpells.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // ITEMS · TAP TO USE
        Column(
            Modifier.weight(1f).fillMaxSize().kh1Panel().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "ITEMS · TAP TO USE",
                color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp,
                letterSpacing = 1.5.sp,
            )
            if (view.sora.items.isEmpty()) {
                Text(
                    "No usable items in stock",
                    color = Kh1Colors.Dim, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 13.sp,
                )
            } else {
                for (rowItems in view.sora.items.chunked(2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in rowItems) {
                            ItemTile(
                                item = item,
                                canAct = view.canAct,
                                picking = pickingTargetFor == item.name,
                                onClick = { onTrigger(item.name, item.needsTarget) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpellTile(
    spell: LearnedSpell,
    soraMp: Int?,
    canAct: Boolean,
    picking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enoughMp = soraMp == null || soraMp >= spell.mpCost
    val enabled = canAct && enoughMp
    val shape = RoundedCornerShape(12.dp)
    val borderMod = when {
        picking -> Modifier.border(3.dp, Kh1Colors.Gold, shape)
        enabled -> Modifier.border(2.dp, Kh1Colors.ActionRim, shape)
        else -> Modifier.dashedBorder(2.dp, Kh1Colors.LockedRim, 12.dp)
    }
    val subText = when {
        picking -> "${spell.mpCost} MP · picking"
        !enoughMp -> "needs ${spell.mpCost} MP"
        else -> "${spell.mpCost} MP"
    }
    Column(
        modifier.height(52.dp)
            .clip(shape)
            .background(if (enabled || picking) Kh1Colors.ActionFill else Kh1Colors.Room, shape)
            .then(borderMod)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            spell.name,
            color = if (enabled || picking) Color.White else Kh1Colors.Dim,
            fontFamily = Fredoka, fontWeight = if (picking) FontWeight.W700 else FontWeight.W600, fontSize = 15.sp,
            lineHeight = 17.sp, maxLines = 1,
        )
        Text(
            subText,
            color = when {
                picking -> Kh1Colors.YouCount
                enabled -> Kh1Colors.ActionSub
                else -> Kh1Colors.Dim
            },
            fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1,
        )
    }
}

@Composable
private fun ItemTile(
    item: StockItem,
    canAct: Boolean,
    picking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = canAct && item.count > 0
    val shape = RoundedCornerShape(12.dp)
    val borderMod = when {
        picking -> Modifier.border(3.dp, Kh1Colors.Gold, shape)
        enabled -> Modifier.border(2.dp, Kh1Colors.ActionRim, shape)
        else -> Modifier.dashedBorder(2.dp, Kh1Colors.LockedRim, 12.dp)
    }
    val subText = when {
        picking -> "× ${item.count} · picking"
        item.count <= 0 -> "none left"
        item.equipped -> "× ${item.count} · eq"
        else -> "× ${item.count}"
    }
    Column(
        modifier.height(52.dp)
            .clip(shape)
            .background(if (enabled || picking) Kh1Colors.ActionFill else Kh1Colors.Room, shape)
            .then(borderMod)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            item.name,
            color = if (enabled || picking) Color.White else Kh1Colors.Dim,
            fontFamily = Fredoka, fontWeight = if (picking) FontWeight.W700 else FontWeight.W600, fontSize = 15.sp,
            lineHeight = 17.sp, maxLines = 1,
        )
        Text(
            subText,
            color = when {
                picking -> Kh1Colors.YouCount
                enabled -> Kh1Colors.ActionSub
                else -> Kh1Colors.Dim
            },
            fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1,
        )
    }
}

@Composable
private fun TargetPickerOverlay(
    actionName: String,
    view: MapView,
    onPickMember: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val lowestIdx = view.party.indices.minByOrNull { idx ->
        val m = view.party[idx]
        barFraction(m.hp, m.maxHp) ?: 1f
    }
    Box(
        Modifier.fillMaxSize()
            .background(Kh1Colors.Scrim)
            .clickable(onClick = onCancel),
    ) {
        val cardShape = RoundedCornerShape(18.dp)
        Column(
            Modifier.align(Alignment.BottomCenter)
                .padding( start = 48.dp, end = 48.dp, bottom = 74.dp)
                .fillMaxWidth()
                .background(Kh1Colors.Panel, cardShape)
                .border(3.dp, Kh1Colors.Gold, cardShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "$actionName · who needs it?",
                    color = Kh1Colors.Gold, fontFamily = Fredoka, fontWeight = FontWeight.W700, fontSize = 19.sp,
                )
                Text(
                    "the game's own picker is open on the top screen",
                    color = Kh1Colors.Muted, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                view.party.forEachIndexed { idx, m ->
                    val frac = barFraction(m.hp, m.maxHp)
                    val isLowest = idx == lowestIdx && frac != null && frac < 1f
                    val btnShape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.weight(1f).height(64.dp)
                            .clip(btnShape)
                            .background(Kh1Colors.ActionFill, btnShape)
                            .border(if (isLowest) 3.dp else 2.dp, if (isLowest) Kh1Colors.HpLow else Kh1Colors.ActionRim, btnShape)
                            .clickable(role = Role.Button) { onPickMember(idx) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            m.name,
                            color = Color.White, fontFamily = Fredoka,
                            fontWeight = if (isLowest) FontWeight.W700 else FontWeight.W600, fontSize = 16.sp,
                        )
                        Text(
                            buildString {
                                append("${m.hp ?: "--"} / ${m.maxHp ?: "--"}")
                                if (isLowest) append(" · lowest")
                            },
                            color = if (isLowest) Kh1Colors.HpLow else hpColor(frac),
                            fontFamily = Nunito, fontWeight = FontWeight.W800, fontSize = 12.sp,
                        )
                    }
                }
            }
            val cancelShape = RoundedCornerShape(12.dp)
            Box(
                Modifier.align(Alignment.CenterHorizontally)
                    .height(44.dp)
                    .clip(cancelShape)
                    .background(Kh1Colors.Ground, cancelShape)
                    .border(2.dp, Kh1Colors.RoomRim, cancelShape)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Cancel",
                    color = Kh1Colors.TabText, fontFamily = Fredoka, fontWeight = FontWeight.W600, fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun PartyTabContent(view: MapView, onReport: () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PartyPanel(view.party, Modifier.weight(1f).fillMaxWidth())
            ReportButton(onReport)
        }
        Column(
            Modifier.weight(1f).fillMaxSize().kh1Panel().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "SORA · STATS & GEAR",
                color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp,
                letterSpacing = 1.5.sp,
            )
            val sora = view.sora
            StatRow("Level", sora.level?.toString() ?: "--", Kh1Colors.Gold)
            StatRow("Keyblade", sora.weapon ?: "Kingdom Key", Kh1Colors.Text)
            StatRow("Strength", sora.strength?.toString() ?: "--", Kh1Colors.Text)
            StatRow("Defense", sora.defense?.toString() ?: "--", Kh1Colors.Text)
            StatRow("Ability Points (AP)", sora.ap?.toString() ?: "--", Kh1Colors.Mp)
            StatRow("Spells Learned", "${sora.spells.size} / 7", Kh1Colors.Gold)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().background(Kh1Colors.Room, shape).border(1.dp, Kh1Colors.KeyRim, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Kh1Colors.Muted, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 13.sp)
        Text(value, color = valueColor, fontFamily = Fredoka, fontWeight = FontWeight.W600, fontSize = 14.sp)
    }
}

@Composable
private fun JournalTabContent(view: MapView) {
    val totalAllChests = view.worlds.sumOf { it.chestsTotal }
    Column(
        Modifier.fillMaxSize().kh1Panel().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "JIMINY'S JOURNAL · 15 WORLDS · $totalAllChests TREASURES",
            color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp,
            letterSpacing = 1.5.sp,
        )
        for (rowWorlds in view.worlds.chunked(3)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (w in rowWorlds) {
                    val shape = RoundedCornerShape(9.dp)
                    val isHere = w.current
                    Column(
                        Modifier.weight(1f).fillMaxSize()
                            .background(if (isHere) Kh1Colors.You else Kh1Colors.Room, shape)
                            .border(if (isHere) 2.dp else 1.dp, if (isHere) Color.White else Kh1Colors.RoomRim, shape)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            w.name,
                            color = if (isHere) Color.White else Kh1Colors.Text,
                            fontFamily = Nunito, fontWeight = FontWeight.W800, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val chestSummary = when {
                            w.chestsTotal == 0 -> "${w.roomsTotal} rooms"
                            isHere -> "${w.chestsOpened ?: "?"} / ${w.chestsTotal} chests"
                            else -> "${w.roomsTotal} rooms · ${w.chestsTotal} chests"
                        }
                        Text(
                            chestSummary,
                            color = if (isHere) Kh1Colors.YouCount else Kh1Colors.Gold,
                            fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(3 - rowWorlds.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ReportButton(onReport: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onReport),
        contentAlignment = Alignment.Center,
    ) {
        Text("This looks wrong", color = Kh1Colors.Muted, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 12.sp)
    }
}

@Composable
private fun Tabs(activeTab: Kh1Tab, onSelect: (Kh1Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().kh1Panel().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tab("MAP", active = activeTab == Kh1Tab.Map, Modifier.weight(1f)) { onSelect(Kh1Tab.Map) }
        Tab("MAGIC & ITEMS", active = activeTab == Kh1Tab.MagicItems, Modifier.weight(1.3f)) { onSelect(Kh1Tab.MagicItems) }
        Tab("PARTY", active = activeTab == Kh1Tab.Party, Modifier.weight(1f)) { onSelect(Kh1Tab.Party) }
        Tab("JOURNAL", active = activeTab == Kh1Tab.Journal, Modifier.weight(1f)) { onSelect(Kh1Tab.Journal) }
    }
}

@Composable
private fun Tab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) Kh1Colors.You else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            color = if (active) Color.White else Kh1Colors.TabText,
            fontFamily = Fredoka,
            fontWeight = if (active) FontWeight.W700 else FontWeight.W600,
            fontSize = 14.sp,
            letterSpacing = 1.sp,
            lineHeight = 16.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun Banner(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 420.dp).kh1Panel().padding(horizontal = 28.dp, vertical = 22.dp)) {
            Text(
                message, color = Kh1Colors.Text, fontFamily = Fredoka, fontWeight = FontWeight.W600, fontSize = 18.sp,
                letterSpacing = 0.5.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
