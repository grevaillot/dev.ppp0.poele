package dev.ppp0.poele.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

sealed interface FeedStatus {
    data object Disabled : FeedStatus
    data object Unknown : FeedStatus
    data object Checking : FeedStatus
    /** Dépôt joignable, aucune release publiée. */
    data object NoRelease : FeedStatus
    data class Latest(val release: AppRelease) : FeedStatus
    data class Unreachable(val reason: String) : FeedStatus
}

data class AppUpdateState(
    val feed: FeedStatus = FeedStatus.Unknown,
    /** Version publiée plus récente, null si à jour ou non vérifié. */
    val available: AppRelease? = null,
    val progress: Int? = null,
    /** APK téléchargé et vérifié, prêt à installer. */
    val apk: File? = null,
    val error: String? = null,
)

/**
 * Cherche une version plus récente dans les releases GitHub, puis télécharge et vérifie son APK
 * (SHA-256 de update.json). L'installation est laissée à l'UI : installeur Android, confirmation
 * de l'utilisateur. Android refuse d'ailleurs toute mise à jour signée par une autre clé.
 * Avec [offerUpdates] faux (builds de dev), seul l'état du dépôt est remonté.
 */
class AppUpdater(
    private val scope: CoroutineScope,
    private val repo: String,
    private val downloadDir: File,
    private val currentVersionCode: Int,
    private val offerUpdates: Boolean,
) {
    private val _state = MutableStateFlow(AppUpdateState(feed = if (repo.isBlank()) FeedStatus.Disabled else FeedStatus.Unknown))
    val state = _state.asStateFlow()

    fun check() {
        if (repo.isBlank() || _state.value.feed == FeedStatus.Checking) return
        _state.update { it.copy(feed = FeedStatus.Checking) }
        scope.launch {
            val feed = try {
                withContext(Dispatchers.IO) { latestRelease() }?.let { FeedStatus.Latest(it) } ?: FeedStatus.NoRelease
            } catch (e: Exception) {
                FeedStatus.Unreachable(e.message ?: e.javaClass.simpleName)
            }
            val newer = (feed as? FeedStatus.Latest)?.release?.takeIf { offerUpdates && it.versionCode > currentVersionCode }
            _state.update { it.copy(feed = feed, available = newer ?: it.available) }
        }
    }

    fun download() {
        val release = _state.value.available ?: return
        if (_state.value.progress != null) return
        _state.update { it.copy(progress = 0, apk = null, error = null) }
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { downloadApk(release) }
                _state.update { it.copy(progress = null, apk = apk) }
            } catch (e: Exception) {
                _state.update { it.copy(progress = null, error = "Téléchargement impossible : ${e.message}") }
            }
        }
    }

    private fun latestRelease(): AppRelease? {
        val releaseJson = get(AppUpdateFeed.latestReleaseUrl(repo)) ?: return null // 404 : aucune release
        val updateUrl = AppUpdateFeed.assetUrl(releaseJson, AppUpdateFeed.UPDATE_ASSET) ?: return null
        val updateJson = get(updateUrl) ?: return null
        return AppUpdateFeed.parse(releaseJson, updateJson)
    }

    private fun downloadApk(release: AppRelease): File {
        downloadDir.mkdirs()
        downloadDir.listFiles()?.filter { it.name != release.apkName }?.forEach { it.delete() } // ne garder que celle-ci
        val target = File(downloadDir, release.apkName)
        val partial = File(downloadDir, release.apkName + ".part")
        val conn = open(release.apkUrl)
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.update { it.copy(progress = (done * 100 / total).toInt()) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (AppUpdateFeed.sha256(partial) != release.sha256) {
            partial.delete()
            error("empreinte SHA-256 inattendue")
        }
        partial.renameTo(target)
        return target
    }

    /** Contenu de [url], ou null sur 404. */
    private fun get(url: String): String? {
        val conn = open(url)
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    // Redirections suivies : les assets GitHub redirigent vers leur stockage (HTTPS uniquement, voir network_security_config).
    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "URL non HTTPS refusée" }
        return (URL(url).openConnection() as HttpURLConnection).apply(::configure)
    }

    private fun configure(c: HttpURLConnection) = with(c) {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "poele-android")
    }

    private companion object { const val TIMEOUT_MS = 15_000 }
}
