package com.diptych.app.shell

import com.diptych.app.ttw.TTW_NOT_LOADED
import com.diptych.app.ttw.TTW_PORT

/** Oldest KH1 mod plugin (major.minor) this app works with. The shipped mod reports "0.1.0". */
const val MIN_PLUGIN = "0.1"

/** Oldest TTW plugin this app works with: the spike plugin, which reports "0.0.1-spike". */
const val MIN_TTW_PLUGIN = "0.0"

private const val RELEASES = "https://gitea.fantastictwo.net/joe/diptych/releases"

data class GameEntry(
    val id: String,
    val title: String,
    val ribbonName: String,
    val tagline: String,
    val requiredBuild: String,
    val coverAppIds: List<Int>,
    val heroAppId: Int,
    val accent: Long,
    val port: Int?,
    val modAsset: String?,
    val guideUrl: String?,
    val changelogUrl: String?,
    /** Oldest plugin (major.minor) of this game's mod the app works with. */
    val minPlugin: String = MIN_PLUGIN,
    /** Why a welcome with `supported:false` can't be used, as the Shelf says it. */
    val unsupportedReason: String = "",
    /** "Before you play", between downloading the zip and the first start. Text between backticks is code. */
    val setupSteps: List<String> = emptyList(),
)

val CATALOG: List<GameEntry> = listOf(
    GameEntry(
        id = "ttw",
        title = "Tale of Two Wastelands",
        ribbonName = "TTW",
        tagline = "Pip-Boy dashboard · map · items · radio",
        requiredBuild = "New Vegas 1.4.0.525 · xNVSE 6.4.9+",
        coverAppIds = listOf(22370, 22380),
        heroAppId = 22380,
        accent = 0xFF6FA35BL,
        port = TTW_PORT,
        modAsset = RELEASES,
        guideUrl = "https://gitea.fantastictwo.net/joe/diptych/src/branch/main/docs/guide/ttw-on-ayn-thor.md",
        changelogUrl = RELEASES,
        minPlugin = MIN_TTW_PLUGIN,
        unsupportedReason = TTW_NOT_LOADED,
        setupSteps = listOf(
            "Copy `ttw_dualscreen.dll` and `ttw_dualscreen.ini` into `Data\\NVSE\\Plugins`",
            "Start New Vegas through xNVSE with `TaleOfTwoWastelands.esm` enabled",
        ),
    ),
    GameEntry(
        id = "kh1",
        title = "Kingdom Hearts Final Mix",
        ribbonName = "KH1",
        tagline = "Map with chests · story hints · party HP",
        requiredBuild = "Steam 1.0.0.2 · GameNative 1.3.0+",
        coverAppIds = listOf(2552430),
        heroAppId = 2552430,
        accent = 0xFFC9A45CL,
        port = 7970,
        modAsset = RELEASES,
        guideUrl = "https://gitea.fantastictwo.net/joe/diptych/src/branch/main/docs/spike/MAP-CHECKLIST.md",
        changelogUrl = RELEASES,
        unsupportedReason = "This KH1 build isn't supported yet (needs Steam 1.0.0.2)",
        setupSteps = listOf(
            "In GameNative: KH 1.5+2.5 → Mods → Import",
            "Add `WINEDLLOVERRIDES` = `dinput8=n,b` in Environment",
        ),
    ),
    GameEntry(
        id = "kh2",
        title = "Kingdom Hearts II Final Mix",
        ribbonName = "KH2",
        tagline = "Minimap · party HP · drive gauge",
        requiredBuild = "",
        coverAppIds = listOf(2552430),
        heroAppId = 2552430,
        accent = 0xFFB5523BL,
        port = null,
        modAsset = null,
        guideUrl = null,
        changelogUrl = null,
    ),
)
