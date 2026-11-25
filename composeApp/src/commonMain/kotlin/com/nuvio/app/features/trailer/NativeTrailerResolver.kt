package com.nuvio.app.features.trailer

/**
 * Optional platform-native YouTube resolver. Android uses the bundled SmartTube engine,
 * which already carries YouTube's visitor/auth state and avoids the anonymous bot check.
 * Platforms without it return null and the HTTP extractor remains the only path.
 */
internal expect object NativeTrailerResolver {
    suspend fun resolve(youtubeUrl: String): TrailerPlaybackSource?
}
