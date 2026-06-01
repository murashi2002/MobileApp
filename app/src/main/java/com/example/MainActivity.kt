package com.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ChatMessage
import com.example.data.local.ChatSession
import com.example.data.local.SocialPost
import com.example.data.local.SocialRelation
import com.example.ui.ChatViewModel
import com.example.ui.models.ChatPersonality
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class WorkspaceTab {
    DASHBOARD,
    CHAT_SUITE,
    PEOPLE_LOGS
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val viewModel: ChatViewModel by viewModels()
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    // State to pipe STT text directly to the active keyboard layout input field
    private val _spokenTextState = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val spokenTextState = _spokenTextState

    // Native Speech to text recognizer launcher
    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenWords = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (spokenWords != null) {
                _spokenTextState.tryEmit(spokenWords)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize TextToSpeech
        tts = TextToSpeech(this, this)

        setContent {
            MyApplicationTheme {
                ChatAndSocialAppWorkspace(
                    viewModel = viewModel,
                    onTriggerVoiceInput = { launchSpeechRecognizer() },
                    spokenTextFlow = spokenTextState,
                    onSpeakText = { speakText(it) }
                )
            }
        }
    }

    private fun launchSpeechRecognizer() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
            }
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speakText(text: String) {
        if (isTtsInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gemini_voice_id")
        } else {
            Toast.makeText(this, "Text to speech engine not initialized", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsInitialized = true
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

// Custom reusable hover modifier for desktop, Chromebooks, DeX compatibility
@Composable
fun Modifier.crossPlatformHoverEffect(): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.05f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "hoverScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isHovered) 0.9f else 1.0f,
        animationSpec = tween(150),
        label = "hoverAlpha"
    )
    return this.hoverable(interactionSource)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAndSocialAppWorkspace(
    viewModel: ChatViewModel,
    onTriggerVoiceInput: () -> Unit,
    spokenTextFlow: SharedFlow<String>,
    onSpeakText: (String) -> Unit
) {
    var activeTab by remember { mutableStateOf(WorkspaceTab.DASHBOARD) }
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val posts by viewModel.allPosts.collectAsStateWithLifecycle()
    val relations by viewModel.allRelations.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == WorkspaceTab.DASHBOARD,
                    onClick = { activeTab = WorkspaceTab.DASHBOARD },
                    label = { Text("Dashboard") },
                    icon = { Icon(imageVector = Icons.Default.Home, contentDescription = "Dashboard") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = activeTab == WorkspaceTab.CHAT_SUITE,
                    onClick = { activeTab = WorkspaceTab.CHAT_SUITE },
                    label = { Text("AI Workspace") },
                    icon = { Icon(imageVector = Icons.Default.Send, contentDescription = "AI Chats") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                NavigationBarItem(
                    selected = activeTab == WorkspaceTab.PEOPLE_LOGS,
                    onClick = { activeTab = WorkspaceTab.PEOPLE_LOGS },
                    label = { Text("Network") },
                    icon = { Icon(imageVector = Icons.Default.Person, contentDescription = "Network") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                WorkspaceTab.DASHBOARD -> {
                    DashboardScreen(
                        viewModel = viewModel,
                        sessionsCount = sessions.size,
                        posts = posts,
                        relations = relations,
                        onClickChatQuickLink = { activeTab = WorkspaceTab.CHAT_SUITE }
                    )
                }
                WorkspaceTab.CHAT_SUITE -> {
                    ChatAppWorkspace(
                        viewModel = viewModel,
                        onTriggerVoiceInput = onTriggerVoiceInput,
                        spokenTextFlow = spokenTextFlow,
                        onSpeakText = onSpeakText
                    )
                }
                WorkspaceTab.PEOPLE_LOGS -> {
                    PeopleNetworkScreen(
                        viewModel = viewModel,
                        relations = relations
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: ChatViewModel,
    sessionsCount: Int,
    posts: List<SocialPost>,
    relations: List<SocialRelation>,
    onClickChatQuickLink: () -> Unit
) {
    val context = LocalContext.current
    var postDraftInput by remember { mutableStateOf("") }
    val followersCount = remember(relations) { relations.count { it.status == "follower" } }
    val followingCount = remember(relations) { relations.count { it.status == "following" } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcoming sunset banner card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Hello, Workspace Peer! 🦊🍊",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Welcome back to your high-octane advanced workspace. Toggle tabs to post bulletins, accept follower requests, or talk to specialized AI assistant agents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Metrics Grid Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Chats total Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onClickChatQuickLink() }
                        .crossPlatformHoverEffect(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Active chat counts",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$sessionsCount",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "AI Chats",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }

                // Followers total Card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Follower counts",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$followersCount",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Followers",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }

                // Posts Feed Card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Bulletins Feed",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${posts.size}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Bulletins",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        // Write Post Section (Bulletin Creator)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Publish a Social Bulletin",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = postDraftInput,
                        onValueChange = { postDraftInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Share update notes with your workspace followers...", fontSize = 14.sp) },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            if (postDraftInput.trim().isNotEmpty()) {
                                viewModel.submitPost(postDraftInput)
                                postDraftInput = ""
                                Toast.makeText(context, "Bulletin bulletin published successfully!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .align(Alignment.End)
                            .crossPlatformHoverEffect(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = "Publish", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Publish update", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Live Feed Bulletins Header
        item {
            Text(
                text = "Community Bulletins Feed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (posts.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No bulletins found. Create your first bulletin above!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(posts) { post ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(post.authorEmoji, fontSize = 18.sp)
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = post.authorName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Workspace Peer",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }

                            if (post.authorName == "You") {
                                IconButton(
                                    onClick = { viewModel.deletePost(post) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Bulletin",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = post.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clickable { viewModel.toggleLikePost(post) }
                                .padding(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Like Bulletin",
                                tint = if (post.isLikedByMe) Color(0xFFE65100) else Color.Gray.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${post.likesCount} Liked",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (post.isLikedByMe) Color(0xFFE65100) else Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PeopleNetworkScreen(
    viewModel: ChatViewModel,
    relations: List<SocialRelation>
) {
    val context = LocalContext.current
    val pendingRequests = remember(relations) { relations.filter { it.status == "requested" } }
    val followingAndFollowers = remember(relations) { relations.filter { it.status == "following" || it.status == "follower" } }

    val mockSuggestions = remember {
        listOf(
            Pair("AI Architect 🤖", "✨"),
            Pair("System Designer 🐰", "🐰"),
            Pair("UI Builder Owl 🦉", "🦉"),
            Pair("Kotlin Mentor Bear 🐻", "🐻")
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Core segment: Pending follower requests (Accept Followers feature)
        item {
            Text(
                text = "Inbox Follower Requests (${pendingRequests.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (pendingRequests.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No pending requests. All inbox is caught up! 🤝🍊",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(pendingRequests) { relation ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(relation.userEmoji, fontSize = 20.sp)
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = relation.userName,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = relation.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // ACCEPT buttons
                            Button(
                                onClick = {
                                    viewModel.acceptFollower(relation)
                                    Toast.makeText(context, "Request accepted!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(36.dp)
                                    .crossPlatformHoverEffect()
                            ) {
                                Text("Accept", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            // DELETE buttons
                            OutlinedButton(
                                onClick = {
                                    viewModel.rejectFollower(relation)
                                    Toast.makeText(context, "Request dismissed.", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(36.dp)
                                    .crossPlatformHoverEffect()
                            ) {
                                Text("Decline", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Segment: Active following and follower relationship listings
        item {
            Text(
                text = "Your Local Connections (${followingAndFollowers.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (followingAndFollowers.isEmpty()) {
            item {
                Text(
                    text = "No active connections. Try following some peers below!",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        } else {
            items(followingAndFollowers) { relation ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(relation.userEmoji, fontSize = 16.sp)
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = relation.userName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Relationship Status: " + relation.status.uppercase(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = {
                                viewModel.unfollowUser(relation)
                                Toast.makeText(context, "Unlinked connection", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Unlink Connection",
                                tint = Color.Gray
                            )
                        }
                    }
                }
            }
        }

        // Segment: Suggested Peers (Post Follow action)
        item {
            Text(
                text = "Discover Suggested Workspace Peers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        items(mockSuggestions) { peer ->
            val isAlreadyConnected = remember(relations, peer.first) {
                relations.any { it.userName == peer.first }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(peer.second, fontSize = 18.sp)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.first,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Awaiting discovery in local workspace matrix",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }

                    if (isAlreadyConnected) {
                        Text(
                            text = "Following",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else {
                        Button(
                            onClick = {
                                viewModel.followUser(peer.first, peer.second)
                                Toast.makeText(context, "Followed ${peer.first}!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .crossPlatformHoverEffect()
                        ) {
                            Text("Follow", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatAppWorkspace(
    viewModel: ChatViewModel,
    onTriggerVoiceInput: () -> Unit,
    spokenTextFlow: kotlinx.coroutines.flow.SharedFlow<String>,
    onSpeakText: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val activeMessages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchedMessages by viewModel.searchedMessages.collectAsStateWithLifecycle()

    val currentSession = remember(sessions, selectedId) {
        sessions.find { it.id == selectedId }
    }

    // Modal state controllers
    var showNewChatSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var listState = rememberLazyListState()

    // Dialog state controllers for session edits
    var sessionToRename by remember { mutableStateOf<ChatSession?>(null) }
    var renameDialogText by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }

    // Floating scroll down button
    val showScrollDownButton by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 1 }
    }

    // Sync keyboard STT
    var messageInputText by remember { mutableStateOf("") }
    LaunchedEffect(spokenTextFlow) {
        spokenTextFlow.collect { voiceText ->
            messageInputText = if (messageInputText.trim().isEmpty()) voiceText else "$messageInputText $voiceText"
        }
    }

    // Auto-scroll on new messages
    LaunchedEffect(activeMessages.size) {
        if (activeMessages.isNotEmpty()) {
            listState.animateScrollToItem(activeMessages.size - 1)
        }
    }

    // Modal Navigation Drawer / Sidebar Adaptive Layout
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        if (isWideScreen) {
            // Tablet-DeX multi-pane layout (Saves drawer toggles)
            Row(modifier = Modifier.fillMaxSize()) {
                SidebarContent(
                    sessions = sessions,
                    selectedId = selectedId,
                    onSelect = { viewModel.selectSession(it) },
                    onDelete = { viewModel.deleteSession(it) },
                    onRenameRequest = {
                        sessionToRename = it
                        renameDialogText = it.title
                        showRenameDialog = true
                    },
                    onTriggerCreate = { showNewChatSheet = true },
                    onTriggerSettings = { showSettingsSheet = true },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                    searchedMessages = searchedMessages,
                    onSelectSearchedSession = { sessId ->
                        viewModel.selectSession(sessId)
                        viewModel.updateSearchQuery("")
                    },
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                        .border(
                            BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        )
                )

                ChatWorkspace(
                    session = currentSession,
                    messages = activeMessages,
                    isGenerating = isGenerating,
                    inputText = messageInputText,
                    onInputChange = { messageInputText = it },
                    onSend = { text ->
                        selectedId?.let { id ->
                            viewModel.sendMessage(id, text)
                            messageInputText = ""
                        }
                    },
                    onRetry = { selectedId?.let { id -> viewModel.retryLastMessage(id) } },
                    onDeleteMessage = { viewModel.deleteMessage(it) },
                    onSpeak = onSpeakText,
                    onClearHistory = { selectedId?.let { id -> viewModel.clearHistory(id) } },
                    toggleNavigation = null, // Hidden on wide screen since sidebar is always visible
                    onTriggerVoiceInput = onTriggerVoiceInput,
                    onEditInstructions = { infoPrompt ->
                        currentSession?.let { sess ->
                            viewModel.updateSessionInstructions(sess, infoPrompt, "Custom Instructions")
                        }
                    },
                    listState = listState,
                    showScrollDownButton = showScrollDownButton,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        } else {
            // Standard compact screen drawer layout
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(320.dp)
                    ) {
                        SidebarContent(
                            sessions = sessions,
                            selectedId = selectedId,
                            onSelect = {
                                viewModel.selectSession(it)
                                coroutineScope.launch { drawerState.close() }
                            },
                            onDelete = { viewModel.deleteSession(it) },
                            onRenameRequest = {
                                sessionToRename = it
                                renameDialogText = it.title
                                showRenameDialog = true
                            },
                            onTriggerCreate = { showNewChatSheet = true },
                            onTriggerSettings = { showSettingsSheet = true },
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                            searchedMessages = searchedMessages,
                            onSelectSearchedSession = { sessId ->
                                viewModel.selectSession(sessId)
                                viewModel.updateSearchQuery("")
                                coroutineScope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            ) {
                ChatWorkspace(
                    session = currentSession,
                    messages = activeMessages,
                    isGenerating = isGenerating,
                    inputText = messageInputText,
                    onInputChange = { messageInputText = it },
                    onSend = { text ->
                        selectedId?.let { id ->
                            viewModel.sendMessage(id, text)
                            messageInputText = ""
                        }
                    },
                    onRetry = { selectedId?.let { id -> viewModel.retryLastMessage(id) } },
                    onDeleteMessage = { viewModel.deleteMessage(it) },
                    onSpeak = onSpeakText,
                    onClearHistory = { selectedId?.let { id -> viewModel.clearHistory(id) } },
                    toggleNavigation = { coroutineScope.launch { drawerState.open() } },
                    onTriggerVoiceInput = onTriggerVoiceInput,
                    onEditInstructions = { infoPrompt ->
                        currentSession?.let { sess ->
                            viewModel.updateSessionInstructions(sess, infoPrompt, "Custom Instructions")
                        }
                    },
                    listState = listState,
                    showScrollDownButton = showScrollDownButton,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Dialog: Rename Session
    if (showRenameDialog && sessionToRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Conversation") },
            text = {
                OutlinedTextField(
                    value = renameDialogText,
                    onValueChange = { renameDialogText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToRename?.let { sess ->
                            viewModel.updateSessionTitle(sess, renameDialogText)
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Bottom Sheet: New Chat Creation with Personality Picker
    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            var selectedPersona by remember { mutableStateOf(ChatPersonality.personalities.first()) }
            var customSystemPromptInput by remember { mutableStateOf("") }
            var useInstructionEditing by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Start a New Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showNewChatSheet = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close sheet")
                    }
                }

                // Personality select scroll row
                Text("Choose Assistant Persona", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ChatPersonality.personalities) { persona ->
                        val isSelected = selectedPersona.name == persona.name
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedPersona = persona },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ),
                            border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(persona.iconEmoji, fontSize = 20.sp)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(persona.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Text(persona.description, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // Toggle customize base prompt instructions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Customize Base Instructions", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = useInstructionEditing,
                        onCheckedChange = { useInstructionEditing = it }
                    )
                }

                if (useInstructionEditing) {
                    OutlinedTextField(
                        value = customSystemPromptInput.ifEmpty { selectedPersona.defaultSystemPrompt },
                        onValueChange = { customSystemPromptInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        label = { Text("Base System Prompts") }
                    )
                }

                Button(
                    onClick = {
                        viewModel.createNewSession(
                            title = "${selectedPersona.name}",
                            personality = selectedPersona,
                            customSystemPrompt = if (useInstructionEditing) customSystemPromptInput else selectedPersona.defaultSystemPrompt
                        )
                        showNewChatSheet = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Text("Launch Workspace", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Bottom Sheet: App Information / Security Settings
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = "Info Icon", tint = MaterialTheme.colorScheme.primary)
                        Text("Workspace Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { showSettingsSheet = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close sheet")
                    }
                }

                Divider()

                Text(
                    "This advanced Android application is built securely with local room cache integration. Conversations, topics, messages, and settings are saved on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Security API Guidelines", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "This prototype fetches direct responses from the Gemini API using native Android coroutines and networking. Any keys entered inside AI Studio's Secrets panel are processed securely at build time via the Secrets Gradle Plugin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("App Version: 1.0.0", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("Engine model: gemini-3.5-flash (Beta)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }

                Button(
                    onClick = { showSettingsSheet = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarContent(
    sessions: List<ChatSession>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onRenameRequest: (ChatSession) -> Unit,
    onTriggerCreate: () -> Unit,
    onTriggerSettings: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchedMessages: List<ChatMessage>,
    onSelectSearchedSession: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // App header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Logo", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                }
                Text("Gemini Chat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onTriggerSettings) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = "App settings")
            }
        }

        // Global chat list/message search textfield
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search messages...", fontSize = 13.sp) },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search icon", modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange(""); keyboardController?.hide() }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            textStyle = TextStyle(fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        )

        // Floating create workspace trigger button
        Button(
            onClick = { onTriggerCreate(); keyboardController?.hide() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add icon", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("New Chat Workspace", fontWeight = FontWeight.Bold)
        }

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        // Toggle list contents between message search result and sessions history
        if (searchQuery.trim().length >= 2) {
            Text(
                "Search Results (${searchedMessages.size})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            if (searchedMessages.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("No messages found matching search query.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchedMessages) { msg ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSearchedSession(msg.sessionId) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (msg.role == "user") "You" else "AI Assistant",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = msg.text,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Text(
                "Conversations History",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                fontWeight = FontWeight.Bold
            )

            if (sessions.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sessions) { session ->
                        val isSelected = session.id == selectedId
                        val personaEmoji = remember(session.personalityName) {
                            ChatPersonality.personalities.find { it.name == session.personalityName }?.iconEmoji ?: "🤖"
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onSelect(session.id) },
                                    onLongClick = { onRenameRequest(session) }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(personaEmoji, fontSize = 16.sp)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = session.personalityName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = Color.Gray,
                                        maxLines = 1
                                    )
                                }
                                if (sessions.size > 1) {
                                    IconButton(
                                        onClick = { onDelete(session) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete conversation",
                                            tint = Color.Gray.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatWorkspace(
    session: ChatSession?,
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onSpeak: (String) -> Unit,
    onClearHistory: () -> Unit,
    toggleNavigation: (() -> Unit)?,
    onTriggerVoiceInput: () -> Unit,
    onEditInstructions: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    showScrollDownButton: Boolean,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var showEditInstructionsDialog by remember { mutableStateOf(false) }
    var systemPromptEditValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = session?.title ?: "Chatting Workspace",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = session?.personalityName ?: "AI Helper",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    if (toggleNavigation != null) {
                        IconButton(onClick = toggleNavigation) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Navigation drawer")
                        }
                    }
                },
                actions = {
                    if (session != null) {
                        IconButton(
                            onClick = {
                                systemPromptEditValue = session.systemPrompt
                                showEditInstructionsDialog = true
                            }
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = "Customize prompt", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onClearHistory) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Clear thread flow")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (session == null) {
                // Circular loading fallback
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (messages.isEmpty() && !isGenerating) {
                // Welcoming Empty State with Interactive Cards Cues
                EmptyStateLayout(
                    session = session,
                    onPromptSelect = { onSend(it) }
                )
            } else {
                // Interactive Scrollable Conversation Column
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages) { message ->
                        ChatMessageBubble(
                            message = message,
                            onSpeak = { onSpeak(message.text) },
                            onCopy = { clipboardManager.setText(AnnotatedString(message.text)) },
                            onDelete = { onDeleteMessage(message.id) },
                            onRetry = onRetry
                        )
                    }

                    // Bottom extra space so keyboard doesn't mask last bubble
                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }

            // Scroll down floating shortcut button
            if (showScrollDownButton) {
                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.size - 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 90.dp, end = 16.dp)
                        .size(44.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom", modifier = Modifier.size(20.dp))
                }
            }

            // Floating Custom Input Row Box at bottom (locks itself over content safely)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(8.dp)
            ) {
                InputRow(
                    inputText = inputText,
                    onInputChange = onInputChange,
                    isGenerating = isGenerating,
                    onSend = onSend,
                    onTriggerVoiceInput = onTriggerVoiceInput
                )
            }
        }
    }

    // Dialog: Edit system instructions dynamically
    if (showEditInstructionsDialog && session != null) {
        AlertDialog(
            onDismissRequest = { showEditInstructionsDialog = false },
            title = { Text("Core Developer Instructions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Updating this prompt will customize how Gemini responds in this workspace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    OutlinedTextField(
                        value = systemPromptEditValue,
                        onValueChange = { systemPromptEditValue = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        maxLines = 8,
                        label = { Text("Prompt guidelines") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditInstructions(systemPromptEditValue)
                        showEditInstructionsDialog = false
                    }
                ) {
                    Text("Update Prompt")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditInstructionsDialog = false }) {
                    Text("Discard")
                }
            }
        )
    }
}

@Composable
fun EmptyStateLayout(
    session: ChatSession,
    onPromptSelect: (String) -> Unit
) {
    val presetPrompts = remember(session.personalityName) {
        getPromptsForPersonality(session.personalityName)
    }
    val emoji = remember(session.personalityName) {
        ChatPersonality.personalities.find { it.name == session.personalityName }?.iconEmoji ?: "🤖"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 42.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Say hello to ${session.personalityName}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Your conversation starts fresh. Tap one of these preset ideas to see how I can help, or write your query below.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Multi-Grid/Flow cards cues
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            presetPrompts.forEach { prompt ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPromptSelect(prompt) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Prompt cue",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = prompt,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit
) {
    val isUser = message.role == "user"
    val timestampFormatted = remember(message.timestamp) {
        val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        formatter.format(Date(message.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // Model identity mini icon
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                    .align(Alignment.Top),
                contentAlignment = Alignment.Center
            ) {
                Text("✨", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Typing spinner loading indicator
            val isBubbleLoading = !isUser && message.text.isEmpty() && !message.isFailed

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isUser -> MaterialTheme.colorScheme.primary
                        message.isFailed -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    }
                ),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 2.dp,
                    bottomEnd = if (isUser) 2.dp else 16.dp
                ),
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (isBubbleLoading) {
                        BouncingDotsTypingIndicator()
                    } else if (message.isFailed) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = "Error icon", tint = MaterialTheme.colorScheme.error)
                            Text(text = message.text, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
                        }
                    } else {
                        MarkdownText(
                            text = message.text,
                            textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Meta status info panel
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = timestampFormatted,
                    fontSize = 10.sp,
                    color = Color.Gray
                )

                if (message.isFailed) {
                    Text(
                        text = "RETRY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onRetry() }
                            .padding(2.dp)
                    )
                } else {
                    if (!isUser) {
                        Icon(
                            imageVector = Icons.Default.Share, // mapped to speak action
                            contentDescription = "Read aloud",
                            tint = Color.Gray,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onSpeak() }
                        )
                    }
                    Text(
                        text = "COPY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier
                            .clickable { onCopy() }
                            .padding(2.dp)
                    )
                    Text(
                        text = "DELETE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clickable { onDelete() }
                            .padding(2.dp)
                    )
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // User identity initial logo
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .align(Alignment.Top),
                contentAlignment = Alignment.Center
            ) {
                Text("ME", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun BouncingDotsTypingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        val infiniteTransition = rememberInfiniteTransition()
        val alphas = listOf(
            infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "alpha1"),
            infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse, initialStartOffset = StartOffset(150)), label = "alpha2"),
            infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse, initialStartOffset = StartOffset(300)), label = "alpha3")
        )
        alphas.forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha.value), CircleShape)
            )
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val paragraphs = remember(text) { text.split("\n") }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var inCodeBlock = false
        var codeBlockContent = StringBuilder()
        var codeBlockLanguage = ""

        for (paragraph in paragraphs) {
            val trimmed = paragraph.trim()
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    CodeBlock(code = codeBlockContent.toString().trim(), language = codeBlockLanguage)
                    codeBlockContent = StringBuilder()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                    codeBlockLanguage = trimmed.removePrefix("```").trim()
                }
            } else if (inCodeBlock) {
                codeBlockContent.append(paragraph).append("\n")
            } else {
                if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
                    val bulletText = trimmed.substring(2)
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text(text = "• ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        Text(text = parseFormattedText(bulletText, textColor), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (trimmed.isNotEmpty()) {
                    Text(text = parseFormattedText(paragraph, textColor), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            CodeBlock(code = codeBlockContent.toString().trim(), language = codeBlockLanguage)
        }
    }
}

@Composable
fun parseFormattedText(text: String, baseColor: Color): AnnotatedString {
    val inlineCodeBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    return remember(text, baseColor, inlineCodeBg) {
        val builder = AnnotatedString.Builder()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor))
                        builder.append(text.substring(i + 2, end))
                        builder.pop()
                        i = end + 2
                    } else {
                        builder.append("**")
                        i += 2
                    }
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        builder.pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = inlineCodeBg,
                                fontSize = 13.sp
                            )
                        )
                        builder.append(text.substring(i + 1, end))
                        builder.pop()
                        i = end + 1
                    } else {
                        builder.append("`")
                        i += 1
                    }
                }
                else -> {
                    builder.append(text[i])
                    i++
                }
            }
        }
        builder.toAnnotatedString()
    }
}

@Composable
fun CodeBlock(code: String, language: String) {
    val clipboardManager = LocalClipboardManager.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF333333))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2D2D))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifEmpty { "code" }.uppercase(),
                    color = Color(0xFFCCCCCC),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "COPY CODE",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable { clipboardManager.setText(AnnotatedString(code)) }
                        .padding(4.dp)
                )
            }
            Text(
                text = code,
                color = Color(0xFFD4D4D4),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputRow(
    inputText: String,
    onInputChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onTriggerVoiceInput: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Native Speech Recognition voice typing toggle
        IconButton(
            onClick = { onTriggerVoiceInput() },
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Settings, // Settings acts as placeholder triggering launcher
                contentDescription = "Voice typing dictation",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Expanded text fields
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = { Text("Type any message...", fontSize = 14.sp) },
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 44.dp),
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (inputText.trim().isNotEmpty() && !isGenerating) {
                        onSend(inputText)
                        keyboardController?.hide()
                    }
                }
            ),
            trailingIcon = {
                if (inputText.isNotEmpty()) {
                    IconButton(onClick = { onInputChange("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear text", modifier = Modifier.size(16.dp))
                    }
                }
            }
        )

        // Floating dynamic send trigger button
        IconButton(
            onClick = {
                if (inputText.trim().isNotEmpty() && !isGenerating) {
                    onSend(inputText)
                    keyboardController?.hide()
                }
            },
            enabled = inputText.trim().isNotEmpty() && !isGenerating,
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (inputText.trim().isNotEmpty() && !isGenerating) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f),
                    CircleShape
                )
        ) {
            if (isGenerating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send Message",
                    tint = if (inputText.trim().isNotEmpty() && !isGenerating) MaterialTheme.colorScheme.onPrimary else Color.Gray
                )
            }
        }
    }
}

// Custom prompt configurations based on the currently active personality
fun getPromptsForPersonality(personalityName: String): List<String> {
    return when (personalityName) {
        "Coding Mentor" -> listOf(
            "Explain Coroutines in Kotlin clearly",
            "Write a Room entity data representation",
            "Optimize a recursive factorial block",
            "What is the difference between val and var?"
        )
        "Creative Writer" -> listOf(
            "Write a short, moody poem about rain",
            "Brainstorm names for a fantasy universe",
            "Draft a tense dialogue between rivals",
            "Create a dramatic thriller opening line"
        )
        "Language Tutor" -> listOf(
            "Translate 'Goodbye, my friend' to French",
            "When to use 'tu' vs 'usted' in Spanish",
            "List 5 useful German shopping phrases",
            "Correct: 'She don't like drinking hot tea'"
        )
        "Wellness Guide" -> listOf(
            "Guide me in a 4-7-8 breathing drill",
            "How can I lock focus while working?",
            "Write three comforting affirmations",
            "Tips for building a healthy night routine"
        )
        else -> listOf(
            "Explain quantum computing in simple terms",
            "Draft a warm email to a potential client",
            "Give me 5 simple weeknight recipes",
            "Recommend classic Sci-Fi books like Dune"
        )
    }
}
