package com.nuvio.app.features.trailer

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }

    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? {
        if (youtubeUrl.isBlank()) return null
        // Prefer the platform engine: it carries YouTube's visitor/auth state, while the
        // anonymous HTTP player API is regularly answered with "confirm you're not a bot".
        NativeTrailerResolver.resolve(youtubeUrl)?.let { return it }
        return extractor.extractPlaybackSource(youtubeUrl)
    }

    actual suspend fun resolveSingleUrlFromYouTubeUrl(youtubeUrl: String): String? {
        if (youtubeUrl.isBlank()) return null
        NativeTrailerResolver.resolve(youtubeUrl)
            ?.takeIf { it.audioUrl == null }
            ?.videoUrl
            ?.let { return it }
        return extractor.extractSingleUrl(youtubeUrl)
    }
}