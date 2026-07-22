package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.SereneClipApp
import com.example.data.Clip
import com.example.data.DeletedClipsManager
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import androidx.annotation.Keep

@Keep
class ClipboardOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val myViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = myViewModelStore

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var clipboardManager: ClipboardManager

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (!text.isNullOrBlank()) {
                    DeletedClipsManager.unmarkDeleted(text)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        checkAndAutoAddClipboard()
    }

    private var isExpanded by mutableStateOf(false)
    private var bubbleXState by mutableStateOf(-1)
    private var bubbleYState by mutableStateOf(-1)
    private var lastSeenClipText: String? = null
    private val repository by lazy { SereneClipApp.instance.repository }

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0

    // Bubble size calculation
    private val bubbleSizePx by lazy {
        (48 * resources.displayMetrics.density).toInt()
    }

    private val collapsedParams by lazy {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleXState
            y = bubbleYState
        }
    }

    private val expandedParams by lazy {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        bubbleXState = screenWidth - bubbleSizePx
        bubbleYState = (screenHeight * 0.4f).toInt()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Register Clipboard PrimaryClipChangedListener
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        startNotification()
        setupOverlay()
    }

    private fun startNotification() {
        val channelId = "serene_clip_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Serene Clip Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Enables floating overlay and background clipboard helpers."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ClipboardOverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Serene Clip Running")
            .setContentText("Tap overlay bubble on the edge to access clip storage.")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupOverlay() {
        composeView = ComposeView(this).apply {
            setOnKeyListener { _, keyCode, event ->
                if (isExpanded && keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    collapseOverlay()
                    true
                } else {
                    false
                }
            }
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@ClipboardOverlayService)
            setViewTreeViewModelStoreOwner(this@ClipboardOverlayService)
            setViewTreeSavedStateRegistryOwner(this@ClipboardOverlayService)
            setContent {
                MyApplicationTheme {
                    OverlayContent()
                }
            }
        }

        windowManager.addView(composeView, collapsedParams)
    }

    private fun expandOverlay() {
        isExpanded = true
        windowManager.updateViewLayout(composeView, expandedParams)
        serviceScope.launch {
            delay(150)
            checkAndAutoAddClipboard()
        }
    }

    private fun collapseOverlay() {
        isExpanded = false
        windowManager.updateViewLayout(composeView, collapsedParams)
    }

    private fun checkAndAutoAddClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (!text.isNullOrBlank()) {
                    if (DeletedClipsManager.isDeleted(text)) {
                        return
                    }
                    if (text == lastSeenClipText) {
                        return
                    }
                    lastSeenClipText = text

                    serviceScope.launch {
                        val latest = repository.allClips.firstOrNull()?.firstOrNull()
                        if (latest == null || latest.text != text) {
                            repository.insert(Clip(text = text))
                            Toast.makeText(this@ClipboardOverlayService, "تم حفظ النص المنسوخ تلقائياً", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OverlayContent() {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!isExpanded) {
                FloatingBubble()
            } else {
                ExpandedPanel()
                val density = LocalDensity.current
                val bubbleX = with(density) { bubbleXState.toDp() }
                val bubbleY = with(density) { bubbleYState.toDp() }
                Box(
                    modifier = Modifier.offset(x = bubbleX, y = bubbleY)
                ) {
                    FloatingBubble(isInsideExpanded = true)
                }
            }
        }
    }

    @Composable
    private fun FloatingBubble(isInsideExpanded: Boolean = false) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        var isDragging by remember { mutableStateOf(false) }

        val isTouched = isPressed || isDragging
        val targetAlpha = if (isTouched) 0.8f else 0.12f
        val alpha by animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = if (isTouched) {
                tween(durationMillis = 150)
            } else {
                tween(durationMillis = 800)
            },
            label = "BubbleAlpha"
        )

        Box(
            modifier = Modifier
                .size(48.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            bubbleXState = (bubbleXState + dragAmount.x.toInt()).coerceIn(0, screenWidth - bubbleSizePx)
                            bubbleYState = (bubbleYState + dragAmount.y.toInt()).coerceIn(0, screenHeight - bubbleSizePx)
                            collapsedParams.x = bubbleXState
                            collapsedParams.y = bubbleYState
                            if (!isInsideExpanded) {
                                try {
                                    windowManager.updateViewLayout(composeView, collapsedParams)
                                } catch (e: Exception) {
                                    // Prevent race condition crashes
                                }
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            snapToEdge()
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (isInsideExpanded) {
                            collapseOverlay()
                        } else {
                            expandOverlay()
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .alpha(alpha)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = "Open Clips Overlay",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }

    private fun snapToEdge() {
        val currentX = bubbleXState
        val targetX = if (currentX + bubbleSizePx / 2 < screenWidth / 2) {
            0
        } else {
            screenWidth - bubbleSizePx
        }

        serviceScope.launch {
            val steps = 12
            val diff = targetX - currentX
            for (i in 1..steps) {
                bubbleXState = currentX + (diff * i / steps)
                collapsedParams.x = bubbleXState
                if (!isExpanded) {
                    try {
                        windowManager.updateViewLayout(composeView, collapsedParams)
                    } catch (e: Exception) {
                        break
                    }
                }
                delay(12)
            }
        }
    }

    @Composable
    private fun ExpandedPanel() {
        val clipsState = repository.allClips.collectAsState(initial = emptyList())
        var searchQuery by remember { mutableStateOf("") }
        var isAddingManual by remember { mutableStateOf(false) }
        var manualText by remember { mutableStateOf("") }

        val filteredClips = clipsState.value.filter {
            it.text.contains(searchQuery, ignoreCase = true)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    collapseOverlay()
                },
            contentAlignment = Alignment.Center
        ) {
            var editingClip by remember { mutableStateOf<Clip?>(null) }
            var editText by remember { mutableStateOf("") }
            val snackbarHostState = remember { SnackbarHostState() }
            var recentlyDeletedClip by remember { mutableStateOf<Clip?>(null) }

            LaunchedEffect(Unit) {
                val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                repository.deleteOldUnpinnedClips(thirtyDaysAgo)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.72f)
                    .shadow(12.dp, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Prevent click propagation to background scrim
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    ) {
                        // Header Area
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Serene Clip",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                            Row {
                                IconButton(
                                    onClick = { checkAndAutoAddClipboard() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Sync clipboard",
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { isAddingManual = !isAddingManual },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isAddingManual) Icons.Default.Close else Icons.Default.Add,
                                        contentDescription = "Add manual clip",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { collapseOverlay() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Collapse panel",
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Add Manual Clip Section
                        AnimatedVisibility(visible = isAddingManual) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                OutlinedTextField(
                                    value = manualText,
                                    onValueChange = { manualText = it },
                                    placeholder = { Text("Type or paste long text...") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 80.dp, max = 150.dp)
                                        .testTag("manual_clip_input"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        if (manualText.isNotBlank()) {
                                            serviceScope.launch {
                                                repository.insert(Clip(text = manualText))
                                                manualText = ""
                                                isAddingManual = false
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .testTag("save_manual_clip_button"),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text("Save Clip", fontSize = 13.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Search Bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search your clips...") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
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
                                .heightIn(min = 50.dp)
                                .testTag("overlay_search_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

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
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (searchQuery.isEmpty()) "No clips stored yet" else "No matching clips found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 8.dp)
                            ) {
                                items(filteredClips, key = { it.id }) { clip ->
                                    val dismissState = rememberSwipeToDismissBoxState(
                                        confirmValueChange = { dismissValue ->
                                            if (dismissValue == SwipeToDismissBoxValue.EndToStart || dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                                                serviceScope.launch {
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
                                                    copyToClipboardAndToast(text)
                                                    val msg = if (isFull) "Full Text Copied" else "Selected Phrase Copied"
                                                    Toast.makeText(this@ClipboardOverlayService, msg, Toast.LENGTH_SHORT).show()
                                                    collapseOverlay()
                                                },
                                                onPinToggle = {
                                                    serviceScope.launch {
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

                    // Snackbar Host in the overlay panel itself
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )
                }
            }

            // Inline Edit Dialog inside overlay
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
                                .testTag("overlay_edit_clip_input"),
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
                                    serviceScope.launch {
                                        repository.update(original.copy(text = editText))
                                        editingClip = null
                                        Toast.makeText(this@ClipboardOverlayService, "Clip updated", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
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
    }

    @Composable
    private fun ClipCardItem(
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
                .testTag("clip_item_${clip.id}"),
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
                        .padding(14.dp)
                ) {
                    // Header of Card
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
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "PINNED",
                                    fontSize = 9.sp,
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
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

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
                                lineHeight = 21.sp,
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

    private fun buildSmartAnnotatedString(
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

    private fun getSentenceContaining(text: String, query: String): String {
        if (query.isEmpty()) return text
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        for (sentence in sentences) {
            if (sentence.contains(query, ignoreCase = true)) {
                return sentence.trim()
            }
        }
        return query
    }

    private fun handleSmartEntityIntent(context: Context, type: String, value: String) {
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

    private fun copyToClipboardAndToast(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("Copied Clip", text)
        clipboard.setPrimaryClip(clip)
        DeletedClipsManager.unmarkDeleted(text)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        myViewModelStore.clear()
        serviceScope.cancel()

        // Unregister Clipboard PrimaryClipChangedListener
        if (::clipboardManager.isInitialized) {
            try {
                clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            windowManager.removeView(composeView)
        } catch (e: Exception) {
            // View might not be attached
        }
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 2468
        const val ACTION_STOP_SERVICE = "com.example.service.ACTION_STOP_SERVICE"
    }
}
