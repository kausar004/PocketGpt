package com.example.pocketgpt

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pocketgpt.ui.theme.*
import androidx.core.view.WindowCompat
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.work.*
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var llmManager: LlmManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        llmManager = LlmManager(this)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.Light) }
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            
            var isModelInitializing by remember { mutableStateOf(false) }
            var isModelReady by remember { mutableStateOf(false) }
            // Shared error message that can be set from auto-load or manual init
            var modelErrorMessage by remember { mutableStateOf<String?>(null) }

            // Auto-load if file already exists on startup
            LaunchedEffect(Unit) {
                // First, check if existing model is in wrong format and auto-delete
                val formatError = llmManager.validateAndDeleteIfWrong()
                if (formatError != null) {
                    Log.e("PocketGPT", "Auto-deleted wrong model: $formatError")
                    modelErrorMessage = formatError
                    return@LaunchedEffect
                }

                if (llmManager.isModelDownloaded()) {
                    isModelInitializing = true
                    val path = llmManager.getModelPath()
                    if (path != null) {
                        try {
                            withContext(Dispatchers.IO) { llmManager.initModel(path) }
                            isModelReady = true
                        } catch (e: Exception) {
                            Log.e("PocketGPT", "Auto-load failed", e)
                            modelErrorMessage = e.message
                        }
                    }
                    isModelInitializing = false
                }
            }

            PocketGptTheme(themeMode = themeMode) {
                MainScreen(
                    themeMode = themeMode, 
                    onThemeChange = { themeMode = it },
                    llmManager = llmManager,
                    isModelReady = isModelReady,
                    isModelInitializing = isModelInitializing,
                    modelErrorMessage = modelErrorMessage,
                    onModelReady = { isModelReady = true },
                    onClearError = { modelErrorMessage = null },
                    onInitModel = { ->
                        scope.launch {
                            isModelInitializing = true
                            modelErrorMessage = null

                            // Validate format first
                            val formatError = llmManager.validateAndDeleteIfWrong()
                            if (formatError != null) {
                                modelErrorMessage = formatError
                                isModelInitializing = false
                                return@launch
                            }

                            val path = llmManager.getModelPath()
                            if (path != null) {
                                try {
                                    withContext(Dispatchers.IO) { llmManager.initModel(path) }
                                    isModelReady = true
                                    Toast.makeText(context, "AI ACTIVE!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    modelErrorMessage = e.message
                                    Toast.makeText(context, "Error: ${e.message?.take(100)}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                modelErrorMessage = "Model file not found. Please download first."
                                Toast.makeText(context, "Model file not found. Please download first.", Toast.LENGTH_LONG).show()
                            }
                            isModelInitializing = false
                        }
                    }
                )
            }
        }
    }
}

enum class ThemeMode { Light, Dark, Neon }

