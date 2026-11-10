package com.diptych.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage

private val Amber = Color(0xFFE8B75D)
private val TileShape = RoundedCornerShape(6.dp)

private fun ribbonLabel(s: GameStatus) = when (s) {
    GameStatus.Ready -> "✓ READY"
    GameStatus.SetUp -> "SET UP"
    GameStatus.Update -> "UPDATE"
    GameStatus.Soon -> "SOON"
}

private fun ribbonColor(s: GameStatus) = when (s) {
    GameStatus.Ready -> ShellColors.Brass
    GameStatus.SetUp, GameStatus.Update -> Amber
    GameStatus.Soon -> ShellColors.Muted
}

/**
 * A 2:3 cover with a status ribbon. The initials tile always sits underneath, so while loading,
 * on failure, or with artwork off the shelf shows initials on the accent color (no spinner).
 * [imageLoader] is injectable so screenshots never touch the network.
 */
@Composable
fun CoverTile(
    entry: GameEntry,
    selected: Boolean,
    statusInfo: StatusInfo,
    artworkEnabled: Boolean,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
) {
    val width: Dp = if (selected) 132.dp else 104.dp
    val loader = imageLoader ?: ArtLoader.get(LocalContext.current)
    val desc = "${entry.title}, ${ribbonLabel(statusInfo.status)}"
    // Room around the tile so the offset outline is not clipped by the caller.
    Box(modifier.padding(4.dp)) {
        Box(
            Modifier
                .width(width)
                .aspectRatio(2f / 3f)
                .semantics { contentDescription = desc }
                .then(
                    if (selected) Modifier.drawBehind {
                        val o = 4.dp.toPx() // 2 dp offset + half of the 2 dp stroke
                        drawRoundRect(
                            ShellColors.Brass,
                            topLeft = Offset(-o + 1.dp.toPx(), -o + 1.dp.toPx()),
                            size = Size(size.width + 2 * (o - 1.dp.toPx()), size.height + 2 * (o - 1.dp.toPx())),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(2.dp.toPx()),
                        )
                    } else Modifier,
                )
                .clip(TileShape),
        ) {
            val dim = Modifier.alpha(if (statusInfo.status == GameStatus.Soon) 0.5f else 1f)
            if (entry.id == "ttw" && entry.coverAppIds.size >= 2) {
                Row(Modifier.fillMaxSize()) {
                    Half(entry, entry.coverAppIds[0], artworkEnabled, loader, dim.weight(1f), 15)
                    Box(Modifier.width(2.dp).fillMaxHeight().background(ShellColors.Brass))
                    Half(entry, entry.coverAppIds[1], artworkEnabled, loader, dim.weight(1f), 15)
                }
            } else {
                Half(entry, entry.coverAppIds.first(), artworkEnabled, loader, dim.fillMaxSize(), 22)
            }
            Text(
                ribbonLabel(statusInfo.status),
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x8C0A0908), Color(0xEB0A0908))))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                color = ribbonColor(statusInfo.status),
                fontFamily = ShellType.body,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

/** One half (or the whole) of a cover: initials underneath, the first URL that loads on top. */
@Composable
private fun Half(
    entry: GameEntry,
    appId: Int,
    artworkEnabled: Boolean,
    loader: ImageLoader,
    modifier: Modifier,
    initialsSp: Int,
) {
    Box(modifier.background(Color(entry.accent)), contentAlignment = Alignment.Center) {
        Text(
            entry.ribbonName,
            Modifier.padding(4.dp),
            color = Color(0xFF1A1611),
            fontFamily = ShellType.display,
            fontWeight = FontWeight.Bold,
            fontSize = initialsSp.sp,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
        if (artworkEnabled) {
            val urls = remember(appId) { artUrls(appId, ArtKind.Cover) }
            var idx by remember(appId) { mutableIntStateOf(0) }
            if (idx < urls.size) {
                AsyncImage(
                    model = urls[idx],
                    contentDescription = null,
                    imageLoader = loader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { idx += 1 },
                )
            }
        }
    }
}
