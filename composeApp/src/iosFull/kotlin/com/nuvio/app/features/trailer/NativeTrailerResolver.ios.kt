package com.nuvio.app.features.trailer

internal actual object NativeTrailerResolver {
    actual suspend fun resolve(youtubeUrl: String): TrailerPlaybackSource? = null
}
