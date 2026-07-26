package com.screensmith.android.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Owns the currently-imported project bundle (the .zip exported by the
 * designer's `lib/android-export.ts`): extracting it to app-internal
 * storage, parsing `project.json`, and resolving the other bundle-relative
 * asset paths under `assets/` (PNG backgrounds, TTF fonts, icon SVGs) that
 * the renderer (M4) needs.
 */
class ProjectRepository(private val context: Context) {
    private val projectDir = File(context.filesDir, "project")
    private val json = Json { ignoreUnknownKeys = true }

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    init {
        loadFromDisk()
    }

    /** Extracts [uri] (a bundle .zip picked via Storage Access Framework) and loads it. */
    suspend fun importBundle(uri: Uri): Result<Project> = withContext(Dispatchers.IO) {
        try {
            // Clear any previously-imported bundle first - a smaller new
            // bundle must not leave stale files an old project.json (now
            // gone) used to reference.
            if (projectDir.exists()) projectDir.deleteRecursively()
            projectDir.mkdirs()

            val resolver = context.contentResolver
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val outFile = File(projectDir, entry.name)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open the selected file"))

            val loaded = loadFromDisk() ?: return@withContext Result.failure(
                IllegalStateException("Bundle has no project.json"),
            )
            Result.success(loaded)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Absolute [File] for a bundle-relative path from project.json (e.g. "assets/screen-1.png"). */
    fun assetFile(relativePath: String): File = File(projectDir, relativePath)

    private fun loadFromDisk(): Project? {
        val projectJsonFile = File(projectDir, "project.json")
        if (!projectJsonFile.exists()) {
            _project.value = null
            return null
        }
        return try {
            val parsed = json.decodeFromString(Project.serializer(), projectJsonFile.readText())
            _project.value = parsed
            parsed
        } catch (e: Exception) {
            _project.value = null
            null
        }
    }
}
