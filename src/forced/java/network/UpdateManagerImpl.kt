package network

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.fury.messenger.utils.UpdateResponse
import com.fury.messenger.utils.uploadsDir
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateManagerImpl(
    private val context: Context,
    private val scope: CoroutineScope
) : UpdateManager {

    private var updateFile: File? = null
    var updateInfo: UpdateResponse? = null

    private val _updateReady = MutableStateFlow(false)
    override val updateReady = _updateReady.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    override val updateAvailable = _updateAvailable.asStateFlow()

    private val _updateProgress = MutableStateFlow(0f)
    override val updateProgress = _updateProgress.asStateFlow()

    private var downloadJob: Job? = null

    companion object {
        private const val UPDATE_URL = "https://furymsg.ru/update"
    }

    override fun startUpdateFlow(activity: android.app.Activity) {
        if (downloadJob?.isActive != true && updateInfo != null) {
            downloadJob = scope.launch(Dispatchers.IO) {
                handleUpdateDownload(updateInfo!!)
            }
        }
    }

    override fun stop() {
        downloadJob?.cancel()
    }

   override fun performInstall() {
        if (!_updateReady.value) return
        val file = updateFile ?: return
        if (!file.exists()) return

        // Check Unknown Sources Permission logic should be handled by Activity if needed,
        // but here we fire the intent.
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("UpdateManager", "Install failed: ${e.message}")
        }
    }

    override fun checkForUpdate() {
        // If we already have a ready file, don't check again until installed
        if (_updateReady.value) return
        if (downloadJob?.isActive == true) return

        try {
            val url = URL(UPDATE_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"

            if (connection.responseCode != 200) return

            val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
            updateInfo = Gson().fromJson(jsonStr, UpdateResponse::class.java) ?: return

            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
            } catch (_: Exception) {
                "1.0"
            }

            if (compareVersions(updateInfo?.appver!!, currentVersion) > 0) {
                // New version found
                _updateAvailable.value = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleUpdateDownload(info: UpdateResponse): Boolean {
        val fileName = "fury_update_${info.appver}.apk"
        val file = File(context.uploadsDir, fileName)
        Log.d("UpdateManager","handleUpdateDownload started.")
        // 1. Check existing file
        if (file.exists()) {
            val localHash = calculateFileHash(file)
            if (localHash.equals(info.checksum, ignoreCase = true)) {
                updateFile = file
                _updateProgress.value = 0f
                _updateReady.value = true // Signal UI
                Log.d("UpdateManager","File already downloaded, start install...")
                performInstall()
                return true
            } else {
                file.delete()
            }
        }

        // 2. Download
        _updateProgress.value = 0.001f
        try {
            Log.d("UpdateManager","Downloading: ${info.file}")

            val url = URL(info.file)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()

            if (connection.responseCode != 200) return false

            val input = connection.inputStream
            val output = FileOutputStream(file)

            val data = ByteArray(8192)
            var total: Long = 0
            val fileLength = connection.contentLength
            var count: Int

            while (input.read(data).also { count = it } != -1) {
                total += count
                output.write(data, 0, count)
                if (fileLength > 0) {
                    _updateProgress.value = total.toFloat() / fileLength
                }
            }

            output.flush()
            output.close()
            input.close()

            // 3. Verify
            val downloadedHash = calculateFileHash(file)
            if (downloadedHash.equals(info.checksum, ignoreCase = true)) {
                updateFile = file
                _updateProgress.value = 0f
                _updateReady.value =true // Signal UI
                performInstall()
                return true
            } else {
                file.delete()
                _updateProgress.value = 0f
                return false
            }

        } catch (e: Exception) {
            e.printStackTrace()
            file.delete()
            _updateProgress.value = 0f
            return false
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val length = maxOf(parts1.size, parts2.size)

        for (i in 0 until length) {
            val p1 = if (i < parts1.size) parts1[i] else 0
            val p2 = if (i < parts2.size) parts2[i] else 0
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun calculateFileHash(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val fis = FileInputStream(file)
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
            fis.close()
            val bytes = digest.digest()
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }
}