package com.diptych.app.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import com.diptych.app.kh1.ui.ConnectionLabel
import com.diptych.app.net.ServerMsg
import com.diptych.app.service.ConnectionSnapshot
import com.diptych.app.service.LINK_GAME_ID
import kotlinx.coroutines.delay

/** Where the bottom screen is. */
sealed interface Route {
    /**
     * A shell screen: a tab and, on the Shelf, maybe a game's fold-out ([foldOut] is its id).
     * [overGame]: the user left a live companion through the hinge button (or Back), so the link doesn't pull them
     * back while it stays up, and the top bar offers "Back to game".
     */
    data class Shell(val tab: ShellTab = ShellTab.Shelf, val foldOut: String? = null, val overGame: Boolean = false) : Route

    /** A game's companion screen, full-bleed. */
    data class Companion(val gameId: String) : Route
}

/** How long the companion shows "Waiting" after the game goes away, before the shell comes back. */
const val DISCONNECT_GRACE_MS = 10_000L

/** The game a live welcome hands the screen to, or null. Only a supported build of the link's game (KH1) takes over. */
fun takeoverGame(welcome: ServerMsg.Welcome?): String? =
    welcome?.takeIf { it.supported && it.gameId == LINK_GAME_ID }?.gameId

/**
 * Where to be, given the live connection. [previousShell] is the last shell screen the user was on (without
 * "Back to game"); [disconnectedForMs] is 0 while a welcome is live, else the time since it went away
 * ([Long.MAX_VALUE] when none has been live).
 * - A supported KH1 welcome takes the screen from the shell (Review Focus 3), unless the user chose the shell over it.
 * - A lost game keeps the companion ("Waiting") for [DISCONNECT_GRACE_MS], then returns [previousShell]; a reconnect
 *   inside that time keeps the companion (Review Focus 4).
 * - An unsupported welcome never opens the companion: the Shelf's ribbon says UPDATE and why.
 */
fun nextRoute(current: Route, previousShell: Route.Shell, snapshot: ConnectionSnapshot, disconnectedForMs: Long): Route {
    val welcome = snapshot.welcome
    val game = takeoverGame(welcome)
    val back = previousShell.copy(overGame = false)
    return when (current) {
        is Route.Companion -> when {
            game != null -> Route.Companion(game)
            welcome != null -> back // a build the companion can't read: the Shelf explains UPDATE
            disconnectedForMs >= DISCONNECT_GRACE_MS -> back
            else -> current // "Waiting": the game may be back in a moment
        }
        is Route.Shell -> when {
            !current.overGame -> if (game != null) Route.Companion(game) else current
            game != null -> current // the user chose the shell over the live game; "Back to game" returns to it
            welcome != null || disconnectedForMs >= DISCONNECT_GRACE_MS -> current.copy(overGame = false)
            else -> current // a blip: keep "Back to game" for the grace period
        }
    }
}

/** Back from a companion: the shell screen the user was on, with "Back to game". */
fun leaveCompanion(previousShell: Route.Shell): Route.Shell = previousShell.copy(overGame = true)

/** A hinge pull-down choice. A tab the user was already on comes back as they left it (the fold-out included). */
fun hingeRoute(choice: HingeChoice, previousShell: Route.Shell): Route.Shell = when (choice) {
    HingeChoice.Shelf -> if (previousShell.tab == ShellTab.Shelf) previousShell else Route.Shell(ShellTab.Shelf)
    HingeChoice.Device -> if (previousShell.tab == ShellTab.Device) previousShell else Route.Shell(ShellTab.Device)
    HingeChoice.Settings -> previousShell
}.copy(overGame = true)

/** [nextRoute]'s `disconnectedForMs`: [lostAtMs] is when the live welcome went away, null if none has been live. */
fun disconnectedFor(live: Boolean, lostAtMs: Long?, nowMs: Long): Long = when {
    live -> 0L
    lostAtMs == null -> Long.MAX_VALUE
    else -> nowMs - lostAtMs
}

/** The KH1 companion's banner, from the live welcome (the client's own welcome outlives the socket). */
fun companionLabel(snapshot: ConnectionSnapshot): ConnectionLabel {
    val w = snapshot.welcome
    return when {
        w == null -> ConnectionLabel.Waiting
        !w.supported -> ConnectionLabel.Unsupported
        else -> ConnectionLabel.Connected
    }
}

/** What the shell reads from the device. Screenshots pass fixed values (and an offline [imageLoader]). */
data class ShellEnv(
    val seen: Map<String, SeenRecord> = emptyMap(),
    val gameNativeVersion: String? = null,
    val artworkEnabled: Boolean = true,
    val imageLoader: ImageLoader? = null,
)

/** What the shell asks the activity to do. */
class ShellActions(
    val play: (GameEntry) -> Unit = {},
    val openUrl: (String) -> Unit = {},
    val toggleDiagnostics: () -> Unit = {},
)

/**
 * The bottom screen: the shell, or a connected game's companion, chosen by [nextRoute]; diagnostics over either.
 * Everything gets OLED care; the companion and diagnostics never dim.
 */
