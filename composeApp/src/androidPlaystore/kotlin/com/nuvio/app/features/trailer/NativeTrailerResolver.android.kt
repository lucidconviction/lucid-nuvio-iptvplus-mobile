package com.nuvio.app.features.trailer

/** The Play Store build does not play YouTube trailers in-app. */
internal actual object NativeTrailerResolver {
    actual suspend fun resolve(youtubeUrl: String): TrailerPlaybackSource? = null
}
