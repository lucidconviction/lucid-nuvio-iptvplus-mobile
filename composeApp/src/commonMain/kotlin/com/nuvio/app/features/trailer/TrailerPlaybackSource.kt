package com.nuvio.app.features.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    /** Request headers the resolved URLs require (for example YouTube's client user agent). */
    val headers: Map<String, String> = emptyMap(),
)
