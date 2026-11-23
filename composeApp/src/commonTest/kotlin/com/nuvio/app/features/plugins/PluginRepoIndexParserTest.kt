package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginRepoIndexParserTest {
    @Test
    fun parsesStringsAndObjects() {
        val entries = PluginRepoIndexRepository.parse(
            """
            [
              "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json",
              {
                "url": "https://raw.githubusercontent.com/CranberrySoup/AniyomiCompatExtension/master/repo.json",
                "verified": true
              },
              {"url": "https://example.com/repo.json"}
            ]
            """.trimIndent(),
        )

        assertEquals(3, entries.size)
        assertEquals("recloudstream/extensions", entries[0].name)
        assertFalse(entries[0].verified)
        assertEquals("CranberrySoup/AniyomiCompatExtension", entries[1].name)
        assertTrue(entries[1].verified)
        assertEquals("example.com/repo.json", entries[2].name)
    }

    @Test
    fun ignoresInvalidAndDuplicateEntries() {
        val entries = PluginRepoIndexRepository.parse(
            """
            [
              "",
              {"verified": true},
              "https://example.com/repo.json",
              "https://example.com/repo.json"
            ]
            """.trimIndent(),
        )

        assertEquals(1, entries.size)
        assertEquals("https://example.com/repo.json", entries.single().url)
    }
}
