package com.diptych.app.kh1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
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
import com.diptych.app.kh1.ACT_NOT_ON_SHORTCUT
import com.diptych.app.kh1.LearnedSpell
import com.diptych.app.kh1.MapView
import com.diptych.app.kh1.PartyMember
import com.diptych.app.kh1.actNeedsMp

enum class ConnectionLabel { Connected, Waiting, Unsupported }

enum class Kh1Tab { Map, MagicItems, Party, Journal }

/**
 * The KH1 companion screen (bottom display). Shows a centered banner while [connection] is not Connected, or before the
 * first [view] arrives. When [onCastShortcut] is supplied, shortcut spells and target picking cast via `kh1.pressSequence`.
 */
@Composable
fun MapScreen(
    view: MapView?,
    connection: ConnectionLabel,
    onReport: () -> Unit,
    initialTab: Kh1Tab = Kh1Tab.Map,
    pendingSpellBit: Int? = null,
    actionNote: String? = null,
    onCastShortcut: ((spellBit: Int, targetIdx: Int?) -> Unit)? = null,
    initialTargetPickerSpell: LearnedSpell? = null,
) {
    Box(Modifier.fillMaxSize().background(Kh1Colors.Ground)) {
        when {
            connection == ConnectionLabel.Unsupported -> Banner("This KH1 build isn't supported yet (needs Steam 1.0.0.2)")
            connection == ConnectionLabel.Waiting || view == null -> Banner("Waiting for Kingdom Hearts…")
            else -> MapLayout(
                view = view,
                onReport = onReport,
                initialTab = initialTab,
                pendingSpellBit = pendingSpellBit,
                actionNote = actionNote,
                onCastShortcut = onCastShortcut,
                initialTargetPickerSpell = initialTargetPickerSpell,
            )
        }
    }
}

@Composable
private fun MapLayout(
    view: MapView,
    onReport: () -> Unit,
    initialTab: Kh1Tab,
    pendingSpellBit: Int?,
    actionNote: String?,
    onCastShortcut: ((spellBit: Int, targetIdx: Int?) -> Unit)?,
    initialTargetPickerSpell: LearnedSpell?,
) {
    var activeTab by remember { mutableStateOf(initialTab) }
    var showSpoilers by remember { mutableStateOf(true) }
    var pickingSpell by remember { mutableStateOf(initialTargetPickerSpell) }

    LaunchedEffect(view.canAct, pendingSpellBit) {
        if (!view.canAct || pendingSpellBit != null) pickingSpell = null
    }

    val triggerSpell: (LearnedSpell) -> Unit = { spell ->
        if (onCastShortcut != null && spell.actionReason == null && pendingSpellBit == null) {
            if (spell.needsTarget) pickingSpell = spell else onCastShortcut(spell.bit, null)
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
                        pendingSpellBit = pendingSpellBit,
                        actionNote = actionNote,
                        pickingSpell = pickingSpell,
                        onCast = if (onCastShortcut != null) triggerSpell else null,
                        onReport = onReport,
                    )
                    Kh1Tab.MagicItems -> MagicItemsTabContent(
                        view = view,
                        pendingSpellBit = pendingSpellBit,
                        actionNote = actionNote,
                        pickingSpell = pickingSpell,
                        onCast = if (onCastShortcut != null) triggerSpell else null,
                    )
                    Kh1Tab.Party -> PartyTabContent(view = view, onReport = onReport)
                    Kh1Tab.Journal -> JournalTabContent(view = view)
                }
            }
            Tabs(activeTab = activeTab, onSelect = { activeTab = it })
        }
        val targetSpell = pickingSpell
        if (targetSpell != null && onCastShortcut != null) {
            TargetPickerOverlay(
                spell = targetSpell,
                party = view.party,
                onPick = { idx ->
                    pickingSpell = null
                    onCastShortcut(targetSpell.bit, idx)
                },
                onDismiss = { pickingSpell = null },
            )
        }
    }
}

