package io.github.lujinxin.nextep.config

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object UpdateChecker {
    const val REPOSITORY_URL = "https://github.com/yishisanren/NeXtep"
    const val RELEASES_URL = "$REPOSITORY_URL/releases"
    private const val API_URL = "https://api.github.com/repos/yishisanren/NeXtep/releases?per_page=100"
    private const val MAX_BYTES = 2 * 1024 * 1024

    data class Release(val version: String, val notes: String, val downloadUrl: String)

    // Returns null only after a valid response establishes that no newer version exists.
    fun check(endpoint: String, installedCode: Long, installedName: String): Release? {
        if (endpoint.isNotBlank()) {
            val json = JSONObject(fetch(endpoint))
            val code = json.getLong("versionCode")
            require(code > 0) { "无效的服务器版本号" }
            val release = Release(
                json.getString("versionName").also { require(it.isNotBlank()) },
                json.optString("releaseNotes", "").take(16000),
                requireHttps(json.getString("downloadUrl")),
            )
            return release.takeIf { code > installedCode }
        }

        val current = versionParts(installedName) ?: error("当前版本格式无法比较，请查看下载页面")
        val releases = JSONArray(fetch(API_URL))
        val candidates = (0 until releases.length()).mapNotNull { index ->
            val json = releases.getJSONObject(index)
            if (json.optBoolean("draft")) return@mapNotNull null
            val tag = json.getString("tag_name")
            val version = versionParts(tag) ?: return@mapNotNull null
            // Include GitHub pre-releases: NeXtep distributes experimental builds this way.
            val assets = json.optJSONArray("assets") ?: return@mapNotNull null
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) && it.optString("state") == "uploaded" }
            if (apks.isEmpty()) return@mapNotNull null
            // Multiple APK variants need the user's choice on the release page.
            val download = if (apks.size == 1) apks.single().getString("browser_download_url")
                else json.getString("html_url")
            version to Release(tag, json.optString("body", "").take(16000), requireHttps(download))
        }
        require(candidates.isNotEmpty()) { "没有找到可用版本，请查看下载页面" }
        val latest = candidates.maxWithOrNull { a, b -> compareVersions(a.first, b.first) }!!
        return latest.second.takeIf { compareVersions(latest.first, current) > 0 }
    }

    internal fun versionParts(value: String): List<Long>? {
        // Tags are vX.Y.Z or vX.Y.Z-workbench.N; reject unknown schemes rather than guess ordering.
        val match = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-workbench\\.(\\d+))?$").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { if (it.isEmpty()) 0L else it.toLongOrNull() ?: return null }
    }

    internal fun compareVersions(left: List<Long>, right: List<Long>): Int {
        for (index in left.indices) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    fun requireHttps(value: String): String {
        val url = URL(value)
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
            "更新地址必须是有效的 HTTPS 地址"
        }
        return value
    }

    private fun fetch(address: String): String {
        var target = requireHttps(address)
        repeat(4) {
            val connection = URL(target).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "NeXtep-UpdateChecker")
                when (val status = connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: error("更新服务重定向无效")
                        target = requireHttps(URL(URL(target), location).toString())
                    }
                    200 -> return connection.inputStream.use { input ->
                        val bytes = input.readNBytes(MAX_BYTES + 1)
                        require(bytes.size <= MAX_BYTES) { "更新信息过大" }
                        bytes.toString(Charsets.UTF_8)
                    }
                    403, 429 -> throw IOException("更新服务暂时限制访问，请稍后重试或打开下载页面")
                    else -> throw IOException("更新服务不可用（HTTP $status）")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("更新服务重定向次数过多")
    }
}
