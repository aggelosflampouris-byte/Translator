package com.example.translator

import android.content.SharedPreferences
import com.example.translator.ui.main.isAccessibilityComponentEnabled
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
    fun curatedProfiles_andDefaults_keepOnlyTestedSupportedApps() {
        assertEquals(
            listOf("whatsapp", "instagram", "viber"),
            TargetAppCatalog.CURATED_PROFILES.map { it.id }
        )
        assertEquals(
            setOf(
                "com.whatsapp",
                "com.whatsapp.w4b",
                "com.instagram.android",
                "com.viber.voip"
            ),
            TargetAppCatalog.getDefaultEnabledPackages()
        )
        assertTrue(TargetAppCatalog.isSupportedPackage("com.whatsapp"))
        assertTrue(TargetAppCatalog.isSupportedPackage("com.instagram.android"))
        assertTrue(TargetAppCatalog.isSupportedPackage("com.viber.voip"))
        assertFalse(TargetAppCatalog.isSupportedPackage("com.facebook.orca"))
        assertFalse(TargetAppCatalog.isSupportedPackage("org.telegram.messenger"))
        assertFalse(TargetAppCatalog.isSupportedPackage("com.discord"))
    }

    @Test
    fun blockedSensitivePackages_allowsVerifiedMessagingApps() {
        val selfPkg = "com.example.translator"

        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.whatsapp", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.instagram.android", selfPkg))
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.viber.voip", selfPkg))
        // Legitimate package containing "time" or "anime" should not false-positive on IME keyword
        assertFalse(TargetAppCatalog.isBlockedSensitivePackage("com.example.animechat", selfPkg))
    }

    @Test
    fun profileForPackage_resolvesTestedAndFallbackProfiles() {
        assertEquals("whatsapp", TargetAppCatalog.profileForPackage("com.whatsapp").id)
        assertTrue(TargetAppCatalog.profileForPackage("com.whatsapp").expandContainerToParent)
        assertEquals("instagram", TargetAppCatalog.profileForPackage("com.instagram.android").id)
        assertFalse(TargetAppCatalog.profileForPackage("com.instagram.android").expandContainerToParent)
        assertEquals("viber", TargetAppCatalog.profileForPackage("com.viber.voip").id)
        assertTrue(TargetAppCatalog.profileForPackage("com.viber.voip").expandContainerToParent)
        assertEquals("generic", TargetAppCatalog.profileForPackage("com.facebook.orca").id)
        assertEquals("generic", TargetAppCatalog.profileForPackage("com.reddit.frontpage").id)
    }

    @Test
    fun saveAndGetEnabledPackages_stripsBlockedAndUnsupportedPackages() {
        val prefs = InMemorySharedPreferences()
        val selfPkg = "com.example.translator"

        TargetAppCatalog.saveEnabledPackages(
            prefs,
            setOf(
                "com.whatsapp",
                "com.instagram.android",
                "com.facebook.orca",
                "org.telegram.messenger",
                "com.revolut.revolut",
                "com.android.settings"
            ),
            selfPkg
        )

        val loaded = TargetAppCatalog.getEnabledPackages(prefs, selfPkg)
        assertEquals(setOf("com.whatsapp", "com.instagram.android"), loaded)

        // Simula preferencias heredadas de una versión anterior que todavía conservaban Messenger o apps no probadas.
        prefs.edit().putStringSet(
            AppDefaults.KEY_ENABLED_PACKAGES,
            setOf("com.whatsapp", "com.viber.voip", "com.facebook.orca", "com.facebook.mlite", "com.discord")
        ).apply()
        assertEquals(
            setOf("com.whatsapp", "com.viber.voip"),
            TargetAppCatalog.getEnabledPackages(prefs, selfPkg)
        )
    }

    @Test
    fun privacySensitivePackage_filtersFinancialAndCredentialAppsOnly() {
        assertTrue(TargetAppCatalog.isPrivacySensitivePackage("com.revolut.revolut"))
        assertTrue(TargetAppCatalog.isPrivacySensitivePackage("com.x8bit.bitwarden"))
        assertTrue(TargetAppCatalog.isPrivacySensitivePackage("com.paypal.android.p2pmobile"))
        assertFalse(TargetAppCatalog.isPrivacySensitivePackage("com.sec.android.app.launcher"))
        assertFalse(TargetAppCatalog.isPrivacySensitivePackage("com.whatsapp"))
    }

    @Test
    fun accessibilityComponentEnabled_requiresExactPackageAndServiceMatch() {
        val pkg = "com.example.translator"
        val svc = "com.example.translator.TranslationAccessibilityService"

        assertTrue(isAccessibilityComponentEnabled("$pkg/$svc", pkg, svc))
        assertTrue(isAccessibilityComponentEnabled("other/pkg.Svc:$pkg/.TranslationAccessibilityService", pkg, svc))
        assertFalse(isAccessibilityComponentEnabled("$pkg.evil/$svc", pkg, svc))
        assertFalse(isAccessibilityComponentEnabled("$pkg/.OtherService", pkg, svc))
        assertFalse(isAccessibilityComponentEnabled("", pkg, svc))
    }
}

