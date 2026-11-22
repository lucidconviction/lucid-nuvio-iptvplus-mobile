package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.external.externalExtensionHost

/**
 * One-time acknowledgement for repositories that may contain executable
 * CloudStream DEX code. Nuvio JavaScript manifest URLs are exempt; anything
 * else (CloudStream repo lists, short codes, unknown URLs) asks once before
 * the first install.
 */
internal object CloudStreamConsent {
    fun isRequired(rawUrl: String): Boolean {
        if (!externalExtensionHost.isSupported) return false
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        val path = trimmed.substringBefore('?').substringBefore('#')
        // The existing JavaScript repository format is explicit about its manifest.
        if (path.endsWith("manifest.json", ignoreCase = true)) return false
        return true
    }

    fun isAccepted(): Boolean = PluginStorage.hasAcceptedCloudStreamConsent()

    fun accept() {
        PluginStorage.setCloudStreamConsentAccepted()
    }
}
