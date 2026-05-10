package com.example.pocketgpt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.*
import java.util.concurrent.TimeUnit

class ModelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "model_pipeline_channel"
    private val TAG = "ModelWorker"

    companion object {
        private val MODEL_EXTENSIONS = listOf(".task", ".bin", ".tflite")
        const val MODEL_NAME = "SmolVLM_256M"

        /** Find the extracted model file in the given directory. */
        fun findModelFile(dir: File): File? {
            MODEL_EXTENSIONS.forEach { ext ->
                val f = File(dir, "$MODEL_NAME$ext")
                if (f.exists() && f.length() > 10 * 1024 * 1024) return f
            }
            return null
        }
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val destDir = applicationContext.filesDir

        createNotificationChannel()
        setForeground(createForegroundInfo(0, "Preparing pipeline..."))

        try {
            // Check if model already exists
            val existingModel = findModelFile(destDir)
            if (existingModel != null) {
                Log.d(TAG, "MODEL ALREADY EXISTS: ${existingModel.absolutePath}")
                updateState(ModelState.COMPLETED)
                return Result.success(workDataOf("model_path" to existingModel.absolutePath))
            }

            // Determine if URL points directly to a model file or an archive
            val isDirectModel = MODEL_EXTENSIONS.any { url.endsWith(it, ignoreCase = true) }
            val downloadFile = if (isDirectModel) {
                // If it's a direct URL, save it as the correct extension. Default to .task
                val ext = MODEL_EXTENSIONS.firstOrNull { url.endsWith(it, ignoreCase = true) } ?: ".task"
                File(destDir, "$MODEL_NAME$ext")
            } else {
                // Store archive in filesDir (NOT cacheDir — Android can wipe cache anytime)
                File(destDir, "temp_archive.tar.gz")
            }

            // STEP 1: DOWNLOAD
            Log.d(TAG, "DOWNLOAD → STARTING: $url")
            if (isDownloadComplete(url, downloadFile)) {
                Log.d(TAG, "DOWNLOAD → ALREADY COMPLETE (${downloadFile.length()} bytes)")
            } else {
                downloadWithResume(url, downloadFile)
            }
            Log.d(TAG, "DOWNLOAD → COMPLETE (${downloadFile.length()} bytes)")

            // STEP 2: EXTRACT (if archive)
            var modelFile: File? = null
            if (!isDirectModel) {
                Log.d(TAG, "EXTRACT → STARTING")
                updateState(ModelState.EXTRACTING)
                setForeground(createForegroundInfo(0, "Extracting AI model..."))

                modelFile = extractModelFromArchive(downloadFile, destDir)

                // Clean up the archive to free space
                downloadFile.delete()
                Log.d(TAG, "EXTRACT → Archive deleted to free space")

                if (modelFile == null || !modelFile.exists()) {
                    Log.e(TAG, "EXTRACT → FAILED: No model file found in archive")
                    updateState(ModelState.FAILED)
                    return Result.failure(
                        workDataOf("error" to "No model file (.task/.bin) found in archive. The archive may not contain a MediaPipe-compatible model.")
                    )
                }
                Log.d(TAG, "EXTRACT → COMPLETE: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            } else {
                modelFile = downloadFile
            }

            // STEP 3: VERIFY
            if (modelFile.exists() && modelFile.length() > 10 * 1024 * 1024) {
                Log.d(TAG, "VERIFY → MODEL READY at ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)}MB)")
                updateState(ModelState.COMPLETED)
                return Result.success(workDataOf("model_path" to modelFile.absolutePath))
            } else {
                Log.e(TAG, "VERIFY → FAILED: File missing or too small (${modelFile.length()} bytes)")
                modelFile.delete()
                updateState(ModelState.FAILED)
                return Result.failure(workDataOf("error" to "Model verification failed: file is too small or corrupted"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "PIPELINE → CRITICAL FAILURE", e)
            // Clean up temp files on failure
            File(destDir, "temp_archive.tar.gz").delete()
            File(destDir, "model_extract.tmp").delete()

            val errorMessage = when {
                e.message?.contains("401") == true -> "Auth Failed (401). The HuggingFace link might be private."
                e.message?.contains("404") == true -> "Not Found (404). Invalid URL."
                e.message?.contains("No space") == true -> "Not enough storage space. Need ~8GB free during download+extraction."
                else -> e.message ?: "Unknown Error"
            }
            updateState(ModelState.FAILED)
            return Result.failure(workDataOf("error" to errorMessage))
        }
    }

    private fun updateState(state: ModelState) {
        applicationContext.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
            .edit().putString("model_state", state.name).apply()
    }

    private fun buildClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun downloadWithResume(url: String, file: File) {
        val client = buildClient()
        var currentBytes = if (file.exists()) file.length() else 0L

        // Get total size
        val totalBytes = getRemoteFileSize(client, url)
        Log.d(TAG, "Remote file size: $totalBytes bytes, local: $currentBytes bytes")

        if (totalBytes > 0 && currentBytes >= totalBytes) {
            Log.d(TAG, "File already fully downloaded")
            return
        }

        updateState(ModelState.DOWNLOADING)

        // Build request with range header for resume
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; PocketGPT)")

        if (currentBytes > 0) {
            requestBuilder.header("Range", "bytes=$currentBytes-")
        }

        var response = client.newCall(requestBuilder.build()).execute()

        // Handle 416 Range Not Satisfiable → delete and restart from scratch
        if (response.code == 416) {
            Log.w(TAG, "416 Range Not Satisfiable → Restarting download from scratch")
            response.close()
            file.delete()
            currentBytes = 0L

            val freshRequest = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; PocketGPT)")
                .build()
            response = client.newCall(freshRequest).execute()
        }

        if (!response.isSuccessful && response.code != 206) {
            val code = response.code
            response.close()
            throw IOException("Server error $code")
        }

        val body = response.body ?: throw IOException("Empty response body")
        val inputStream = body.byteStream()
        val outputStream = FileOutputStream(file, currentBytes > 0) // Append if resuming

        try {
            val buffer = ByteArray(256 * 1024) // 256KB buffer
            var bytesRead: Int
            var lastUpdate = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                currentBytes += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdate > 1000) {
                    val progress = if (totalBytes > 0) (currentBytes * 100 / totalBytes).toInt() else -1
                    val mb = currentBytes / (1024 * 1024)
                    val totalMb = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
                    setProgress(workDataOf("progress" to progress, "status" to "downloading"))
                    setForegroundAsync(createForegroundInfo(progress, "Downloading: ${mb}MB / ${totalMb}MB"))
                    lastUpdate = now
                }
            }
            outputStream.flush()
            Log.d(TAG, "Download finished: ${currentBytes} bytes written")
        } finally {
            outputStream.close()
            inputStream.close()
            response.close()
        }
    }

    private fun getRemoteFileSize(client: OkHttpClient, url: String): Long {
        return try {
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Android; PocketGPT)")
                .build()
            val headResponse = client.newCall(headRequest).execute()
            val size = headResponse.header("Content-Length")?.toLongOrNull() ?: -1L
            headResponse.close()
            size
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request failed, will download without known total size", e)
            -1L
        }
    }

    private fun isDownloadComplete(url: String, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val client = buildClient()
        return try {
            val remoteSize = getRemoteFileSize(client, url)
            val isComplete = remoteSize > 0 && file.length() >= remoteSize
            Log.d(TAG, "Download check: local=${file.length()}, remote=$remoteSize, complete=$isComplete")
            isComplete
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractModelFromArchive(archive: File, destDir: File): File? {
        val tempFile = File(destDir, "model_extract.tmp")
        tempFile.delete() // Clean any previous temp file

        try {
            val inputBufferSize = 4 * 1024 * 1024 // 4MB input buffer
            val archiveStream = TarArchiveInputStream(
                GzipCompressorInputStream(
                    BufferedInputStream(FileInputStream(archive), inputBufferSize)
                )
            )

            archiveStream.use { tarIn ->
                var entry = tarIn.nextEntry
                var entryIndex = 0

                while (entry != null) {
                    val name = entry.name
                    val size = entry.size
                    val isDir = entry.isDirectory
                    Log.d(TAG, "TAR entry[$entryIndex]: name=$name, size=$size, isDir=$isDir")
                    entryIndex++

                    if (!isDir) {
                        val fileName = name.substringAfterLast('/')
                        val isModelByExtension = MODEL_EXTENSIONS.any {
                            fileName.endsWith(it, ignoreCase = true)
                        }
                        // Extract if: known model extension, OR size > 50MB, OR unknown size (size <= 0)
                        val isLikelyModel = isModelByExtension || size > 50 * 1024 * 1024 || size <= 0

                        if (isLikelyModel) {
                            Log.d(TAG, "EXTRACTING candidate: $name (size=$size)")
                            setForegroundAsync(createForegroundInfo(0, "Extracting: $fileName"))

                            val writeBuffer = ByteArray(1024 * 1024) // 1MB write buffer
                            var totalExtracted = 0L
                            var lastUpdate = 0L

                            BufferedOutputStream(FileOutputStream(tempFile), 4 * 1024 * 1024).use { out ->
                                var read: Int
                                while (tarIn.read(writeBuffer).also { read = it } != -1) {
                                    out.write(writeBuffer, 0, read)
                                    totalExtracted += read

                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate > 1000) {
                                        val mb = totalExtracted / (1024 * 1024)
                                        val progress = if (size > 0) (totalExtracted * 100 / size).toInt() else -1
                                        setForegroundAsync(createForegroundInfo(progress, "Extracting: ${mb}MB"))
                                        setProgress(workDataOf("progress" to progress, "status" to "extracting"))
                                        lastUpdate = now
                                    }
                                }
                                out.flush()
                            }

                            Log.d(TAG, "Extracted $totalExtracted bytes to temp file")

                            // If extracted file is very small, it's not the model — skip it
                            if (tempFile.length() < 10 * 1024 * 1024) {
                                Log.w(TAG, "Skipping small file: ${tempFile.length()} bytes")
                                tempFile.delete()
                                entry = tarIn.nextEntry
                                continue
                            }

                            // Determine output extension
                            val ext = when {
                                fileName.endsWith(".task", true) -> ".task"
                                fileName.endsWith(".tflite", true) -> ".tflite"
                                else -> ".bin"
                            }
                            val outputFile = File(destDir, "$MODEL_NAME$ext")
                            outputFile.delete() // Remove any old version

                            // Move temp → final (same filesystem, so rename should work)
                            if (tempFile.renameTo(outputFile)) {
                                Log.d(TAG, "Renamed to: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                                return outputFile
                            } else {
                                // Fallback: copy then delete
                                Log.w(TAG, "Rename failed, falling back to copy")
                                tempFile.inputStream().buffered(4 * 1024 * 1024).use { input ->
                                    FileOutputStream(outputFile).buffered(4 * 1024 * 1024).use { output ->
                                        input.copyTo(output, 1024 * 1024)
                                    }
                                }
                                tempFile.delete()
                                if (outputFile.exists() && outputFile.length() > 10 * 1024 * 1024) {
                                    Log.d(TAG, "Copied to: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                                    return outputFile
                                }
                            }

                            Log.e(TAG, "Failed to save extracted model file")
                            tempFile.delete()
                            return null
                        }
                    }
                    entry = tarIn.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction error", e)
            tempFile.delete()
            throw e
        }

        Log.e(TAG, "No suitable model file found in archive")
        return null
    }

    private fun createForegroundInfo(progress: Int, text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Pocket GPT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, if (progress < 0) 0 else progress, progress < 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "AI Pipeline", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
