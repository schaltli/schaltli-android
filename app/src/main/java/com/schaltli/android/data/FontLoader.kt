package com.schaltli.android.data

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * Loads a bundled TTF file (see [ProjectRepository.assetFile]) into a
 * Compose [FontFamily], cached by absolute path so the same font file isn't
 * parsed from disk again for every object that uses it.
 */
object FontLoader {
    private val cache = mutableMapOf<String, FontFamily>()

    fun load(file: File): FontFamily? {
        if (!file.exists()) return null
        return cache.getOrPut(file.absolutePath) {
            FontFamily(Font(file))
        }
    }
}
