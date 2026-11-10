package com.diptych.app.shell

/** Oldest mod plugin (major.minor) this app works with. The shipped mod reports "0.1.0". */
const val MIN_PLUGIN = "0.1"

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
)

val CATALOG: List<GameEntry> = listOf(
    GameEntry(
        id = "ttw",
        title = "Tale of Two Wastelands",
        ribbonName = "TTW",
        tagline = "Pip-Boy dashboard · map · items · radio",
        requiredBuild = "",
        coverAppIds = listOf(22370, 22380),
        heroAppId = 22380,
        accent = 0xFF6FA35BL,
        port = null,
        modAsset = null,
        guideUrl = "https://github.com/TristanAdams03/ttw-dualscreen/blob/m0-spike/docs/guide/ttw-on-ayn-thor.md",
        changelogUrl = null,
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
