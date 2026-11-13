package com.example.translator

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun normalizeVersion_stripsPrefixesAndWhitespace() {
        assertEquals("1.0.63", AppUpdater.normalizeVersion("v1.0.63"))
        assertEquals("1.0.63", AppUpdater.normalizeVersion("V1.0.63"))
        assertEquals("1.0.63", AppUpdater.normalizeVersion(" 1.0.63 "))
        assertEquals("2.0.0", AppUpdater.normalizeVersion("v2.0.0"))
    }

    // Por qué: Verifica que la comparación de versiones detecte correctamente cuándo una versión es posterior
    @Test
    fun isNewerVersion_detectsNewerReleasesCorrectly() {
        assertTrue(AppUpdater.isNewerVersion("v1.0.64", "1.0.63"))
        assertTrue(AppUpdater.isNewerVersion("v1.1.0", "1.0.63"))
        assertTrue(AppUpdater.isNewerVersion("v2.0.0", "1.0.63"))
        assertTrue(AppUpdater.isNewerVersion("1.0.64", "v1.0.63"))

        // Same version
        assertFalse(AppUpdater.isNewerVersion("v1.0.63", "1.0.63"))
        assertFalse(AppUpdater.isNewerVersion("1.0.63", "v1.0.63"))

        // Older version
        assertFalse(AppUpdater.isNewerVersion("v1.0.62", "1.0.63"))
        assertFalse(AppUpdater.isNewerVersion("v0.9.9", "1.0.63"))
    }

    // Por qué: Comprueba el formateo semántico cuando el número de subversión supera los 9 dígitos
    @Test
    fun isNewerVersion_handlesMultiDigitComponents() {
        assertTrue(AppUpdater.isNewerVersion("v1.0.100", "1.0.99"))
        assertFalse(AppUpdater.isNewerVersion("v1.0.9", "1.0.10"))
    }

    // Por qué: Solo se aceptan descargas HTTPS del repositorio oficial o de la CDN de versiones de GitHub
    @Test
    fun isTrustedDownloadUrl_acceptsOfficialRepositoryAndCdn() {
        assertTrue(AppUpdater.isTrustedDownloadUrl("https://github.com/aggelosflampouris-byte/Translator/releases/download/v1.0.82/app-release-signed.apk"))
        assertTrue(AppUpdater.isTrustedDownloadUrl("https://github.com/aggelosflampouris-byte/Translator/releases/tag/v1.0.82"))
        assertTrue(AppUpdater.isTrustedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/123/abc?X-Amz=1"))
        assertTrue(AppUpdater.isTrustedDownloadUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123"))
    }

    @Test
    fun isTrustedDownloadUrl_rejectsForeignOrInsecureUrls() {
        assertFalse(AppUpdater.isTrustedDownloadUrl("http://github.com/aggelosflampouris-byte/Translator/releases/download/v1/app.apk"))
        assertFalse(AppUpdater.isTrustedDownloadUrl("https://github.com/attacker/Translator/releases/download/v1/app.apk"))
        assertFalse(AppUpdater.isTrustedDownloadUrl("https://github.com/aggelosflampouris-byte/TranslatorEvil/releases/download/v1/app.apk"))
        assertFalse(AppUpdater.isTrustedDownloadUrl("https://github.com.evil.com/aggelosflampouris-byte/Translator/app.apk"))
        assertFalse(AppUpdater.isTrustedDownloadUrl("https://user@github.com/aggelosflampouris-byte/Translator/app.apk"))
        assertFalse(AppUpdater.isTrustedDownloadUrl("https://github.com/aggelosflampouris-byte/Translator/../../attacker/Repo/app.apk"))
        assertFalse(AppUpdater.isTrustedDownloadUrl("https://github.com/aggelosflampouris-byte/Translator/%2e%2e/%2e%2e/attacker/Repo/app.apk"))
        assertFalse(AppUpdater.isTrustedDownloadUrl("not a url"))
        assertFalse(AppUpdater.isTrustedDownloadUrl("file:///sdcard/app.apk"))
    }

    @Test
    fun parseSha256Digest_acceptsRawAndSha256SumFormats() {
        val hex = "a".repeat(64)
        assertEquals(hex, AppUpdater.parseSha256Digest(hex))
        assertEquals(hex, AppUpdater.parseSha256Digest("${hex.uppercase()}  app-release-signed.apk\n"))
        assertNull(AppUpdater.parseSha256Digest("<html>not found</html>"))
        assertNull(AppUpdater.parseSha256Digest("deadbeef"))
    }

    @Test
    fun computeFileSha256_computesExpectedDigest() {
        val tmp = File.createTempFile("appupdater-sha", ".bin")
        try {
            tmp.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AppUpdater.computeFileSha256(tmp)
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun buildReleaseUrl_rejectsPathTraversalTags() {
        assertNull(AppUpdater.buildReleaseUrl("download", "../attacker", "app.apk"))
        assertNull(AppUpdater.buildReleaseUrl("download", "v1.0/../../evil", "app.apk"))
        assertEquals(
            "https://github.com/aggelosflampouris-byte/Translator/releases/download/v1.0.109/app-release-signed.apk",
            AppUpdater.buildReleaseUrl("download", "v1.0.109", "app-release-signed.apk")
        )
    }
}

