package com.scraft.glassreport.domain.translate

import java.io.IOException

/** Safe, provider-independent failure classification for shared translation backoff. */
open class TranslationException(
    message: String,
    val code: Int? = null,
) : IOException(message)
