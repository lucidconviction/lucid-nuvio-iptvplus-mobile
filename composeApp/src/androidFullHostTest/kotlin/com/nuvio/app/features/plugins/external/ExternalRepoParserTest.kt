package com.nuvio.app.features.plugins.external

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalRepoParserTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesRepoManifestAndRelativePluginList() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"name":"Example","description":"test","pluginLists":["plugins.json"]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """[
                  {"name":"Provider","internalName":"provider","version":2,"status":1,
                   "tvTypes":["Movie","TvSeries"],"url":"provider.cs3",
                   "fileHash":"sha256-abc","iconUrl":"icon.png"}
                ]""",
            ),
        )

        val result = ExternalRepoParser.tryParse(server.url("/repo.json").toString())
        assertNotNull(result)
        assertEquals("Example", result!!.name)
        assertEquals(1, result.plugins.size)
        val plugin = result.plugins.single()
        assertEquals("provider", plugin.internalName)
        assertEquals(server.url("/provider.cs3").toString(), plugin.url)
        assertEquals(server.url("/icon.png").toString(), plugin.iconUrl)
        assertEquals(listOf("movie", "tv"), cloudStreamContentTypes(plugin.tvTypes))
    }

    @Test
    fun parsesDirectPluginsArray() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"internalName":"one","name":"One","version":1,"url":"one.cs3"}]""",
            ),
        )

        val result = ExternalRepoParser.tryParse(server.url("/plugins.json").toString())
        assertNotNull(result)
        assertEquals(server.url("/one.cs3").toString(), result!!.plugins.single().url)
    }

    @Test
    fun githubShareLinksResolveToRawContent() {
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/HEAD/repo.json",
            cloudStreamManifestUrl("https://github.com/owner/repo"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/main/plugins.json",
            cloudStreamManifestUrl("https://github.com/owner/repo/blob/main/plugins.json"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/main/plugins.json",
            cloudStreamManifestUrl("https://github.com/owner/repo/raw/main/plugins.json"),
        )
    }

    @Test
    fun cloudStreamShareSchemeAndRelativePaths() {
        assertEquals(
            "https://example.com/repo",
            cloudStreamManifestUrl("cloudstreamrepo://example.com/repo"),
        )
        assertEquals(
            "https://example.com/a/plugins/b.cs3",
            joinUrl("https://example.com/a/repo.json", "plugins/b.cs3"),
        )
        assertEquals(
            "https://example.com/plugins.json",
            joinUrl("https://example.com/a/repo.json", "../plugins.json"),
        )
        assertNull(joinUrl("not a url", "x"))
    }
}
