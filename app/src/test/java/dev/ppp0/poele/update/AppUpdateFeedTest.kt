package dev.ppp0.poele.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateFeedTest {
    // Forme d'une réponse GitHub /releases/latest (champs utiles seulement).
    private val release = """
        {"tag_name":"v0.3.0","body":"Notes","assets":[
          {"name":"poele-v0.3.0.apk","browser_download_url":"https://github.com/o/r/releases/download/v0.3.0/poele-v0.3.0.apk"},
          {"name":"update.json","browser_download_url":"https://github.com/o/r/releases/download/v0.3.0/update.json"}]}
    """.trimIndent()
    private val sha = "a".repeat(64)
    private val update = """{"tag":"v0.3.0","versionName":"0.3.0","versionCode":300,"apk":"poele-v0.3.0.apk","sha256":"${sha.uppercase()}"}"""

    @Test fun parsesRelease() {
        val r = AppUpdateFeed.parse(release, update)
        assertEquals(300, r.versionCode)
        assertEquals("0.3.0", r.versionName)
        assertEquals("https://github.com/o/r/releases/download/v0.3.0/poele-v0.3.0.apk", r.apkUrl)
        assertEquals(sha, r.sha256) // normalisé en minuscules
        assertEquals("Notes", r.notes)
    }

    @Test fun missingAssets() {
        assertNull(AppUpdateFeed.assetUrl("""{"assets":[]}""", "update.json"))
        assertThrows(IllegalStateException::class.java) { AppUpdateFeed.parse(release, update.replace("poele-v0.3.0.apk", "autre.apk")) }
    }

    @Test fun rejectsBadHash() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdateFeed.parse(release, update.replace(sha.uppercase(), "1234")) }
    }
}
