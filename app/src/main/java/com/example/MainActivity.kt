package com.example

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.Clip
import com.example.data.DeletedClipsManager
import com.example.service.ClipAccessibilityService
import com.example.service.ClipboardOverlayService
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val repository = (context.applicationContext as SereneClipApp).repository
    val scope = rememberCoroutineScope()

    var isPermissionGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    var isServiceRunningState by remember {
        mutableStateOf(isServiceRunning(context, ClipboardOverlayService::class.java))
    }

    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, ClipAccessibilityService::class.java))
    }

    // Monitor overlay permission and auto-save clipboard when returning to activity
    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isPermissionGranted = Settings.canDrawOverlays(context)
                isServiceRunningState = isServiceRunning(context, ClipboardOverlayService::class.java)
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context, ClipAccessibilityService::class.java)
                
                 // Auto-save from clipboard on resume
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                if (clipboard.hasPrimaryClip()) {
                    val clipData = clipboard.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).coerceToText(context)?.toString()
                        if (!text.isNullOrBlank()) {
                            if (DeletedClipsManager.isDeleted(text)) {
                                // Ignore currently deleted clip
                            } else {
                                DeletedClipsManager.clearAll()
                                scope.launch {
                                    val latest = repository.getLatestClipByTimestamp()
                                    if (latest == null || latest.text != text) {
                                        repository.insert(Clip(text = text, timestamp = System.currentTimeMillis()))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val lifecycle = (context as ComponentActivity).lifecycle
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isPermissionGranted = Settings.canDrawOverlays(context)
    }

    val clipsState = repository.allClips.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var manualText by remember { mutableStateOf("") }
    var isAddingManual by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeletedClip by remember { mutableStateOf<Clip?>(null) }

    LaunchedEffect(Unit) {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        repository.deleteOldUnpinnedClips(thirtyDaysAgo)
    }

    // Editing State
    var editingClip by remember { mutableStateOf<Clip?>(null) }
    var editText by remember { mutableStateOf("") }

    val filteredClips = clipsState.value.filter {
        it.text.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.background,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Serene Clip",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            SmallFloatingActionButton(
                onClick = {
                    isAddingManual = true
                    manualText = ""
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("app_add_clip_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add New Clip",
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Permission Alert or Service Status Controller
            if (!isPermissionGranted) {
                PermissionRequestCard {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    launcher.launch(intent)
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                ServiceControlCard(
                    isRunning = isServiceRunningState,
                    onToggle = { start ->
                        val serviceIntent = Intent(context, ClipboardOverlayService::class.java)
                        if (start) {
                            ContextCompat.startForegroundService(context, serviceIntent)
                        } else {
                            context.stopService(serviceIntent)
                        }
                        // Soft delay to allow service status change
                        scope.launch {
                            kotlinx.coroutines.delay(200)
                            isServiceRunningState = isServiceRunning(context, ClipboardOverlayService::class.java)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                AccessibilityControlCard(
                    isEnabled = isAccessibilityEnabled,
                    onOpenSettings = {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "إعدادات سهولة الوصول غير متاحة", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                KeyboardControlCard()
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search stored clips...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search icon",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("app_search_input"),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Clips List
            if (filteredClips.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPasteOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) "Your clipping deck is empty" else "No clips match search query",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredClips, key = { it.id }) { clip ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { dismissValue ->
                                if (dismissValue == SwipeToDismissBoxValue.EndToStart || dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                                    scope.launch {
                                        recentlyDeletedClip = clip
                                        DeletedClipsManager.markAsDeleted(clip.text)
                                        repository.deleteById(clip.id)
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Clip deleted",
                                            actionLabel = "Undo",
                                            duration = SnackbarDuration.Short
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            recentlyDeletedClip?.let { restored ->
                                                DeletedClipsManager.unmarkDeleted(restored.text)
                                                repository.insert(restored)
                                            }
                                        }
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val color = MaterialTheme.colorScheme.errorContainer
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(color)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            },
                            content = {
                                ClipCardItem(
                                    clip = clip,
                                    searchQuery = searchQuery,
                                    onCopy = { text, isFull ->
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val data = android.content.ClipData.newPlainText("Copied Clip", text)
                                        clipboard.setPrimaryClip(data)
                                        DeletedClipsManager.unmarkDeleted(text)
                                        val msg = if (isFull) "Full Text Copied" else "Selected Phrase Copied"
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    },
                                    onPinToggle = {
                                        scope.launch {
                                            repository.update(clip.copy(isPinned = !clip.isPinned))
                                        }
                                    },
                                    onEdit = {
                                        editingClip = clip
                                        editText = clip.text
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // Add Manual Clip Dialog
    if (isAddingManual) {
        AlertDialog(
            onDismissRequest = { isAddingManual = false },
            title = { Text("Add New Clip", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    placeholder = { Text("Type or paste anything...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .testTag("app_manual_clip_input"),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualText.isNotBlank()) {
                            scope.launch {
                                repository.insert(Clip(text = manualText))
                                manualText = ""
                                isAddingManual = false
                                Toast.makeText(context, "Clip added", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.testTag("app_save_clip_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { isAddingManual = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clip Editing Dialog
    if (editingClip != null) {
        AlertDialog(
            onDismissRequest = { editingClip = null },
            title = { Text("Edit Clip", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .testTag("app_edit_clip_input"),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val original = editingClip
                        if (original != null && editText.isNotBlank()) {
                            scope.launch {
                                repository.update(original.copy(text = editText))
                                editingClip = null
                                Toast.makeText(context, "Clip updated", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("app_edit_clip_save_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingClip = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PermissionRequestCard(onRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Floating Overlay Permission Required",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "To allow copying and pasting text over any running app without interupting your flow, please grant overlay permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onRequest,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Enable Overlay", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ServiceControlCard(
    isRunning: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) "Floating Assistant is Active" else "Floating Assistant is Off",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isRunning) "Tapping bubble snaps to edges. Tap to open clip deck." else "Toggle overlay bubble to copy/paste anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = isRunning,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.testTag("service_status_switch")
            )
        }
    }
}

@Composable
fun ClipCardItem(
    clip: Clip,
    searchQuery: String,
    onCopy: (String, Boolean) -> Unit,
    onPinToggle: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val isPinned = clip.isPinned
    val scrollState = rememberScrollState()

    // Auto-scroll to search match
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            val index = clip.text.indexOf(searchQuery, ignoreCase = true)
            if (index != -1 && clip.text.length > 200) {
                val ratio = index.toFloat() / clip.text.length
                val targetScroll = (scrollState.maxValue * ratio).toInt()
                scrollState.animateScrollTo(targetScroll)
            }
        } else {
            scrollState.scrollTo(0)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (isPinned) 4.dp else 1.dp, RoundedCornerShape(16.dp))
            .testTag("app_clip_item_${clip.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPinned) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (isPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Left border accent line for pinned items (border-l-4 style)
            if (isPinned) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
            ) {
                // Header inside card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPinned) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.alpha(0.7f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "PINNED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Text(
                        text = formatTimestamp(clip.timestamp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Text Content with Smart Highlighting, Clicks, and Auto-scroll
                val annotatedString = remember(clip.text, searchQuery) {
                    buildSmartAnnotatedString(
                        text = clip.text,
                        query = searchQuery,
                        primaryColor = Color(0xFFD0BCFF),
                        secondaryColor = Color(0xFF381E72)
                    )
                }

                var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                ) {
                    Text(
                        text = annotatedString,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 22.sp,
                            letterSpacing = 0.15.sp
                        ),
                        onTextLayout = { layoutResult = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .pointerInput(clip.text, searchQuery) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        val layout = layoutResult
                                        if (layout != null && searchQuery.isNotEmpty()) {
                                            val characterOffset = layout.getOffsetForPosition(offset)
                                            val matchAnnotations = annotatedString.getStringAnnotations(
                                                tag = "MATCH",
                                                start = characterOffset,
                                                end = characterOffset
                                            )
                                            if (matchAnnotations.isNotEmpty()) {
                                                val sentence = getSentenceContaining(clip.text, searchQuery)
                                                onCopy(sentence, false)
                                                return@detectTapGestures
                                            }
                                        }
                                        onCopy(clip.text, true)
                                    },
                                    onDoubleTap = {
                                        onEdit()
                                    },
                                    onLongPress = { offset ->
                                        val layout = layoutResult
                                        if (layout != null) {
                                            val characterOffset = layout.getOffsetForPosition(offset)
                                            val urlAnnotations = annotatedString.getStringAnnotations(
                                                tag = "URL",
                                                start = characterOffset,
                                                end = characterOffset
                                            )
                                            val emailAnnotations = annotatedString.getStringAnnotations(
                                                tag = "EMAIL",
                                                start = characterOffset,
                                                end = characterOffset
                                            )
                                            val phoneAnnotations = annotatedString.getStringAnnotations(
                                                tag = "PHONE",
                                                start = characterOffset,
                                                end = characterOffset
                                            )

                                            when {
                                                urlAnnotations.isNotEmpty() -> {
                                                    handleSmartEntityIntent(context, "URL", urlAnnotations.first().item)
                                                }
                                                emailAnnotations.isNotEmpty() -> {
                                                    handleSmartEntityIntent(context, "EMAIL", emailAnnotations.first().item)
                                                }
                                                phoneAnnotations.isNotEmpty() -> {
                                                    handleSmartEntityIntent(context, "PHONE", phoneAnnotations.first().item)
                                                }
                                                else -> {
                                                    onPinToggle()
                                                }
                                            }
                                        } else {
                                            onPinToggle()
                                        }
                                    }
                                )
                            }
                    )
                }
            }
        }
    }
}

fun buildSmartAnnotatedString(
    text: String,
    query: String,
    primaryColor: Color,
    secondaryColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        append(text)

        val urlRegex = "(?:https?|ftp)://\\S+|www\\.\\S+".toRegex(RegexOption.IGNORE_CASE)
        val emailRegex = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}".toRegex()
        val phoneRegex = "\\+?\\d{1,4}[-.\\s]?\\(?\\d{1,3}?\\)?[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,9}".toRegex()

        urlRegex.findAll(text).forEach { match ->
            addStyle(
                style = SpanStyle(
                    color = primaryColor,
                    textDecoration = TextDecoration.Underline
                ),
                start = match.range.first,
                end = match.range.last + 1
            )
            addStringAnnotation(
                tag = "URL",
                annotation = match.value,
                start = match.range.first,
                end = match.range.last + 1
            )
        }

        emailRegex.findAll(text).forEach { match ->
            addStyle(
                style = SpanStyle(
                    color = primaryColor,
                    textDecoration = TextDecoration.Underline
                ),
                start = match.range.first,
                end = match.range.last + 1
            )
            addStringAnnotation(
                tag = "EMAIL",
                annotation = match.value,
                start = match.range.first,
                end = match.range.last + 1
            )
        }

        phoneRegex.findAll(text).forEach { match ->
            addStyle(
                style = SpanStyle(
                    color = primaryColor,
                    textDecoration = TextDecoration.Underline
                ),
                start = match.range.first,
                end = match.range.last + 1
            )
            addStringAnnotation(
                tag = "PHONE",
                annotation = match.value,
                start = match.range.first,
                end = match.range.last + 1
            )
        }

        if (query.isNotEmpty()) {
            var startIndex = text.indexOf(query, ignoreCase = true)
            while (startIndex != -1) {
                val endIndex = startIndex + query.length
                addStyle(
                    style = SpanStyle(
                        background = secondaryColor.copy(alpha = 0.5f),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    ),
                    start = startIndex,
                    end = endIndex
                )
                addStringAnnotation(
                    tag = "MATCH",
                    annotation = query,
                    start = startIndex,
                    end = endIndex
                )
                startIndex = text.indexOf(query, startIndex + 1, ignoreCase = true)
            }
        }
    }
}

fun getSentenceContaining(text: String, query: String): String {
    if (query.isEmpty()) return text
    val sentences = text.split(Regex("(?<=[.!?])\\s+"))
    for (sentence in sentences) {
        if (sentence.contains(query, ignoreCase = true)) {
            return sentence.trim()
        }
    }
    return query
}

fun handleSmartEntityIntent(context: Context, type: String, value: String) {
    try {
        val intent = when (type) {
            "URL" -> {
                val url = if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    "https://$value"
                } else {
                    value
                }
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            "EMAIL" -> {
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$value")
                }
            }
            "PHONE" -> {
                Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$value")
                }
            }
            else -> null
        }
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not handle: $value", Toast.LENGTH_SHORT).show()
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> "${diff / 86400_000}d ago"
    }
}

@Suppress("DEPRECATION")
fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) {
            return true
        }
    }
    return false
}

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expectedComponentName = ComponentName(context, serviceClass)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val stringSplitter = TextUtils.SimpleStringSplitter(':')
    stringSplitter.setString(enabledServicesSetting)
    while (stringSplitter.hasNext()) {
        val componentNameString = stringSplitter.next()
        val enabledComponent = ComponentName.unflattenFromString(componentNameString)
        if (enabledComponent != null && enabledComponent == expectedComponentName) {
            return true
        }
    }
    return false
}

@Composable
fun AccessibilityControlCard(
    isEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isEnabled) Icons.Default.CheckCircle else Icons.Default.AccessibilityNew,
                        contentDescription = null,
                        tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isEnabled) "النسخ التلقائي المباشر مُفعّل" else "تفعيل النسخ المباشر (Accessibility)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isEnabled) "خدمة الوصول تعمل الآن وسوف تحفظ النصوص تلقائياً بدون الحاجة لفتح الفقاعة." else "اضغط لتفعيل الخدمة من إعدادات الوصول بالهاتف لحفظ أي نص فور نسخِه.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(if (isEnabled) "الإعدادات" else "تفعيل", fontSize = 12.sp)
            }
        }
    }
}

fun isKeyboardEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    val enabledMethods = imm?.enabledInputMethodList ?: return false
    return enabledMethods.any { it.packageName == context.packageName }
}

@Composable
fun KeyboardControlCard() {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(isKeyboardEnabled(context)) }

    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isEnabled = isKeyboardEnabled(context)
            }
        }
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Keyboard,
                            contentDescription = null,
                            tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isEnabled) "لوحة المفاتيح الحافظة مُفعلة" else "تفعيل لوحة المفاتيح الحافظة (حل مضاعف)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "لوحة مفاتيح خفيفة تتيح لك الوصول الفوري والكامل للحافظة واللصق بضغطة واحدة من أي تطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "تعذر فتح إعدادات لوحة المفاتيح", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text(if (isEnabled) "إعدادات الكيبورد" else "1. تفعيل الكيبورد", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.showInputMethodPicker()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text("2. اختيار الكيبورد", fontSize = 12.sp)
                }
            }
        }
    }
}