@Composable
private fun MapTabContent(
    view: MapView,
    showSpoilers: Boolean,
    pendingSpellBit: Int?,
    actionNote: String?,
    pickingSpell: LearnedSpell?,
    onCast: ((LearnedSpell) -> Unit)?,
    onReport: () -> Unit,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoomMap(view, Modifier.weight(1f).fillMaxSize(), showSpoilers = showSpoilers)
        Column(Modifier.width(196.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PartyPanel(view.party, Modifier.fillMaxWidth())
            QuickMagicPanel(
                view = view,
                pendingSpellBit = pendingSpellBit,
                actionNote = actionNote,
                pickingSpell = pickingSpell,
                onCast = onCast,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
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
            } else if (activeTab != Kh1Tab.Map && view.busyReason != null) {
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

/** Sora's stats block is not watched in this world (watch budget) or could not be read. */
internal const val NOT_READ_HERE = "Not shown in this world yet"

internal const val ITEMS_COMING_NEXT = "Item menu navigation coming after PC check"

/** The status line under the quick panel: busy safety reason first, else transient action feedback, else "Game ready". */
internal fun statusLine(view: MapView, actionNote: String? = null): String? =
    view.busyReason ?: actionNote ?: if (view.canAct) "Game ready" else null

/** Whether [member] can be chosen safely in the target picker (HP is known and > 0 until PC check settles KO order). */
internal fun isTargetSelectable(member: PartyMember): Boolean = member.hp != null && member.hp > 0

/** Index of the lowest-HP living damaged party member (`0 < hp < maxHp`), or null if none qualifies. */
internal fun lowestHpTargetIndex(party: List<PartyMember>): Int? {
    val damaged = party.indices.mapNotNull { i ->
        if (!isTargetSelectable(party[i])) return@mapNotNull null
        val f = barFraction(party[i].hp, party[i].maxHp) ?: return@mapNotNull null
        if (f < 1f) i to f else null
    }
    return damaged.minByOrNull { it.second }?.first
}

/** Subtitle text inside a Magic tile, matching `Magic.dc.html`. */
internal fun spellSubtext(
    spell: LearnedSpell,
    mp: Int?,
    picking: Boolean = false,
    pending: Boolean = false,
): String = when {
    pending -> "${spell.mpCost} MP · casting…"
    picking -> "${spell.mpCost} MP · picking"
    spell.actionReason == actNeedsMp(spell.mpCost) || (mp != null && mp < spell.mpCost) -> "needs ${spell.mpCost} MP"
    spell.actionReason == ACT_NOT_ON_SHORTCUT -> "no shortcut"
    else -> "${spell.mpCost} MP"
}

/** The 3 shortcut slots mapped to learned spells (or null if empty/unlearned); null when shortcuts are unreadable. */
internal fun shortcutSpells(view: MapView): List<Pair<Int, LearnedSpell?>>? {
    val slots = view.actions.shortcuts ?: return null
    return slots.mapIndexed { idx, bit ->
        idx to bit?.let { b -> view.sora.spells.firstOrNull { it.bit == b } }
    }
}

@Composable
private fun QuickMagicPanel(
    view: MapView,
    pendingSpellBit: Int?,
    actionNote: String?,
    pickingSpell: LearnedSpell?,
    onCast: ((LearnedSpell) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val soraMp = view.party.firstOrNull()?.mp
    val shortcuts = shortcutSpells(view)
    Column(
        modifier.kh1Panel().padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "QUICK MAGIC",
            color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp,
            letterSpacing = 1.5.sp,
        )
        when {
            shortcuts == null -> DashedPanelNote("Can't read shortcuts", Modifier.weight(1f))
            shortcuts.all { it.second == null } -> DashedPanelNote("No shortcut spells set", Modifier.weight(1f))
            else -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((slot, spell) in shortcuts) {
                    if (spell == null) {
                        val shape = RoundedCornerShape(9.dp)
                        Row(
                            Modifier.weight(1f).fillMaxWidth()
                                .clip(shape)
                                .background(Kh1Colors.Room, shape)
                                .dashedBorder(1.dp, Kh1Colors.LockedRim, 9.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Slot ${slot + 1} · Empty",
                                color = Kh1Colors.Dim, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        val isPicking = pickingSpell?.bit == spell.bit
                        val isPending = pendingSpellBit == spell.bit
                        val enabled = onCast != null && spell.actionReason == null && pendingSpellBit == null
                        val shape = RoundedCornerShape(9.dp)
                        val borderColor = when {
                            isPicking || isPending -> Kh1Colors.Gold
                            enabled -> Kh1Colors.ActionRim
                            else -> Kh1Colors.LockedRim
                        }
                        val fillColor = if (enabled || isPicking || isPending) Kh1Colors.ActionFill else Kh1Colors.Room
                        val rowMod = Modifier.weight(1f).fillMaxWidth()
                            .clip(shape)
                            .background(fillColor, shape)
                            .let { m ->
                                if (enabled || isPicking || isPending) {
                                    m.border(if (isPicking || isPending) 2.5.dp else 1.5.dp, borderColor, shape)
                                } else {
                                    m.dashedBorder(1.5.dp, borderColor, 9.dp)
                                }
                            }
                            .let { m -> if (enabled) m.clickable(role = Role.Button) { onCast?.invoke(spell) } else m }
                            .padding(horizontal = 8.dp)
                        Row(
                            rowMod,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                spell.name,
                                color = if (enabled || isPicking || isPending) Color.White else Kh1Colors.Dim,
                                fontFamily = Fredoka, fontWeight = FontWeight.W600, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                spellSubtext(spell, soraMp, isPicking, isPending),
                                color = when {
                                    isPicking || isPending -> Kh1Colors.YouCount
                                    enabled -> Kh1Colors.ActionSub
                                    else -> Kh1Colors.Dim
                                },
                                fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        statusLine(view, actionNote)?.let { line ->
            Text(
                line,
                color = if (view.busyReason == null && actionNote == null && view.canAct) Kh1Colors.Muted else Kh1Colors.Gold,
                fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 12.sp, lineHeight = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashedPanelNote(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth()
            .background(Kh1Colors.Room, RoundedCornerShape(10.dp))
            .dashedBorder(1.5.dp, Kh1Colors.LockedRim, 10.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Kh1Colors.Dim, fontFamily = Fredoka, fontWeight = FontWeight.W600, fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MagicItemsTabContent(
    view: MapView,
    pendingSpellBit: Int?,
    actionNote: String?,
    pickingSpell: LearnedSpell?,
    onCast: ((LearnedSpell) -> Unit)?,
) {
    val soraMp = view.party.firstOrNull()?.mp
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoPanel("MAGIC · TAP TO CAST", Modifier.weight(1f)) {
            if (!view.sora.known) {
                EmptyNote(NOT_READ_HERE)
            } else if (view.sora.spells.isEmpty()) {
                EmptyNote("No spells learned yet")
            } else {
                for (rowSpells in view.sora.spells.chunked(2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (spell in rowSpells) {
                            val isPicking = pickingSpell?.bit == spell.bit
                            val isPending = pendingSpellBit == spell.bit
                            val enabled = onCast != null && spell.actionReason == null && pendingSpellBit == null
                            ActionTile(
                                title = spell.name,
                                sub = spellSubtext(spell, soraMp, isPicking, isPending),
                                enabled = enabled,
                                highlighted = isPicking || isPending,
                                onClick = { onCast?.invoke(spell) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowSpells.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            actionNote?.let {
                Text(it, color = Kh1Colors.Gold, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 12.sp)
            }
        }
        InfoPanel("ITEMS · TAP TO USE", Modifier.weight(1f)) {
            if (!view.sora.known) {
                EmptyNote(NOT_READ_HERE)
            } else if (view.sora.items.isEmpty()) {
                EmptyNote("No items equipped")
            } else {
                for (rowItems in view.sora.items.chunked(2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in rowItems) {
                            InfoTile(item.name, if (item.count <= 0) "none left" else "× ${item.count}", Modifier.weight(1f))
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            EmptyNote(ITEMS_COMING_NEXT)
        }
    }
}

@Composable
private fun ActionTile(
    title: String,
    sub: String,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val active = enabled || highlighted
    val mod = modifier.height(52.dp)
        .clip(shape)
        .background(if (active) Kh1Colors.ActionFill else Kh1Colors.Room, shape)
        .let { m ->
            if (highlighted) m.border(3.dp, Kh1Colors.Gold, shape)
            else if (enabled) m.border(2.dp, Kh1Colors.ActionRim, shape)
            else m.dashedBorder(2.dp, Kh1Colors.LockedRim, 12.dp)
        }
        .let { m -> if (enabled) m.clickable(role = Role.Button, onClick = onClick) else m }
    Column(
        mod,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            color = if (active) Color.White else Kh1Colors.Dim,
            fontFamily = Fredoka, fontWeight = if (highlighted) FontWeight.W700 else FontWeight.W600, fontSize = 15.sp,
            lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            sub,
            color = when {
                highlighted -> Kh1Colors.YouCount
                enabled -> Kh1Colors.ActionSub
                else -> Kh1Colors.Dim
            },
            fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp,
            lineHeight = 13.sp, maxLines = 1,
        )
    }
}

@Composable
private fun TargetPickerOverlay(
    spell: LearnedSpell,
    party: List<PartyMember>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val lowestIdx = lowestHpTargetIndex(party)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Kh1Colors.Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val sheetShape = RoundedCornerShape(18.dp)
        Column(
            Modifier.align(Alignment.BottomCenter)
                .padding(start = 48.dp, end = 48.dp, bottom = 72.dp)
                .fillMaxWidth()
                .clip(sheetShape)
                .background(Kh1Colors.Panel, sheetShape)
                .border(3.dp, Kh1Colors.Gold, sheetShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "${spell.name} · who needs it?",
                    color = Kh1Colors.Gold, fontFamily = Fredoka, fontWeight = FontWeight.W700, fontSize = 19.sp,
                )
                Text(
                    "runs the game's picker on top",
                    color = Kh1Colors.Muted, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((idx, member) in party.withIndex()) {
                    val selectable = isTargetSelectable(member)
                    val isLowest = selectable && idx == lowestIdx
                    val frac = barFraction(member.hp, member.maxHp)
                    val btnShape = RoundedCornerShape(14.dp)
                    val rimColor = when {
                        !selectable -> Kh1Colors.LockedRim
                        isLowest -> Kh1Colors.HpLow
                        else -> Kh1Colors.ActionRim
                    }
                    val colMod = Modifier.weight(1f).height(64.dp)
                        .clip(btnShape)
                        .background(if (selectable) Kh1Colors.ActionFill else Kh1Colors.Room, btnShape)
                        .let { m ->
                            if (selectable) m.border(if (isLowest) 3.dp else 2.dp, rimColor, btnShape)
                            else m.dashedBorder(2.dp, rimColor, 14.dp)
                        }
                        .let { m -> if (selectable) m.clickable(role = Role.Button) { onPick(idx) } else m }
                    Column(
                        colMod,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            member.name,
                            color = if (selectable) Color.White else Kh1Colors.Dim,
                            fontFamily = Fredoka,
                            fontWeight = if (isLowest) FontWeight.W700 else FontWeight.W600, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val hpLine = when {
                            member.hp == null -> "HP unknown"
                            member.hp <= 0 -> "KO'd"
                            isLowest -> "${member.hp} / ${member.maxHp ?: "--"} · lowest"
                            else -> "${member.hp} / ${member.maxHp ?: "--"}"
                        }
                        Text(
                            hpLine,
                            color = when {
                                !selectable -> Kh1Colors.Dim
                                isLowest -> Kh1Colors.HpLow
                                else -> hpColor(frac)
                            },
                            fontFamily = Nunito, fontWeight = FontWeight.W800, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                    .clickable(role = Role.Button, onClick = onDismiss)
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
private fun InfoPanel(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().kh1Panel().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            color = Kh1Colors.Muted, fontFamily = Fredoka, fontWeight = FontWeight.W500, fontSize = 12.sp,
            letterSpacing = 1.5.sp,
        )
        content()
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = Kh1Colors.Dim, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 13.sp)
}

/** A read-only tile in the existing disabled style (dashed rim, dim text). Not clickable. */
@Composable
private fun InfoTile(title: String, sub: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.height(52.dp)
            .clip(shape)
            .background(Kh1Colors.Room, shape)
            .dashedBorder(2.dp, Kh1Colors.LockedRim, 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            color = Kh1Colors.Dim, fontFamily = Fredoka, fontWeight = FontWeight.W600, fontSize = 15.sp,
            lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            sub,
            color = Kh1Colors.Dim, fontFamily = Nunito, fontWeight = FontWeight.W700, fontSize = 11.sp,
            lineHeight = 13.sp, maxLines = 1,
        )
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
            StatRow("Keyblade", sora.weapon ?: "--", Kh1Colors.Text)
            StatRow("Strength", sora.strength?.toString() ?: "--", Kh1Colors.Text)
            StatRow("Defense", sora.defense?.toString() ?: "--", Kh1Colors.Text)
            StatRow("Ability Points (AP)", sora.ap?.toString() ?: "--", Kh1Colors.Mp)
            StatRow("Spells Learned", if (sora.known) "${sora.spells.size} / 7" else "--", Kh1Colors.Gold)
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
            "JIMINY'S JOURNAL · ${view.worlds.size} WORLDS · $totalAllChests TREASURES",
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
