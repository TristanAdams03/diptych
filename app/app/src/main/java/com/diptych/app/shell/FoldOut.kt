package com.diptych.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One "Before you play" step. Text between backticks is drawn as code. [linkUrl] opens in the browser. */
data class CheckItem(val text: String, val done: Boolean, val linkLabel: String? = null, val linkUrl: String? = null)

/**
 * "Before you play", ticked only from what Diptych knows. A [seen] handshake proves the mod was imported and that
 * dinput8 loaded (it can't load without the override), so it ticks steps 2-4. The zip step stays open in M1:
 * Diptych doesn't download the zip itself yet, so it can't know.
 */
fun beforeYouPlay(entry: GameEntry, seen: SeenRecord?): List<CheckItem> = listOf(
    CheckItem("Mod zip downloaded", done = false, linkLabel = "Get it from the release page", linkUrl = entry.modAsset),
    CheckItem("In GameNative: KH 1.5+2.5 → Mods → Import", done = seen != null),
    CheckItem("Add `WINEDLLOVERRIDES` = `dinput8=n,b` in Environment", done = seen != null),
    CheckItem("Start ${entry.ribbonName} once; Diptych ticks this off", done = seen != null),
)

private val SEEN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

/** "Mod check": the plugin and build the mod last reported, or that it hasn't reported yet. */
fun modCheckLines(seen: SeenRecord?): List<String> =
    if (seen == null) listOf("Mod not seen yet. Each launch, Diptych and the mod compare versions.")
    else listOf(
        "Plugin ${seen.plugin} · build ${seen.gameBuild}",
        "Last seen ${SEEN_DATE.format(Instant.ofEpochMilli(seen.atMs))}",
    )

/** The left leaf's status tag. */
fun foldOutTag(status: GameStatus): String = when (status) {
    GameStatus.Ready -> "✓ READY"
    GameStatus.SetUp -> "NEEDS SETUP"
    GameStatus.Update -> "UPDATE"
    GameStatus.Soon -> "SOON"
}

/** Splits "a `b` c" into its plain and code runs. */
internal fun withCode(text: String, code: SpanStyle): AnnotatedString = buildAnnotatedString {
    text.split('`').forEachIndexed { i, part -> if (i % 2 == 1) withStyle(code) { append(part) } else append(part) }
}

/**
 * A game's details as two leaves (diptych-v4, right board): Steam hero art, logo, status tag and required build on
 * the left 44%, a 2 dp brass hinge, and "Before you play", "What goes where", "Mod check" and the links on the right.
 */
@Composable
fun FoldOut(
    game: ShelfGame,
    seen: SeenRecord?,
    artworkEnabled: Boolean,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
) {
    val loader = imageLoader ?: ArtLoader.get(LocalContext.current)
    Row(modifier.fillMaxSize().background(ShellColors.Ground)) {
        LeftLeaf(game, artworkEnabled, loader, Modifier.fillMaxWidth(0.44f).fillMaxHeight())
        Box(Modifier.width(2.dp).fillMaxHeight().background(ShellColors.Brass))
        RightLeaf(game, seen, onOpenUrl, Modifier.weight(1f).fillMaxHeight())
    }
}

/** Hero dimmed to 72% brightness (and 90% saturation), as the mockup's `filter: saturate(.9) brightness(.72)`. */
private val HeroDim = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(0.9f)
        timesAssign(ColorMatrix().apply { setToScale(0.72f, 0.72f, 0.72f, 1f) })
    },
)

