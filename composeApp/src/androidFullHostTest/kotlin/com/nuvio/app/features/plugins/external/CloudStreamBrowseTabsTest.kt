package com.nuvio.app.features.plugins.external

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudStreamBrowseTabsTest {
    @Test
    fun realTabsAreUsedEvenWithoutHasMainPage() {
        val api = object : MainAPI() {
            override var name = "Test"
            override val hasMainPage = false
            override val mainPage = listOf(
                MainPageData("Movies", "movies", false),
                MainPageData("Series", "series", false),
            )
        }

        val tabs = cloudStreamBrowseTabs(api)
        assertEquals(listOf("Movies", "Series"), tabs.map { it.name })
    }

    @Test
    fun defaultPlaceholderIsNotACategoryWhenTheProviderHasNoHome() {
        val api = object : MainAPI() {
            override var name = "Search only"
            override val hasMainPage = false
        }

        assertTrue(cloudStreamBrowseTabs(api).isEmpty())
    }

    @Test
    fun blankDefaultTabIsKeptForProvidersThatDeclareAHome() {
        val api = object : MainAPI() {
            override var name = "Default home"
            override val hasMainPage = true
        }

        assertEquals(1, cloudStreamBrowseTabs(api).size)
    }

    @Test
    fun aBrokenMainPageGetterDoesNotCrashTheCatalog() {
        val api = object : MainAPI() {
            override var name = "Broken extension"
            override val hasMainPage = true
            override val mainPage: List<MainPageData>
                get() = throw IllegalStateException("provider hiccup")
        }

        assertTrue(cloudStreamBrowseTabs(api).isEmpty())
    }
}
