package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.AppBrand
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val RELEASE_TAG_PREFIX = "1.0-v"
private const val RELEASE_APK_NAME = "app-github-release.apk"
private val releaseVersionPattern = Regex("""\d+\.\d+\.\d+(?:-[\da-zA-Z.-]+)?(?:\+[\da-zA-Z.-]+)?""")
private val releaseJson = Json { ignoreUnknownKeys = true }

/** 仓库同时保留 custom 历史版本和每日构建，只用 1.0 的正式通用包检查更新。 */
internal fun parseGitHubReleaseUpdates(payload: String): UpdateInfo? {
    val projectUrl = AppBrand.PROJECT_URL.toHttpUrl()
    return releaseJson.decodeFromString<List<GitHubRelease>>(payload).mapNotNull { release ->
        if (release.draft || release.prerelease || !release.tag.startsWith(RELEASE_TAG_PREFIX)) {
            return@mapNotNull null
        }
        val version = release.tag.removePrefix(RELEASE_TAG_PREFIX)
        if (!releaseVersionPattern.matches(version)) return@mapNotNull null
        val publishedAt = release.publishedAt ?: return@mapNotNull null
        val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val asset = apkAssets.singleOrNull()?.takeIf {
            it.name == RELEASE_APK_NAME && it.state == "uploaded" && it.size > 0
        } ?: return@mapNotNull null
        val downloadUrl = asset.downloadUrl.toHttpUrlOrNull() ?: return@mapNotNull null
        if (downloadUrl.scheme != "https" || downloadUrl.host != projectUrl.host ||
            downloadUrl.pathSegments != projectUrl.pathSegments +
                listOf("releases", "download", release.tag, RELEASE_APK_NAME)
        ) return@mapNotNull null

        UpdateInfo(
            version = version,
            publishedAt = publishedAt,
            changelog = release.body.orEmpty(),
            downloads = listOf(
                UpdateDownload(name = asset.name, url = asset.downloadUrl, size = asset.size.fileSizeToString())
            ),
        )
    }.maxByOrNull { Version(it.version) }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    val state: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
)
