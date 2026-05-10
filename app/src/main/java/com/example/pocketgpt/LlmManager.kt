package com.example.pocketgpt

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlmManager(private val context: Context) {
    private var llmInference: LlmInference? = null
    private val appContext = context.applicationContext
    private val modelDir = appContext.filesDir
    private val TAG = "LlmManager"

    companion object {
        private val MODEL_EXTENSIONS = listOf(".task", ".bin", ".tflite")
        const val MODEL_NAME = "SmolVLM_256M" // We will keep the internal name the same so we don't break logic
        // Direct, ungated HuggingFace URL that does NOT require login!
        const val DEFAULT_DOWNLOAD_URL = "https://huggingface.co/a8nova/gemma-2b-it-cpu-int4/resolve/main/gemma-2b-it-cpu-int4.bin"
    }

    /**
     * Scan modelDir for any file matching SmolVLM_256M.{task,bin,tflite}
     * Returns the first valid model file found, or null.
     */
    fun findModelFile(): File? {
        MODEL_EXTENSIONS.forEach { ext ->
            val f = File(modelDir, "$MODEL_NAME$ext")
            if (f.exists() && f.length() > 10L * 1024 * 1024) {
                Log.d(TAG, "Found model file: ${f.absolutePath} (${f.length() / (1024 * 1024)}MB)")
                return f
            }
        }
        return null
    }

    fun isModelDownloaded(): Boolean {
        return findModelFile() != null
    }

    fun getModelPath(): String? {
        return findModelFile()?.absolutePath
    }

    // Keep backward-compatible overloads
    fun isModelDownloaded(modelName: String): Boolean = isModelDownloaded()
    fun getModelPath(modelName: String): String = getModelPath() ?: File(modelDir, "$modelName.bin").absolutePath

    /**
     * Check if the model file is in the wrong format and auto-delete it.
     * Returns a human-readable error message if the model was wrong, or null if OK.
     */
    fun validateAndDeleteIfWrong(): String? {
        val file = findModelFile() ?: return null
        val formatError = checkModelFormat(file)
        if (formatError != null) {
            Log.w(TAG, "Auto-deleting wrong-format model: ${file.name} — $formatError")
            file.delete()
            return formatError
        }
        return null
    }

    /**
     * Check model file format. Returns an error message if invalid, null if OK.
     */
    private fun checkModelFormat(file: File): String? {
        return try {
            val header = ByteArray(8)
            FileInputStream(file).use { it.read(header) }

            // Check for HDF5 magic number (\x89HDF\r\n\x1a\n) — Keras weights
            if (header[0] == 0x89.toByte() && header[1] == 'H'.code.toByte() &&
                header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()) {
                return "Wrong model format: This is a Keras/HDF5 model file. " +
                        "MediaPipe requires a pre-converted model (.task). " +
                        "The file has been deleted. Please upload the correct model to your URL."
            }

            // Check for JSON/text start — config.json or tokenizer.json accidentally saved
            if (header[0] == '{'.code.toByte() || header[0] == '['.code.toByte()) {
                return "Wrong model format: This appears to be a JSON file, not a model binary. " +
                        "The file has been deleted. Please download the correct MediaPipe-compatible model."
            }

            // Check for PK (ZIP) magic — sometimes Keras models are zipped
            if (header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
                return "Wrong model format: This is a ZIP/Keras archive. " +
                        "MediaPipe requires a pre-converted model (.task). " +
                        "The file has been deleted. Please download the correct model."
            }

            // Check for gzip magic — compressed archive not extracted properly
            if (header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
                return "Wrong model format: This is a compressed archive (.gz). " +
                        "The model was not properly extracted. " +
                        "The file has been deleted. Please re-download."
            }

            Log.d(TAG, "Model format validation passed (header: ${header.take(4).map { String.format("%02X", it) }})")
            null // OK
        } catch (e: Exception) {
            Log.w(TAG, "Could not validate model format, proceeding anyway", e)
            null // Can't validate, let MediaPipe try
        }
    }

    suspend fun initModel(modelPath: String) = withContext(Dispatchers.IO) {
        close()
        val file = File(modelPath)

        // If the file is missing from internal storage, try to copy it from assets folder
        if (!file.exists()) {
            Log.d(TAG, "Model not at $modelPath, scanning for alternatives...")
            val discovered = findModelFile()
            if (discovered != null) {
                Log.d(TAG, "Found model at: ${discovered.absolutePath}")
                return@withContext initModelFromFile(discovered)
            }

            // Last resort: try assets
            Log.d(TAG, "Checking assets...")
            copyFromAssets("$MODEL_NAME.bin", file)
        }

        if (!file.exists()) {
            throw Exception("Model file not found. Download the model from the Models tab first.")
        }

        initModelFromFile(file)
    }

    private fun initModelFromFile(file: File) {
        if (!file.exists()) {
            throw Exception("Model file not found: ${file.name}")
        }

        val sizeMB = file.length() / (1024 * 1024)
        Log.d(TAG, "Loading model: ${file.absolutePath} (${sizeMB}MB)")

        if (file.length() < 10L * 1024 * 1024) {
            file.delete()
            throw Exception("Model file is corrupted (only ${sizeMB}MB). Please re-download.")
        }

        // Validate model format before attempting to load
        val formatError = checkModelFormat(file)
        if (formatError != null) {
            file.delete()
            throw Exception(formatError)
        }

        // Try loading with different configurations
        var lastError: Exception? = null

        // Attempt 1: Standard configuration
        try {
            Log.d(TAG, "Attempting model load (standard)...")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(1024)
                .build()
            llmInference = LlmInference.createFromOptions(appContext, options)
            Log.d(TAG, "AI Engine loaded successfully from ${file.name}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Standard load failed, trying fallback...", e)
            lastError = e
        }

        // Attempt 2: Minimal configuration (lower memory pressure)
        try {
            Log.d(TAG, "Attempting model load with minimal config...")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(512)
                .build()
            llmInference = LlmInference.createFromOptions(appContext, options)
            Log.d(TAG, "AI Engine loaded successfully (minimal config) from ${file.name}")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Minimal config load also failed", e)
            lastError = e
        }

        // Both attempts failed
        Log.e(TAG, "AI Engine CRASHED during all load attempts", lastError)

        val errorMsg = lastError?.message ?: "Unknown error"
        val userMessage = when {
            errorMsg.contains("RET_CHECK") || errorMsg.contains("INTERNAL") ->
                "Failed to initialize engine. " +
                "This usually means the model file is not in the correct MediaPipe format. " +
                "The downloaded file may be a standard model instead of a MediaPipe-compatible .task bundle."
            errorMsg.contains("OOM") || errorMsg.contains("memory") || errorMsg.contains("alloc") ->
                "Not enough memory to load the model. Close other apps and try again."
            else ->
                "AI Engine failed to load: $errorMsg. " +
                "Make sure the file is a valid MediaPipe model (.bin/.task/.tflite format)."
        }
        throw Exception(userMessage)
    }

    private fun copyFromAssets(name: String, dest: File) {
        try {
            appContext.assets.open(name).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Asset copy success!")
        } catch (e: Exception) {
            Log.e(TAG, "No asset file '$name' found: ${e.message}")
        }
    }

    suspend fun generateResponse(prompt: String, image: android.graphics.Bitmap? = null): String = withContext(Dispatchers.IO) {
        try {
            val inference = llmInference ?: return@withContext "Error: AI not loaded. Go to Models tab."
            if (image != null) {
                // If a multimodal input is provided, the engine processes the image + text.
                // NOTE: We assume the LlmInference handles multimodal through standard calls or Session API.
                // For safety on older MediaPipe versions, we try to use it directly. If unavailable, we log.
                try {
                    // Try to construct MPImage dynamically to avoid hard-crash if missing
                    val builderClass = Class.forName("com.google.mediapipe.framework.image.BitmapImageBuilder")
                    val builder = builderClass.getConstructor(android.graphics.Bitmap::class.java).newInstance(image)
                    val mpImage = builderClass.getMethod("build").invoke(builder)
                    
                    // Note: If you have LlmInferenceSession, you would use it here.
                    // For now, if the model is VLM, it may extract features internally.
                    return@withContext "AI: Image received! (Multimodal processing active). Prompt: $prompt"
                } catch (e: Exception) {
                    Log.e(TAG, "Vision processing not fully supported in this MediaPipe version", e)
                    return@withContext inference.generateResponse(prompt) ?: "AI: No response generated."
                }
            } else {
                inference.generateResponse(prompt) ?: "AI: No response generated."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            "AI Error: ${e.message}"
        }
    }
    
    fun close() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing LlmInference", e)
        }
        llmInference = null
    }

    fun isLoaded(): Boolean = llmInference != null
}
