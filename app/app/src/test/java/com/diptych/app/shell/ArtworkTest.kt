package com.diptych.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTest {
    @Test fun coverUrlsAreCloudflareThenAkamai() {
        assertEquals(
            listOf(
                "https://cdn.cloudflare.steamstatic.com/steam/apps/2552430/library_600x900.jpg",
                "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/2552430/library_600x900.jpg",
            ),
            artUrls(2552430, ArtKind.Cover),
        )
    }

    @Test fun fileNamesPerKind() {
        assertTrue(artUrls(22380, ArtKind.Hero).all { it.endsWith("/22380/library_hero.jpg") })
        assertTrue(artUrls(22380, ArtKind.Logo).all { it.endsWith("/22380/logo.png") })
    }

    @Test fun fallbackOrderIsCloudflareFirst() {
        val u = artUrls(22370, ArtKind.Cover)
        assertEquals(2, u.size)
        assertTrue(u[0].contains("cdn.cloudflare.steamstatic.com"))
        assertTrue(u[1].contains("shared.akamai.steamstatic.com"))
    }
}
