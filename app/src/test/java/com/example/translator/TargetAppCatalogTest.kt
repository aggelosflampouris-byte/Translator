package com.example.translator

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAppCatalogTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val existing = data[key] as? Set<String> ?: return defValues
            return existing.toMutableSet()
        }
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }
    }

    @Test
    fun blockedSensitivePackages_rejectsLaunchersKeyboardsBanksAndSelf() {
        val selfPkg = "com.example.translator"

        assertTrue(TargetAppCatalog.isBlockedSensitivePackage(null, selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage("", selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage(selfPkg, selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage("com.android.systemui", selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage("com.android.settings", selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage("com.sec.android.app.launcher", selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage("com.google.android.inputmethod.latin", selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage("com.revolut.revolut", selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage("com.x8bit.bitwarden", selfPkg))
        assertTrue(TargetAppCatalog.isBlockedSensitivePackage("com.paypal.android.p2pmobile", selfPkg))
    }

    @Test
    fun blockedSensitivePackages_allowsLegitimateMessagingApps() {
        val selfPkg = "com.example.translator"

        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.whatsapp", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("org.telegram.messenger", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.facebook.orca", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.instagram.android", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.discord", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("org.thoughtcrime.securesms", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.viber.voip", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.google.android.apps.messaging", selfPkg))
        // Legitimate package containing "time" or "anime" should not false-positive on IME keyword
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.example.animechat", selfPkg))
    }

    @Test
    fun profileForPackage_resolvesCuratedAndFallbackProfiles() {
        assertEquals("whatsapp", TargetAppCatalog.profileForPackage("com.whatsapp").id)
        assertEquals(ChatAlignmentMode.LEFT_RIGHT_CHAT, TargetAppCatalog.profileForPackage("org.telegram.messenger").alignmentMode)
        assertEquals(ChatAlignmentMode.LEFT_STREAM_CHAT, TargetAppCatalog.profileForPackage("com.discord").alignmentMode)
        assertFalse(TargetAppCatalog.profileForPackage("com.discord").expandContainerToParent)
        assertEquals("generic", TargetAppCatalog.profileForPackage("com.reddit.frontpage").id)
    }

    @Test
    fun saveAndGetEnabledPackages_stripsBlockedPackages() {
        val prefs = InMemorySharedPreferences()
        val selfPkg = "com.example.translator"

        TargetAppCatalog.saveEnabledPackages(
            prefs,
            setOf("com.whatsapp", "org.telegram.messenger", "com.revolut.revolut", "com.android.settings"),
            selfPkg
        )

        val loaded = TargetAppCatalog.getEnabledPackages(prefs, selfPkg)
        assertEquals(setOf("com.whatsapp", "org.telegram.messenger"), loaded)
    }
}

