package com.diptych.app.shell

import android.content.SharedPreferences
import com.diptych.app.net.ServerMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusTest {
    private val ttw = CATALOG.first { it.id == "ttw" }
    private val kh1 = CATALOG.first { it.id == "kh1" }
    private val kh2 = CATALOG.first { it.id == "kh2" }

    private fun welcome(plugin: String = "0.1.0", supported: Boolean = true) =
        ServerMsg.Welcome(plugin, 1, "kh1", "1.0.0.2", supported, emptyList())

    private fun seen(plugin: String = "0.1.0", supported: Boolean = true, at: Long = 1_762_387_200_000L) =
        SeenRecord(plugin, "1.0.0.2", supported, at)

    @Test fun catalogOrderAndValues() {
        assertEquals(listOf("ttw", "kh1", "kh2"), CATALOG.map { it.id })
        assertEquals(7970, kh1.port)
        assertEquals(listOf(22370, 22380), ttw.coverAppIds)
        assertEquals("0.1", MIN_PLUGIN)
    }

    @Test fun portlessIsSoon() {
        assertEquals(StatusInfo(GameStatus.Soon, "Coming later"), statusOf(ttw, null, null))
        assertEquals(GameStatus.Soon, statusOf(kh2, seen(), welcome()).status)
    }

    @Test fun unsupportedLiveIsUpdateWithReason() {
        val s = statusOf(kh1, null, welcome(supported = false))
        assertEquals(GameStatus.Update, s.status)
        assertEquals("This KH1 build isn't supported yet (needs Steam 1.0.0.2)", s.reason)
    }

    @Test fun unsupportedIsNeverReadyEvenWithOldSupportedSeen() {
        val s = statusOf(kh1, seen(supported = true), welcome(supported = false))
        assertEquals(GameStatus.Update, s.status)
        assertEquals(GameStatus.Update, statusOf(kh1, seen(supported = false), null).status)
    }

    @Test fun olderPluginLiveIsUpdate() {
        assertEquals(StatusInfo(GameStatus.Update, "Mod update available"), statusOf(kh1, null, welcome("0.0.3")))
    }

    @Test fun olderPluginSeenIsUpdate() {
        assertEquals(StatusInfo(GameStatus.Update, "Mod update available"), statusOf(kh1, seen("0.0.3"), null))
    }

    @Test fun seenSupportedIsReadyWithDate() {
        assertEquals(StatusInfo(GameStatus.Ready, "Mod seen 2025-11-06"), statusOf(kh1, seen(), null))
    }

    @Test fun liveSupportedIsReady() {
        assertEquals(GameStatus.Ready, statusOf(kh1, null, welcome()).status)
    }

    @Test fun nothingSeenIsSetUp() {
        assertEquals(StatusInfo(GameStatus.SetUp, "Mod never seen"), statusOf(kh1, null, null))
    }

    @Test fun seenStoreRoundTrip() {
        val store = SeenStore(FakePrefs())
        assertNull(store.get("kh1"))
        val r = seen()
        store.put("kh1", r)
        assertEquals(r, store.get("kh1"))
        assertNull(store.get("kh2"))
    }

    @Test fun seenStoreIgnoresCorruptJson() {
        val p = FakePrefs()
        p.edit().putString("seen.kh1", "{nope").apply()
        assertNull(SeenStore(p).get("kh1"))
    }
}

/** Minimal in-memory SharedPreferences; only string storage is exercised. */
class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            for ((k, v) in pending) if (v == null) map.remove(k) else map[k] = v
        }
    }
}