@Composable
fun PocketGptTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val colors = when (themeMode) {
        ThemeMode.Light -> lightColorScheme(primary = DarkGreen, secondary = AccentTeal, background = Background, surface = CardBackground, onBackground = TextPrimary, onSurface = TextPrimary)
        ThemeMode.Dark -> darkColorScheme(primary = LightGreen, secondary = AccentTeal, background = Color(0xFF121212), surface = Color(0xFF1E1E1E), onBackground = Color.White, onSurface = Color.White)
        ThemeMode.Neon -> darkColorScheme(primary = Color(0xFF00FFFF), secondary = Color(0xFF00BFFF), background = Color.Black, surface = Color(0xFF0A0A0A), onBackground = Color(0xFF00FFFF), onSurface = Color(0xFF00FFFF))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun MainScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    llmManager: LlmManager,
    isModelReady: Boolean,
    isModelInitializing: Boolean,
    modelErrorMessage: String?,
    onModelReady: () -> Unit,
    onClearError: () -> Unit,
    onInitModel: () -> Unit
) {
    var currentScreen by remember { mutableStateOf(Screen.Chat) }
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }
    val libraryHistory = remember { mutableStateListOf<LibraryItem>() }
    val scope = rememberCoroutineScope()
    var isGenerating by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = { BottomNavigationBar(currentScreen) { currentScreen = it } },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize().imePadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(currentScreen)
                Box(modifier = Modifier.weight(1f)) {
                    when (currentScreen) {
                        Screen.Chat -> ChatScreen(chatHistory, isGenerating, llmManager, libraryHistory)
                        Screen.Models -> ModelsScreen(
                            llmManager = llmManager,
                            onInitModel = onInitModel,
                            onModelReady = onModelReady,
                            isModelReady = isModelReady,
                            isModelInitializing = isModelInitializing,
                            externalErrorMessage = modelErrorMessage,
                            onClearError = onClearError
                        )
                        Screen.Library -> LibraryScreen(libraryHistory)
                        Screen.Settings -> SettingsScreen(themeMode, onThemeChange)
                    }
                }
            }
            if (currentScreen == Screen.Chat) {
                // The ChatInput is now inside ChatScreen to handle image picking securely.
            }
        }
    }
}

@Composable
fun ModelsScreen(
    llmManager: LlmManager,
    onInitModel: () -> Unit,
    onModelReady: () -> Unit,
    isModelReady: Boolean,
    isModelInitializing: Boolean,
    externalErrorMessage: String? = null,
    onClearError: () -> Unit = {}
) {
    val context = LocalContext.current
    var isModelDownloaded by remember { mutableStateOf(llmManager.isModelDownloaded()) }
    val workManager = WorkManager.getInstance(context)
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData("model_download").observeAsState(emptyList())
    val activeWork = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val succeededWork = workInfos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
    val failedWork = workInfos.firstOrNull { it.state == WorkInfo.State.FAILED }
    val progress = activeWork?.progress?.getInt("progress", -1) ?: -1
    val status = activeWork?.progress?.getString("status") ?: "downloading"

    // Auto-load model when download+extraction completes successfully
    LaunchedEffect(succeededWork?.id) {
        if (succeededWork != null && !isModelReady && !isModelInitializing) {
            Log.d("ModelsScreen", "Worker SUCCEEDED — checking for model file...")

            // Validate format of newly downloaded model
            val formatError = llmManager.validateAndDeleteIfWrong()
            if (formatError != null) {
                Log.e("ModelsScreen", "Downloaded model has wrong format: $formatError")
                isModelDownloaded = false
                return@LaunchedEffect
            }

            isModelDownloaded = llmManager.isModelDownloaded()
            if (isModelDownloaded) {
                Log.d("ModelsScreen", "Model found! Auto-loading...")
                // Small delay to let the UI update
                delay(500)
                onInitModel()
            }
        }
    }

    // Show error from failed work
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Merge external error messages (from auto-load)
    LaunchedEffect(externalErrorMessage) {
        if (externalErrorMessage != null) {
            errorMessage = externalErrorMessage
            isModelDownloaded = llmManager.isModelDownloaded()
        }
    }

    LaunchedEffect(failedWork?.id) {
        if (failedWork != null) {
            errorMessage = failedWork.outputData.getString("error")
            isModelDownloaded = llmManager.isModelDownloaded()
        }
    }

    // Re-check download state when screen appears
    LaunchedEffect(Unit) {
        isModelDownloaded = llmManager.isModelDownloaded()
    }

    Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("AI Core Setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("To use the AI, you need a MediaPipe-compatible SmolVLM 256M model (.task format).", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(24.dp))
                
                if (activeWork != null) {
                    // Show download/extraction progress
                    val statusText = if (status == "extracting") "Extracting Model..." else "Downloading Model..."
                    Text(statusText, style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { if (progress >= 0) progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    val progressText = when {
                        status == "extracting" && progress >= 0 -> "Extracting: $progress%"
                        status == "extracting" -> "Extracting... (this may take a while)"
                        progress >= 0 -> "Downloaded: $progress%"
                        else -> "Starting download..."
                    }
                    Text(progressText, style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = { workManager.cancelUniqueWork("model_download") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                } else if (isModelInitializing) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Loading AI into memory...", modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelSmall)
                } else if (isModelReady) {
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = DarkGreen)) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI IS ACTIVE")
                    }
                } else if (isModelDownloaded) {
                    Button(
                        onClick = { onInitModel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ACTIVATE AI")
                    }
                } else {
                    // No model downloaded — show download UI
                    var modelUrl by remember {
                        mutableStateOf("https://huggingface.co/YOUR_USERNAME/smolvlm-256m/resolve/main/smolvlm-256m.task")
                    }

                    OutlinedTextField(
                        value = modelUrl,
                        onValueChange = { modelUrl = it },
                        label = { Text("Model URL (.task file)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            errorMessage = null
                            onClearError()
                            val data = workDataOf("url" to modelUrl.trim())
                            val constraints = Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                            val request = OneTimeWorkRequestBuilder<ModelWorker>()
                                .setInputData(data)
                                .setConstraints(constraints)
                                .addTag("model_download")
                                .build()
                            workManager.enqueueUniqueWork("model_download", ExistingWorkPolicy.REPLACE, request)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = modelUrl.isNotBlank()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("DOWNLOAD MODEL")
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "⚠️ You must convert the SmolVLM 256M model to a .task file using LiteRT Torch, and host it! " +
                        "Enter the direct download URL above. " +
                        "Base PyTorch/Safetensors models will NOT work.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFCC6600)
                    )
                }

                // Show error message if any
                val displayError = errorMessage
                if (displayError != null && activeWork == null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0x33FF0000)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "Error: $displayError",
                            modifier = Modifier.padding(12.dp),
                            color = Color.Red,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Always show delete button if there's an error or model exists
                if ((isModelDownloaded || displayError != null) && !isModelInitializing && activeWork == null && !isModelReady) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { 
                        deleteModel(context)
                        isModelDownloaded = false
                        errorMessage = null
                        onClearError()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete Model & Reset", color = Color.Red)
                    }
                }
            }
        }
    }
}

