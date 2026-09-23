package com.schaltli.android.mqtt

import android.util.Log
import com.schaltli.android.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32

/**
 * Takes a deploy the way every board does: a retained `deploy` names a zip,
 * the device fetches it over plain HTTP, checks it and installs it, saying
 * where it is on `deploy-status` the whole way
 * (docs/device-contract.md SS4's deploy-flow topics).
 *
 * Until 2026-09-21 the designer hid its deploy button for an Android project
 * - "no self-update firmware path exists there yet" - and a project reached
 * this app as a file through the picker. The phone announces itself now, so
 * the half that was missing is this one.
 *
 * What it does *not* do is reboot. A board has to; a phone can put the new
 * screen up under whoever is looking at it, which is the point of deploying
 * to one (docs/2026-09-21-android-self-announce.md, decision A). The
 * terminal state it reports is therefore `applied`, not `rebooting`.
 */
class DeployReceiver(
    private val projectRepository: ProjectRepository,
    private val publishStatus: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "DeployReceiver"
        /** Matches the firmware's own guard: a second deploy while one runs is refused, not queued. */
        private const val BUSY = "busy"
    }

    // Deploys arrive on the MQTT client's own threads, and on more than one
    // of them when a retained message is re-delivered. Plain fields were read
    // and written from several at once: two could each find `running` false
    // and both start downloading the same bundle into the same staging
    // directory.
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var runningDeployId: String? = null
    @Volatile private var lastDeployId: String? = null

    /** Handles one retained `deploy` payload. Safe to call again with the same one. */
    fun onDeploy(payload: String) {
        val message = try {
            JSONObject(payload)
        } catch (e: Exception) {
            Log.w(TAG, "deploy payload is not JSON", e)
            return
        }
        // An empty retained payload is how the designer clears a deploy; an
        // id-less one is not something to act on either.
        val deployId = message.optString("deployId").ifEmpty { return }
        val url = message.optString("url").ifEmpty { return }
        val crc32 = message.optLong("crc32", -1L)

        // Retained, so it arrives again on every reconnect - and installing
        // the same project twice would be a screen that flickers back to
        // where it was every time the network hiccups.
        if (deployId == lastDeployId) {
            Log.i(TAG, "deploy $deployId already applied")
            return
        }
        // The same deploy arriving again while it is being installed is not
        // another deploy. Saying `busy` to it told the designer's dialog that
        // something else had the device, over a progress bar that was mid-
        // download - and the retained message is re-delivered often enough
        // that this was the usual outcome, not a rare one.
        if (deployId == runningDeployId) return
        if (!running.compareAndSet(false, true)) {
            report(deployId, BUSY)
            return
        }
        runningDeployId = deployId
        scope.launch {
            try {
                report(deployId, "downloading", 0)
                val bytes = download(url) { percent -> report(deployId, "downloading", percent) }
                report(deployId, "download_complete", 100)

                report(deployId, "verifying")
                if (crc32 >= 0) {
                    val actual = CRC32().apply { update(bytes) }.value
                    if (actual != crc32) {
                        report(deployId, "error", error = "Checksum mismatch")
                        return@launch
                    }
                }

                report(deployId, "applying")
                val result = projectRepository.importBytes(bytes)
                if (result.isFailure) {
                    report(deployId, "error", error = result.exceptionOrNull()?.message ?: "Could not install")
                    return@launch
                }

                lastDeployId = deployId
                // Not "rebooting": the screen is already the new one.
                report(deployId, "applied", 100)
                Log.i(TAG, "deploy $deployId applied")
            } catch (e: Exception) {
                Log.w(TAG, "deploy failed", e)
                report(deployId, "error", error = e.message ?: e::class.java.simpleName)
            } finally {
                runningDeployId = null
                running.set(false)
            }
        }
    }

    private suspend fun download(url: String, onProgress: (Int) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Download failed (${connection.responseCode})")
                }
                val total = connection.contentLength
                val out = java.io.ByteArrayOutputStream(if (total > 0) total else 64 * 1024)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var read = input.read(buffer)
                    var last = -1
                    while (read >= 0) {
                        out.write(buffer, 0, read)
                        if (total > 0) {
                            // The designer's progress bar reads this number
                            // directly, so it is reported as it moves - but
                            // only when it actually changes, since every
                            // report is a publish.
                            val percent = (out.size().toLong() * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != last) {
                                last = percent
                                onProgress(percent)
                            }
                        }
                        read = input.read(buffer)
                    }
                }
                out.toByteArray()
            } finally {
                connection.disconnect()
            }
        }

    private fun report(deployId: String, state: String, percent: Int? = null, error: String? = null) {
        val json = JSONObject()
            .put("deployId", deployId)
            .put("state", state)
        if (percent != null) json.put("percent", percent)
        if (error != null) json.put("error", error)
        publishStatus(json.toString())
    }
}
