package com.diptych.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import com.diptych.app.net.ServerMsg

/** One cover on the shelf: the game and what Diptych can honestly say about it. */
data class ShelfGame(val entry: GameEntry, val status: StatusInfo)

/** The catalog with each game's status. A live welcome only counts for the game it names. */
fun shelfGames(seen: Map<String, SeenRecord>, live: ServerMsg.Welcome?): List<ShelfGame> =
    CATALOG.map { e -> ShelfGame(e, statusOf(e, seen[e.id], live?.takeIf { it.gameId == e.id })) }

/** The info row's main button. SOON has none. */
enum class MainAction(val label: String) { Play("Play"), SetUp("Set up"), Update("Update") }

fun mainActionFor(status: GameStatus): MainAction? = when (status) {
    GameStatus.Ready -> MainAction.Play
    GameStatus.SetUp -> MainAction.SetUp
    GameStatus.Update -> MainAction.Update
    GameStatus.Soon -> null
}

fun readyLine(games: List<ShelfGame>): String =
    "${games.count { it.status.status == GameStatus.Ready }} of ${games.size} ready"

/** [version] is GameNative's installed versionName, or null when it isn't installed. */
fun gameNativeLine(version: String?): String = if (version == null) "GameNative not found" else "GameNative $version"

/** Android doesn't let Diptych read AYN's Focus Lock, so the shelf never pretends to know it. */
const val FOCUS_LOCK_LINE = "Focus Lock: check"

/**
 * The Shelf (diptych-v4, left board): a status line, the covers in a row with the selected one enlarged, and the
 * selected game's line with its main button. Play, Set up and Update are [onMain]'s to carry out.
 */
@Composable
fun ShelfScreen(
    games: List<ShelfGame>,
    selectedId: String,
    onSelect: (String) -> Unit,
    gameNativeVersion: String?,
    artworkEnabled: Boolean,
    onMain: (GameEntry, MainAction) -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
) {
    val selected = games.firstOrNull { it.entry.id == selectedId } ?: games.first()
    Column(modifier.fillMaxSize().background(ShellColors.Ground)) {
        StatusLine(games, gameNativeVersion)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            games.forEach { g ->
                val id = g.entry.id
                CoverTile(
                    g.entry,
                    selected = id == selected.entry.id,
                    statusInfo = g.status,
                    artworkEnabled = artworkEnabled,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Select") { onSelect(id) },
                    imageLoader = imageLoader,
                )
            }
        }
        InfoRow(selected, onMain)
    }
}

@Composable
private fun StatusLine(games: List<ShelfGame>, gameNativeVersion: String?) {
    val dot = SpanStyle(color = ShellColors.Muted)
    Text(
        buildAnnotatedString {
            append(readyLine(games))
            withStyle(dot) { append("  ·  ") }
            withStyle(SpanStyle(color = if (gameNativeVersion != null) ShellColors.Ok else ShellColors.Amber)) {
                append(gameNativeLine(gameNativeVersion))
            }
            withStyle(dot) { append("  ·  ") }
            withStyle(SpanStyle(color = ShellColors.Amber)) { append(FOCUS_LOCK_LINE) }
        },
        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        color = ShellColors.Muted,
        style = bodyText(12.sp),
        maxLines = 1,
    )
}

@Composable
private fun InfoRow(game: ShelfGame, onMain: (GameEntry, MainAction) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val action = mainActionFor(game.status.status)
    Row(
        Modifier
            .padding(start = 14.dp, end = 14.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(ShellColors.Surface)
            .border(1.dp, ShellColors.Line, shape)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(game.entry.title, color = ShellColors.Ink, style = displayText(17.sp))
            Text(game.entry.tagline, color = ShellColors.Muted, style = bodyText(12.sp), modifier = Modifier.padding(top = 2.dp))
            if (game.status.status == GameStatus.Update) {
                Text(game.status.reason, color = ShellColors.Amber, style = bodyText(12.sp), modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        if (action != null) {
            ShellButton(action.label, primary = true) { onMain(game.entry, action) }
        } else {
            Text(game.status.reason, color = ShellColors.Muted, style = bodyText(12.sp), modifier = Modifier.padding(end = 6.dp))
        }
    }
}

/** A pill button: brass fill ([primary]) or a line outline. The pill is drawn inside a 48 dp-high touch target. */
@Composable
internal fun ShellButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            Modifier
                .then(
                    if (primary) Modifier.background(ShellColors.Brass, CircleShape)
                    else Modifier.border(1.dp, ShellColors.Line, CircleShape),
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = if (primary) ShellColors.OnBrass else ShellColors.Ink,
            style = bodyText(13.sp).copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

internal fun bodyText(size: TextUnit) = TextStyle(fontFamily = ShellType.body, fontSize = size)

internal fun displayText(size: TextUnit) =
    TextStyle(fontFamily = ShellType.display, fontWeight = FontWeight.Bold, fontSize = size)