@Composable
private fun LeftLeaf(game: ShelfGame, artworkEnabled: Boolean, loader: ImageLoader, modifier: Modifier) {
    val e = game.entry
    val accent = Color(e.accent)
    Box(
        modifier
            .background(ShellColors.Raised)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.06f)))),
    ) {
        var logoShown by remember(e.heroAppId) { mutableStateOf(false) }
        if (artworkEnabled) {
            FallbackImage(e.heroAppId, ArtKind.Hero, loader, Modifier.fillMaxSize(), ContentScale.Crop, HeroDim)
        }
        // Keeps the tag and lines legible over bright art.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB30A0908)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.BottomStart) {
                if (!logoShown) {
                    Text(e.ribbonName, color = accent, style = displayText(40.sp))
                }
                if (artworkEnabled) {
                    FallbackImage(
                        e.heroAppId, ArtKind.Logo, loader, Modifier.fillMaxSize(), ContentScale.Fit,
                        alignment = Alignment.BottomStart, onShown = { logoShown = it },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val tagColor = when (game.status.status) {
                GameStatus.Ready -> ShellColors.Brass
                GameStatus.SetUp, GameStatus.Update -> ShellColors.Amber
                GameStatus.Soon -> ShellColors.Muted
            }
            Text(
                foldOutTag(game.status.status),
                Modifier.border(1.dp, tagColor, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                color = tagColor,
                style = bodyText(12.sp).copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
            )
            Spacer(Modifier.height(6.dp))
            Text(e.title, color = LeafInk, style = bodyText(12.sp))
            if (e.requiredBuild.isNotEmpty()) Text(e.requiredBuild, color = LeafInk, style = bodyText(12.sp))
        }
    }
}

private val LeafInk = Color(0xFFE9E1D1)

/** The first of [artUrls] that loads; nothing (the leaf's own fallback shows) when none does. */
@Composable
private fun FallbackImage(
    appId: Int,
    kind: ArtKind,
    loader: ImageLoader,
    modifier: Modifier,
    scale: ContentScale,
    filter: ColorFilter? = null,
    alignment: Alignment = Alignment.Center,
    onShown: (Boolean) -> Unit = {},
) {
    val urls = remember(appId, kind) { artUrls(appId, kind) }
    var idx by remember(appId, kind) { mutableIntStateOf(0) }
    if (idx < urls.size) {
        AsyncImage(
            model = urls[idx],
            contentDescription = null,
            imageLoader = loader,
            contentScale = scale,
            alignment = alignment,
            colorFilter = filter,
            modifier = modifier,
            onSuccess = { onShown(true) },
            onError = { onShown(false); idx += 1 },
        )
    }
}

@Composable
private fun RightLeaf(game: ShelfGame, seen: SeenRecord?, onOpenUrl: (String) -> Unit, modifier: Modifier) {
    val e = game.entry
    Column(modifier.padding(10.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Block("Before you play") {
                beforeYouPlay(e, seen).forEachIndexed { i, item -> CheckRow(i + 1, item, onOpenUrl) }
            }
            Block("What goes where") {
                Row(
                    Modifier.height(IntrinsicSize.Min).padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Screen("Top · the game")
                    Box(Modifier.width(2.dp).fillMaxHeight().background(ShellColors.Brass))
                    Screen("Bottom · map, hints, party", Modifier.weight(1f, fill = false))
                }
            }
            Block("Mod check") {
                modCheckLines(seen).forEach { Text(it, color = ShellColors.Muted, style = bodyText(12.sp)) }
                if (game.status.status == GameStatus.Update) {
                    Text(game.status.reason, color = ShellColors.Amber, style = bodyText(12.sp))
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.guideUrl?.let { url -> ShellButton("Show me how", primary = true) { onOpenUrl(url) } }
            e.changelogUrl?.let { url -> ShellButton("Changelog", primary = false) { onOpenUrl(url) } }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(ShellColors.Surface).border(1.dp, ShellColors.Line, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = ShellColors.Ink, style = displayText(14.sp))
        content()
    }
}

private val CodeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = ShellColors.Brass, fontSize = 12.sp)

@Composable
private fun CheckRow(n: Int, item: CheckItem, onOpenUrl: (String) -> Unit) {
    val url = item.linkUrl
    Row(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) { stateDescription = if (item.done) "Done" else "Not done" }
            .then(if (url != null) Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onOpenUrl(url) } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = if (url != null) Alignment.CenterVertically else Alignment.Top,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .then(
                    if (item.done) Modifier.background(ShellColors.Brass)
                    else Modifier.border(1.dp, ShellColors.Line, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (item.done) "✓" else "$n",
                color = if (item.done) ShellColors.OnBrass else ShellColors.Muted,
                style = bodyText(12.sp).copy(fontWeight = FontWeight.Bold),
            )
        }
        Column {
            Text(withCode(item.text, CodeStyle), color = ShellColors.Ink, style = bodyText(12.sp))
            if (url != null && item.linkLabel != null) {
                Text(
                    item.linkLabel,
                    color = ShellColors.Brass,
                    style = bodyText(12.sp).copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
                )
            }
        }
    }
}

/** One screen in "What goes where". */
@Composable
private fun Screen(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.clip(RoundedCornerShape(4.dp)).background(ShellColors.Raised)
            .border(1.dp, ShellColors.Line, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
        color = ShellColors.Ink,
        style = bodyText(12.sp),
    )
}
