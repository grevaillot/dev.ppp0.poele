package dev.ppp0.poele.update

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Version publiée, décrite par l'asset update.json de sa release GitHub. */
data class AppRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkName: String,
    val sha256: String,
    val notes: String,
)

/**
 * Releases publiées par `make release-github` sur un dépôt GitHub public, lisible sans compte.
 * La dernière release porte l'APK et un asset update.json
 * `{"tag", "versionName", "versionCode", "apk", "sha256"}`, `apk` nommant l'asset APK.
 */
object AppUpdateFeed {
    const val UPDATE_ASSET = "update.json"

    fun latestReleaseUrl(repo: String) = "https://api.github.com/repos/$repo/releases/latest"

    /** URL de téléchargement de l'asset [name] de la release, null s'il est absent. */
    fun assetUrl(releaseJson: String, name: String): String? {
        val assets = JSONObject(releaseJson).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == name) return a.optString("browser_download_url").ifEmpty { null }
        }
        return null
    }

    fun parse(releaseJson: String, updateJson: String): AppRelease {
        val u = JSONObject(updateJson)
        val apkName = u.getString("apk")
        val sha = u.getString("sha256").lowercase()
        require(Regex("[0-9a-f]{64}").matches(sha)) { "SHA-256 invalide dans update.json" }
        return AppRelease(
            tag = u.getString("tag"),
            versionName = u.getString("versionName"),
            versionCode = u.getInt("versionCode"),
            apkUrl = assetUrl(releaseJson, apkName) ?: error("APK $apkName absent de la release"),
            apkName = apkName,
            sha256 = sha,
            notes = JSONObject(releaseJson).optString("body"),
        )
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
