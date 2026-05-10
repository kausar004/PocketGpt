package com.example.pocketgpt

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(val title: String, val icon: ImageVector) {
    Chat("Chat", Icons.AutoMirrored.Filled.Chat),
    Models("Models", Icons.Default.Storage),
    Library("Library", Icons.Default.CollectionsBookmark),
    Settings("Settings", Icons.Default.Settings)
}

data class ChatMessage(val text: String, val isUser: Boolean)
data class LibraryItem(val query: String, val output: String, val timestamp: String)