fun deleteModel(context: Context) {
    val dir = context.filesDir
    // Delete all possible model files and temp files
    listOf("SmolVLM_256M.task", "SmolVLM_256M.bin", "SmolVLM_256M.tflite", "Gemma_4_E2B.task", "temp_archive.tar.gz", "model_extract.tmp").forEach {
        File(dir, it).delete()
    }
}

@Composable
fun Header(screen: Screen) {
    Column(modifier = Modifier.statusBarsPadding().padding(top = 16.dp, start = 24.dp, end = 24.dp, bottom = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Pocket GPT", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = screen.title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BottomNavigationBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    Surface(modifier = Modifier.navigationBarsPadding().fillMaxWidth().height(60.dp), color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            Screen.entries.forEach { screen ->
                val isSelected = currentScreen == screen
                IconButton(onClick = { onScreenSelected(screen) }, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)) {
                    Icon(screen.icon, contentDescription = screen.title, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    history: MutableList<ChatMessage>, 
    isGenerating: Boolean,
    llmManager: LlmManager,
    libraryHistory: MutableList<LibraryItem>
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    
    val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> selectedImageUri = uri }
    )

    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), contentPadding = PaddingValues(bottom = 140.dp)) {
            if (history.isEmpty()) {
                item { ChatMessageCard(ChatMessage("Hello! Activate the model to start chatting.", false)); Spacer(modifier = Modifier.height(16.dp)); InsightCard() }
            } else {
                items(history) { msg -> ChatMessageCard(msg); Spacer(modifier = Modifier.height(12.dp)) }
                if (isGenerating) item { Text("AI is thinking...", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp)) }
            }
        }
        
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
            if (selectedImageUri != null) {
                Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).size(80.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray)) {
                    Text("Image Attached", modifier = Modifier.align(Alignment.Center), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    IconButton(
                        onClick = { selectedImageUri = null },
                        modifier = Modifier.align(Alignment.TopEnd).size(24.dp).background(Color.Black.copy(alpha=0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            
            ChatInput(
                modifier = Modifier.padding(horizontal = 24.dp),
                isGenerating = isGenerating,
                onAttachClick = {
                    photoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onSendMessage = { text ->
                    if (!llmManager.isLoaded()) {
                        history.add(ChatMessage("AI: Not activated. Go to Models tab.", false))
                        return@ChatInput
                    }
                    history.add(ChatMessage(text + if(selectedImageUri != null) " [Image attached]" else "", true))
                    
                    val uriToProcess = selectedImageUri
                    selectedImageUri = null // clear input
                    
                    scope.launch {
                        // In a real app, convert URI to Bitmap. 
                        // For now we just pass null to simulate.
                        var bitmap: android.graphics.Bitmap? = null
                        if (uriToProcess != null) {
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uriToProcess)
                                    bitmap = android.graphics.ImageDecoder.decodeBitmap(source)
                                } else {
                                    @Suppress("DEPRECATION")
                                    bitmap = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uriToProcess)
                                }
                            } catch (e: Exception) {
                                Log.e("ChatScreen", "Failed to load image", e)
                            }
                        }
                        
                        val response = llmManager.generateResponse(text, bitmap)
                        history.add(ChatMessage(response, false))
                        libraryHistory.add(0, LibraryItem(query = text, output = response, timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())))
                    }
                }
            )
        }
    }
}

