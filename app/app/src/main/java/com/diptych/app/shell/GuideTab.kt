package com.diptych.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val KH1_GUIDE_URL = "https://gitea.fantastictwo.net/joe/diptych/src/branch/main/docs/spike/MAP-CHECKLIST.md"
const val TTW_GUIDE_URL = "https://github.com/TristanAdams03/ttw-dualscreen/blob/m0-spike/docs/guide/ttw-on-ayn-thor.md"

data class GuideCard(val title: String, val blurb: String, val url: String)

val GUIDES = listOf(
    GuideCard("KH1 on the Thor with Diptych", "What goes where, and a check that the map follows the game.", KH1_GUIDE_URL),
    GuideCard("TTW on the AYN Thor", "Fallout 3 and New Vegas across both screens.", TTW_GUIDE_URL),
)

/** Guide: cards that open in the browser. */
@Composable
fun GuideTab(openUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("What goes where", color = ShellColors.Ink, style = displayText(20.sp))
        GUIDES.forEach { g ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ShellColors.Surface)
                    .clickable(role = Role.Button) { openUrl(g.url) }
                    .heightIn(min = 48.dp).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(g.title, color = ShellColors.Ink, style = bodyText(15.sp).copy(fontWeight = FontWeight.Bold))
                Text(g.blurb, color = ShellColors.Muted, style = bodyText(13.sp))
                Text("Opens in your browser", color = ShellColors.Brass, style = bodyText(12.sp))
            }
        }
    }
}
