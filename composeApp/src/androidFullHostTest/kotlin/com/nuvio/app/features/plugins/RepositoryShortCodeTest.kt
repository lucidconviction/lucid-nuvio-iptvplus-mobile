package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryShortCodeTest {
    @Test
    fun recognizesShortCodes() {
        assertTrue(isRepositoryShortCode("cspr"))
        assertTrue(isRepositoryShortCode("0094"))
        assertTrue(isRepositoryShortCode("my-repo_1"))
    }

    @Test
    fun rejectsUrlsAndDomains() {
        assertFalse(isRepositoryShortCode(""))
        assertFalse(isRepositoryShortCode("https://example.com/repo.json"))
        assertFalse(isRepositoryShortCode("example.com/repo"))
        assertFalse(isRepositoryShortCode("repo.json"))
        assertFalse(isRepositoryShortCode("cloudstreamrepo://example.com/repo"))
    }
}
