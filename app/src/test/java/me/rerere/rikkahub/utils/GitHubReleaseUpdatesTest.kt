package me.rerere.rikkahub.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.AppBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseUpdatesTest {
    @Test
    fun `maps a versioned universal release with its notes and download`() {
        val result = parseGitHubReleaseUpdates(payload(release("1.0-v3.1.1")))!!

        assertEquals("3.1.1", result.version)
        assertEquals("2026-10-25T01:00:00Z", result.publishedAt)
        assertEquals("Release notes", result.changelog)
        assertEquals("app-github-release.apk", result.downloads.single().name)
        assertEquals(
            "${AppBrand.PROJECT_URL}/releases/download/1.0-v3.1.1/app-github-release.apk",
            result.downloads.single().url,
        )
    }

    @Test
    fun `ignores historical custom tags nightly drafts and prereleases`() {
        val result = parseGitHubReleaseUpdates(
            payload(
                release("99.0.0"),
                release("1.0-nightly"),
                release("1.0-v99.0.1", draft = true),
                release("1.0-v99.0.2", prerelease = true),
                release("1.0-vnot-a-version"),
                release("1.0-v3.1.1"),
            )
        )!!

        assertEquals("3.1.1", result.version)
    }

    @Test
    fun `selects by version even when release dates have a different order`() {
        val result = parseGitHubReleaseUpdates(
            payload(release("1.0-v3.9.0"), release("1.0-v3.10.0"), release("1.0-v3.2.0"))
        )!!

        assertEquals("3.10.0", result.version)
    }

    @Test
    fun `requires one uploaded nonempty universal release APK`() {
        val tag = "1.0-v3.1.1"
        listOf(
            emptyList(),
            listOf(asset(tag, name = "app-github-debug.apk")),
            listOf(asset(tag, size = 0)),
            listOf(asset(tag, state = "new")),
            listOf(asset(tag), asset(tag, name = "arm64.apk")),
        ).forEach { assets ->
            assertNull(parseGitHubReleaseUpdates(payload(release(tag, assets = assets))))
        }
    }

    @Test
    fun `does not download from another repository tag or insecure address`() {
        val tag = "1.0-v3.1.1"
        listOf(
            "https://github.com/rikkahub/rikkahub/releases/download/$tag/app-github-release.apk",
            "${AppBrand.PROJECT_URL}/releases/download/1.0-v3.1.0/app-github-release.apk",
            "http://github.com/Hoixg/rikkaplus/releases/download/$tag/app-github-release.apk",
        ).forEach { url ->
            assertNull(parseGitHubReleaseUpdates(payload(release(tag, assets = listOf(asset(tag, url = url))))))
        }
    }

    @Test
    fun `returns no update when only incompatible channels are published`() {
        assertNull(parseGitHubReleaseUpdates(payload(release("3.1.9-custom"), release("1.0-nightly"))))
        assertNull(parseGitHubReleaseUpdates("[]"))
    }

    private fun payload(vararg releases: JsonObject) = JsonArray(releases.toList()).toString()

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<JsonObject> = listOf(asset(tag)),
    ) = buildJsonObject {
        put("tag_name", tag)
        put("draft", draft)
        put("prerelease", prerelease)
        put("published_at", "2026-10-25T01:00:00Z")
        put("body", "Release notes")
        put("assets", JsonArray(assets))
        put("extra_github_field", "ignored")
    }

    private fun asset(
        tag: String,
        name: String = "app-github-release.apk",
        size: Long = 1024,
        state: String = "uploaded",
        url: String = "${AppBrand.PROJECT_URL}/releases/download/$tag/$name",
    ) = buildJsonObject {
        put("name", name)
        put("size", size)
        put("state", state)
        put("browser_download_url", url)
    }
}