@Composable
fun ChatMessageCard(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (message.isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surface
    val textColor = if (message.isUser) Color.White else MaterialTheme.colorScheme.onSurface
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = alignment) {
        Box(modifier = Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(20.dp)).background(bgColor).padding(16.dp)) { Text(message.text, color = textColor) }
    }
}

@Composable
fun InsightCard() {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(GradientStart, GradientEnd))).padding(24.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = DarkPurple); Spacer(modifier = Modifier.width(8.dp)); Text("PRIVACY FIRST", style = MaterialTheme.typography.labelSmall, color = DarkPurple) }
            Spacer(modifier = Modifier.height(12.dp)); Text("Model runs 100% on-device.", color = Color.Black)
        }
    }
}

@Composable
fun ChatInput(modifier: Modifier = Modifier, isInitializing: Boolean = false, isGenerating: Boolean = false, onAttachClick: () -> Unit = {}, onSendMessage: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Box(modifier = modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAttachClick() }) { Icon(Icons.Default.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(0.6f)) }
            Box(modifier = Modifier.weight(1f)) {
                if (text.isEmpty()) Text(text = if (isGenerating) "AI is thinking..." else if (isInitializing) "Activating..." else "Type a message...", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                BasicTextField(value = text, onValueChange = { text = it }, enabled = !isGenerating, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
            }
            IconButton(onClick = { if (text.isNotBlank()) { onSendMessage(text); text = "" } }, enabled = text.isNotBlank() && !isGenerating, modifier = Modifier.size(44.dp).clip(CircleShape).background(if (text.isNotBlank() && !isGenerating) MaterialTheme.colorScheme.primary else Color.Gray)) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White) }
        }
    }
}

@Composable
fun LibraryScreen(history: List<LibraryItem>) { LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) { items(history) { item -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(modifier = Modifier.padding(16.dp)) { Text(item.query, fontWeight = FontWeight.Bold); Text(item.output) } } } } }

@Composable
fun SettingsScreen(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Row { ThemeMode.entries.forEach { mode -> Button(onClick = { onThemeChange(mode) }, modifier = Modifier.weight(1f)) { Text(mode.name) } } }
    }
}
