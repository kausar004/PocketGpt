package com.example.pocketgpt

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
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
        // Premium Slate Dark Mode with Neon Cyan accents
        ThemeMode.Dark -> darkColorScheme(primary = Color(0xFF00E5FF), secondary = Color(0xFFB000FF), background = Color(0xFF0F1115), surface = Color(0xFF161B22), onBackground = Color(0xFFE2E8F0), onSurface = Color(0xFFCBD5E1))
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
                        Screen.Chat -> ChatScreen(chatHistory, isGenerating, llmManager, libraryHistory, isModelReady, isModelInitializing, onInitModel, modelErrorMessage)
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

// Removed ModelsScreen and deleteModel

fun deleteModel(context: Context) {
    val dir = context.filesDir
    listOf("SmolVLM_256M.task", "temp_archive.tar.gz", "model_extract.tmp").forEach {
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
    Surface(modifier = Modifier.navigationBarsPadding().fillMaxWidth().height(60.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shadowElevation = 12.dp) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            val view = LocalView.current
            Screen.entries.forEach { screen ->
                val isSelected = currentScreen == screen
                val iconSize by animateDpAsState(targetValue = if (isSelected) 26.dp else 22.dp, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                IconButton(onClick = { 
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onScreenSelected(screen) 
                }, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)) {
                    Icon(screen.icon, contentDescription = screen.title, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), modifier = Modifier.size(iconSize))
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
    libraryHistory: MutableList<LibraryItem>,
    isModelReady: Boolean,
    isModelInitializing: Boolean,
    onInitModel: () -> Unit,
    modelErrorMessage: String?
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    
    // Auto-download logic
    val workManager = WorkManager.getInstance(context)
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData("model_download").observeAsState(emptyList())
    val activeWork = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val succeededWork = workInfos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
    val failedWork = workInfos.firstOrNull { it.state == WorkInfo.State.FAILED }
    val progress = activeWork?.progress?.getInt("progress", -1) ?: -1
    val status = activeWork?.progress?.getString("status") ?: "downloading"
    var isModelDownloaded by remember { mutableStateOf(llmManager.isModelDownloaded()) }

    LaunchedEffect(succeededWork?.id) {
        if (succeededWork != null && !isModelReady && !isModelInitializing) {
            val formatError = llmManager.validateAndDeleteIfWrong()
            if (formatError == null) {
                isModelDownloaded = llmManager.isModelDownloaded()
                if (isModelDownloaded) {
                    delay(500)
                    onInitModel()
                }
            } else {
                isModelDownloaded = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!isModelDownloaded && activeWork == null && !isModelInitializing && failedWork == null) {
            // Start download automatically
            val data = workDataOf("url" to LlmManager.DEFAULT_DOWNLOAD_URL)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<ModelWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .addTag("model_download")
                .build()
            workManager.enqueueUniqueWork("model_download", ExistingWorkPolicy.KEEP, request)
        }
    }
    
    val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> selectedImageUri = uri }
    )

    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), contentPadding = PaddingValues(bottom = 140.dp)) {
            item {
                if (activeWork != null) {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Downloading AI Core...", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onBackground)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            val animatedProgress by animateFloatAsState(targetValue = if (progress >= 0) progress / 100f else 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy), label = "progress")
                            LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha=0.1f))
                            Spacer(modifier = Modifier.height(8.dp))
                            val progressText = if (progress >= 0) "$progress%" else "Starting..."
                            Text("Status: $status | $progressText", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f))
                        }
                    }
                } else if (failedWork != null || modelErrorMessage != null) {
                    val error = modelErrorMessage ?: failedWork?.outputData?.getString("error") ?: "Unknown Error"
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).animateContentSize(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF3B0000))) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFFF5555), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Model Setup Failed", color = Color(0xFFFF5555), fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(error, color = Color(0xFFFFB3B3), style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = { deleteModel(context); isModelDownloaded = false; workManager.cancelAllWork() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5555))) { Text("Reset & Retry") }
                        }
                    }
                } else if (isModelInitializing) {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).animateContentSize(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Loading AI into memory...", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            if (history.isEmpty()) {
                item { ChatMessageCard(ChatMessage("Hello! Ask me anything.", false)); Spacer(modifier = Modifier.height(16.dp)); InsightCard() }
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
                        history.add(ChatMessage("AI: Not ready yet. Please wait for the download and activation to finish.", false))
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
    val bubbleShape = if (message.isUser) RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp) else RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)
    
    // Premium Gradient for user messages
    val userGradient = Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF007AFF)))
    val aiBackground = MaterialTheme.colorScheme.surface
    val textColor = if (message.isUser) Color.White else MaterialTheme.colorScheme.onSurface
    
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)), contentAlignment = alignment) {
        Box(modifier = Modifier.fillMaxWidth(0.85f).clip(bubbleShape)
            .then(if (message.isUser) Modifier.background(userGradient) else Modifier.background(aiBackground))
            .padding(horizontal = 20.dp, vertical = 14.dp)) { 
            Text(message.text, color = textColor, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp))) 
        }
    }
}

@Composable
fun InsightCard() {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))).padding(24.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF00E5FF)); Spacer(modifier = Modifier.width(10.dp)); Text("PRIVACY FIRST", style = MaterialTheme.typography.labelMedium, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.height(12.dp)); Text("Model runs 100% locally on your device.", color = Color(0xFFE2E8F0), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ChatInput(modifier: Modifier = Modifier, isInitializing: Boolean = false, isGenerating: Boolean = false, onAttachClick: () -> Unit = {}, onSendMessage: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val view = LocalView.current
    
    Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize(), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAttachClick() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(0.5f)) }
            Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (text.isEmpty()) Text(text = if (isGenerating) "AI is thinking..." else if (isInitializing) "Activating..." else "Message Pocket GPT...", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f), style = MaterialTheme.typography.bodyLarge)
                BasicTextField(value = text, onValueChange = { text = it }, enabled = !isGenerating, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
            IconButton(onClick = { 
                if (text.isNotBlank() && !isGenerating) { 
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    onSendMessage(text)
                    text = "" 
                } 
            }, enabled = text.isNotBlank() && !isGenerating, modifier = Modifier.size(48.dp).clip(CircleShape).background(if (text.isNotBlank() && !isGenerating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = if (text.isNotBlank() && !isGenerating) Color.Black else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) }
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
