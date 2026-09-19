package com.futo.platformplayer.e2e.support

import android.content.Context
import com.futo.platformplayer.api.http.server.ManagedHttpServer
import com.futo.platformplayer.api.http.server.handlers.HttpFileHandler
import com.futo.platformplayer.api.media.platforms.js.SourcePluginConfig
import com.futo.platformplayer.states.StatePlugins
import com.futo.platformplayer.stores.FragmentedStorage
import com.futo.platformplayer.stores.StringArrayStorage
import java.io.File

/**
 * The deterministic content the suite runs against: a JS source plugin (assets/fixture/FixtureScript.js)
 * whose videos stream a tiny local clip from an in-process HTTP server. No network access is involved.
 *
 * Catalogue (see FixtureScript.js): "Video 01".."Video 15"; Home = 01-08; Alpha Channel = 01-06;
 * Beta Channel = 07-12; recommendations for any video = 13-15. Every video is 60 seconds long.
 */
object Fixture {
    const val PLUGIN_ID = "e2e0f1a7-0000-4000-8000-000000000001"
    const val VIDEO_COUNT = 15
    const val VIDEO_URL_PREFIX = "https://fixture.test/watch/"

    private var server: ManagedHttpServer? = null

    val baseUrl: String get() = "http://127.0.0.1:${server!!.port}"

    fun videoName(n: Int) = "Video %02d".format(n)
    fun videoUrl(n: Int) = VIDEO_URL_PREFIX + "%02d".format(n)

    fun startMediaServer(target: Context, test: Context) {
        val dir = File(target.cacheDir, "fixture").apply { mkdirs() }
        val clip = copyAsset(test, "fixture/clip60.mp4", File(dir, "clip60.mp4"))
        val thumb = copyAsset(test, "fixture/thumb.png", File(dir, "thumb.png"))

        val s = ManagedHttpServer(0)
        s.start()
        s.addHandler(HttpFileHandler("GET", "/thumb.png", "image/png", thumb.absolutePath), true)
        for (n in 1..VIDEO_COUNT)
            s.addHandler(HttpFileHandler("GET", "/media/%02d.mp4".format(n), "video/mp4", clip.absolutePath), true)
        server = s
    }

    fun stopMediaServer() {
        server?.stop()
        server = null
    }

    /** Installs the fixture plugin and makes it the only enabled source. Must run before MainActivity starts. */
    fun installPlugin(test: Context) {
        val config = SourcePluginConfig.fromJson(readAsset(test, "fixture/FixtureConfig.json"))
        val script = readAsset(test, "fixture/FixtureScript.js").replace("__FIXTURE_BASE__", baseUrl)
        StatePlugins.instance.createPlugin(config, script, reinstall = true)?.let { throw it }

        FragmentedStorage.get<StringArrayStorage>("enabledClients").apply {
            set(PLUGIN_ID)
            saveBlocking()
        }
    }

    private fun readAsset(test: Context, path: String): String =
        test.assets.open(path).bufferedReader().use { it.readText() }

    private fun copyAsset(test: Context, path: String, dest: File): File {
        test.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }
}
