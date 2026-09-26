package com.schaltli.android.data

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
import kotlinx.serialization.json.JsonElement

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

    private val _darkProject = MutableStateFlow<Project?>(null)

    /**
     * The same project as it is drawn while the installation's theme is dark
     * ([darkVariantOf]): decoded once per load, beside [project], so that a
     * flip of `schaltli/state/theme` is a choice between two ready projects
     * and not a re-parse. Equal to [project] where the bundle carries no dark
     * fields.
     */
    val darkProject: StateFlow<Project?> = _darkProject.asStateFlow()

    private val _installation = MutableStateFlow(0L)

    /**
     * Counts installations, and is the reason anything drawn from a bundle
     * file gets looked at again after a deploy.
     *
     * [project] alone cannot say. A StateFlow drops a value equal to the one
     * it holds, so re-installing a project whose project.json is unchanged
     * emits nothing at all - and a bundle can change without its project.json
     * changing, because the background PNG is a separate file. This counter
     * changes on every install, whatever is in the zip.
     */
    val installation: StateFlow<Long> = _installation.asStateFlow()

    init {
        loadFromDisk()
    }

    /** Extracts [uri] (a bundle .zip picked via Storage Access Framework) and loads it. */
    suspend fun importBundle(uri: Uri): Result<Project> = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Could not open the selected file"))
            input.use { unpackAndSwap(it) }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The same thing for a bundle that arrived over the air rather than
     * through the file picker - a deploy from the designer
     * (docs/2026-09-21-android-self-announce.md in that repo).
     */
    suspend fun importBytes(bytes: ByteArray): Result<Project> = withContext(Dispatchers.IO) {
        try {
            bytes.inputStream().use { unpackAndSwap(it) }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unpacks a bundle beside the live one and only then lets it take its
     * place.
     *
     * Never into the directory being drawn from: a deploy lands while
     * somebody is looking at the screen, and a half-written project is a
     * screen of missing icons and a parse error. The swap is two renames -
     * as close to atomic as a filesystem gets - so the worst interruption
     * leaves either the old project or the new one, never half of each.
     *
     * Staging is emptied first rather than merged into, because a smaller
     * new bundle must not leave stale files that an old project.json used to
     * reference.
     */
    private fun unpackAndSwap(input: java.io.InputStream): Result<Project> {
        val staging = File(context.filesDir, "project.incoming")
        val previous = File(context.filesDir, "project.previous")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(staging, entry.name)
                    // A zip entry naming its way out of the directory it is
                    // unpacked into is the oldest trick there is, and this
                    // one arrives over the network now.
                    if (!outFile.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        staging.deleteRecursively()
                        return Result.failure(IllegalStateException("Bundle entry escapes the project directory"))
                    }
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (!File(staging, "project.json").exists()) {
            staging.deleteRecursively()
            return Result.failure(IllegalStateException("Bundle has no project.json"))
        }

        if (previous.exists()) previous.deleteRecursively()
        if (projectDir.exists() && !projectDir.renameTo(previous)) {
            staging.deleteRecursively()
            return Result.failure(IllegalStateException("Could not put the running project aside"))
        }
        if (!staging.renameTo(projectDir)) {
            // Put back what was there: better the old project than none.
            previous.renameTo(projectDir)
            staging.deleteRecursively()
            return Result.failure(IllegalStateException("Could not install the new project"))
        }
        previous.deleteRecursively()

        val loaded = loadFromDisk() ?: return Result.failure(IllegalStateException("Bundle has no project.json"))
        // After loadFromDisk, so that anything watching both sees the new
        // project rather than the old one under a new number.
        _installation.value += 1
        return Result.success(loaded)
    }

    /** Absolute [File] for a bundle-relative path from project.json (e.g. "assets/screen-1.png"). */
    fun assetFile(relativePath: String): File = File(projectDir, relativePath)

    private fun loadFromDisk(): Project? {
        val projectJsonFile = File(projectDir, "project.json")
        if (!projectJsonFile.exists()) {
            _darkProject.value = null
            _project.value = null
            return null
        }
        return try {
            val element = json.parseToJsonElement(projectJsonFile.readText())
            val parsed = json.decodeFromJsonElement(Project.serializer(), element)
            // Dark first: whoever reacts to a new [project] finds its dark
            // twin already there.
            _darkProject.value = json.decodeFromJsonElement(Project.serializer(), darkVariantOf(element))
            _project.value = parsed
            parsed
        } catch (e: Exception) {
            _darkProject.value = null
            _project.value = null
            null
        }
    }
}