@Composable
fun ShellRoot(
    snapshot: ConnectionSnapshot,
    env: ShellEnv,
    actions: ShellActions,
    showDiagnostics: Boolean,
    diagnostics: @Composable () -> Unit,
    companion: @Composable (gameId: String) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    var route by remember { mutableStateOf<Route>(Route.Shell()) }
    var previousShell by remember { mutableStateOf(Route.Shell()) }
    var selectedId by remember { mutableStateOf(LINK_GAME_ID) }
    var settingsOpen by remember { mutableStateOf(false) } // the sheet itself is Task 6's
    var everLive by remember { mutableStateOf(false) }
    var lostAt by remember { mutableStateOf<Long?>(null) }

    fun go(r: Route) {
        route = r
        if (r is Route.Shell) previousShell = r.copy(overGame = false)
    }

    val welcome = snapshot.welcome
    LaunchedEffect(welcome) {
        if (welcome != null) {
            everLive = true
            lostAt = null
        } else if (everLive && lostAt == null) {
            lostAt = clock()
        }
        // Re-route now, and once more when the grace runs out if the game is still gone.
        while (true) {
            go(nextRoute(route, previousShell, snapshot, disconnectedFor(welcome != null, lostAt, clock())))
            val lost = lostAt ?: break
            val wait = lost + DISCONNECT_GRACE_MS - clock()
            if (welcome != null || wait <= 0) break
            delay(wait)
        }
    }

    val r = route
    BackHandler(enabled = !showDiagnostics && (r is Route.Companion || (r is Route.Shell && (r.foldOut != null || r.tab != ShellTab.Shelf)))) {
        when (r) {
            is Route.Companion -> go(leaveCompanion(previousShell))
            is Route.Shell -> go(if (r.foldOut != null) r.copy(foldOut = null) else Route.Shell(overGame = r.overGame))
        }
    }

    OledCare(connected = welcome != null, dimAllowed = !showDiagnostics && r !is Route.Companion, clock = clock) {
        when {
            showDiagnostics -> Box(Modifier.fillMaxSize()) {
                diagnostics()
                // Leave 72 dp on the right so the diagnostics header's own buttons receive taps normally.
                HoldStrip(Modifier.align(Alignment.TopStart).padding(end = 72.dp), actions.toggleDiagnostics)
            }
            r is Route.Companion -> CompanionFrame(
                onChoose = { c ->
                    if (c == HingeChoice.Settings) settingsOpen = true
                    go(hingeRoute(c, previousShell))
                },
                onDiagnostics = actions.toggleDiagnostics,
            ) { companion(r.gameId) }
            r is Route.Shell -> {
                val game = takeoverGame(welcome)
                ShellScreen(
                    route = r,
                    env = env,
                    games = shelfGames(env.seen, welcome),
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onRoute = ::go,
                    actions = actions,
                    onSettings = { settingsOpen = true },
                    backToGame = if (r.overGame && game != null) ({ go(Route.Companion(game)) }) else null,
                )
            }
        }
    }
}

/** One shell screen: the top bar over the Shelf, a fold-out, or another tab. */
@Composable
fun ShellScreen(
    route: Route.Shell,
    env: ShellEnv,
    games: List<ShelfGame>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onRoute: (Route.Shell) -> Unit,
    actions: ShellActions,
    onSettings: () -> Unit = {},
    backToGame: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().background(ShellColors.Ground)) {
        TopBar(
            active = route.tab,
            onSelect = { tab -> onRoute(Route.Shell(tab, overGame = route.overGame)) },
            onSettings = onSettings,
            onWordmarkHold = actions.toggleDiagnostics,
            backToGame = backToGame,
        )
        val fold = route.foldOut?.let { id -> games.firstOrNull { it.entry.id == id } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                route.tab == ShellTab.Shelf && fold != null -> FoldOut(
                    fold, env.seen[fold.entry.id], env.artworkEnabled, actions.openUrl, imageLoader = env.imageLoader,
                )
                route.tab == ShellTab.Shelf -> ShelfScreen(
                    games = games,
                    selectedId = selectedId,
                    onSelect = onSelect,
                    gameNativeVersion = env.gameNativeVersion,
                    artworkEnabled = env.artworkEnabled,
                    onMain = { entry, action ->
                        if (action == MainAction.Play) actions.play(entry) else onRoute(route.copy(foldOut = entry.id))
                    },
                    imageLoader = env.imageLoader,
                )
                else -> Text(
                    if (route.tab == ShellTab.Device) "Device checks are on the way." else "Guides are on the way.",
                    Modifier.align(Alignment.Center),
                    color = ShellColors.Muted,
                    style = bodyText(13.sp),
                )
            }
        }
    }
}

/** How long a hold takes to toggle diagnostics (the M2 gesture). */
const val HOLD_MS = 1000L

/** Calls [onHold] once the pointer has stayed down for [ms]. Taps pass nothing on and nothing is consumed. */
internal fun Modifier.holdFor(ms: Long, onHold: () -> Unit): Modifier = composed {
    val action by rememberUpdatedState(onHold)
    pointerInput(ms) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var held = true
            withTimeoutOrNull(ms) {
                waitForUpOrCancellation()
                held = false
            }
            if (held) action()
        }
    }
}

/** A transparent 64 dp strip over a screen's header that toggles diagnostics on a 1 s hold. */
@Composable
internal fun HoldStrip(modifier: Modifier, onHold: () -> Unit) {
    Box(modifier.fillMaxWidth().height(64.dp).holdFor(HOLD_MS, onHold))
}
