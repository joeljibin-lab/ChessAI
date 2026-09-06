package com.example.chessai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {

        /*
         * Sent by the floating NEW button.
         */
        const val EXTRA_AUTO_START_NEW_SESSION =
            "AUTO_START_NEW_SESSION"
    }

    private var isStarted by mutableStateOf(false)

    private var autoStartRequestConsumed =
        false
    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            pendingResultCode = result.resultCode
            pendingResultData = result.data
            isStarted = true

            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, pendingResultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, pendingResultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startScreenCapture()
        }
    }

    // Launcher for handling the SYSTEM_ALERT_WINDOW (overlay) settings result
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            checkAndRequestPermissions()
        }
    }

    private fun consumeAutoStartRequest(
        sourceIntent: Intent?
    ) {

        val requested =
            sourceIntent?.getBooleanExtra(
                EXTRA_AUTO_START_NEW_SESSION,
                false
            ) == true


        if (
            !requested ||
            autoStartRequestConsumed
        ) {
            return
        }


        autoStartRequestConsumed =
            true


        /*
         * Force a genuinely fresh recording session.
         * Never reuse the previous MediaProjection result Intent.
         */
        isStarted =
            false

        pendingResultData =
            null

        pendingResultCode =
            0


        sourceIntent?.removeExtra(
            EXTRA_AUTO_START_NEW_SESSION
        )


        /*
         * The floating NEW button already told ScreenRecordService to stop.
         * Wait briefly for VirtualDisplay/ImageReader/MediaProjection cleanup,
         * then launch the same normal capture flow as the Start Recording button.
         */
        android.os.Handler(
            android.os.Looper.getMainLooper()
        ).postDelayed(
            {
                checkOverlayPermissionAndStart()
            },
            250L
        )
    }


    override fun onNewIntent(
        intent: Intent
    ) {

        super.onNewIntent(
            intent
        )

        setIntent(
            intent
        )

        autoStartRequestConsumed =
            false

        consumeAutoStartRequest(
            intent
        )
    }


    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selectedTab by remember { mutableStateOf("Capture") }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Chess Assistant",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleLarge
                        )
                        HorizontalDivider()
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = "Capture") },
                            label = { Text("Capture") },
                            selected = selectedTab == "Capture",
                            onClick = {
                                selectedTab = "Capture"
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.List, contentDescription = "Chess Boards") },
                            label = { Text("Chess Boards") },
                            selected = selectedTab == "Boards",
                            onClick = {
                                selectedTab = "Boards"
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (selectedTab == "Capture") "Capture Screen" else "Chess Boards") },
                            navigationIcon = {
                                IconButton(onClick = {
                                    scope.launch { drawerState.open() }
                                }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Open Menu")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTab) {
                            "Capture" -> CaptureScreen(
                                isStarted = isStarted,
                                onToggleCapture = {
                                    if (isStarted) {
                                        // Stop the service properly
                                        val stopIntent = Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                                            action = ScreenRecordService.ACTION_STOP
                                        }
                                        startService(stopIntent)
                                        isStarted = false

                                        // Clear expired token data so a fresh one must be requested next time
                                        pendingResultData = null
                                        pendingResultCode = 0
                                    } else {
                                        if (pendingResultData != null && pendingResultCode != 0) {
                                            // Start fresh screen recording with saved projection data
                                            val serviceIntent = Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                                                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, pendingResultCode)
                                                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, pendingResultData)
                                            }

                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                startForegroundService(serviceIntent)
                                            } else {
                                                startService(serviceIntent)
                                            }
                                            isStarted = true
                                        } else {
                                            checkOverlayPermissionAndStart()
                                        }
                                    }
                                }
                            )
                            "Boards" -> ChessBoardsScreen()
                        }
                    }
                }
            }
        }

        consumeAutoStartRequest(
            intent
        )
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startScreenCapture()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startScreenCapture()
        }
    }

    private fun startScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}

@Composable
fun CaptureScreen(isStarted: Boolean, onToggleCapture: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Button(onClick = onToggleCapture) {
            Text(text = if (isStarted) "Stop Recording" else "Start Recording")
        }
    }
}

@Composable
fun ChessBoardsScreen() {
    // Observe the shared repository list (matching MutableList<ChessBoardItem>)
    var boards by remember { mutableStateOf(ChessBoardRepository.savedBoards) }

    // Keep local sync
    boards = ChessBoardRepository.savedBoards

    var selectedBoard by remember { mutableStateOf<ChessBoardItem?>(null) }
    var boardToRename by remember { mutableStateOf<ChessBoardItem?>(null) }
    var newNameInput by remember { mutableStateOf("") }

    if (selectedBoard == null) {
        if (boards.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No chess boards detected yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(boards, key = { it.id }) { board ->
                    ChessBoardCard(
                        board = board,
                        onClick = { selectedBoard = board },
                        onRenameClick = {
                            boardToRename = board
                            newNameInput = board.name
                        }
                    )
                }
            }
        }
    } else {
        ChessBoardDetailScreen(
            board = selectedBoard!!,
            onBack = { selectedBoard = null }
        )
    }

    if (boardToRename != null) {
        AlertDialog(
            onDismissRequest = { boardToRename = null },
            title = { Text("Rename Chess Board") },
            text = {
                OutlinedTextField(
                    value = newNameInput,
                    onValueChange = { newNameInput = it },
                    singleLine = true,
                    label = { Text("Board Name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newNameInput.isNotBlank()) {
                            // Update repository items
                            ChessBoardRepository.savedBoards = ChessBoardRepository.savedBoards.map {
                                if (it.id == boardToRename?.id) it.copy(name = newNameInput) else it
                            }.toMutableList()
                            boards = ChessBoardRepository.savedBoards
                            if (selectedBoard?.id == boardToRename?.id) {
                                selectedBoard = selectedBoard?.copy(name = newNameInput)
                            }
                        }
                        boardToRename = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { boardToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ChessBoardCard(board: ChessBoardItem, onClick: () -> Unit, onRenameClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Uses the actual captured board bitmap as the thumbnail if available
            if (board.boardBitmap != null) {
                Image(
                    bitmap = board.boardBitmap.asImageBitmap(),
                    contentDescription = board.name,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
            } else {
                Image(
                    painter = painterResource(id = board.imageResId),
                    contentDescription = board.name,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = board.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = board.timestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRenameClick) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Rename Board"
                )
            }
        }
    }
}

@Composable
fun ChessBoardDetailScreen(
    board: ChessBoardItem,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(onClick = onBack) {
            Text("Back to List")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Full uncropped board image on top of detail screen
        if (board.boardBitmap != null) {
            Image(
                bitmap = board.boardBitmap.asImageBitmap(),
                contentDescription = board.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(id = board.imageResId),
                contentDescription = board.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = board.name, style = MaterialTheme.typography.headlineSmall)
        Text(text = board.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "64 Individual Blocks (Read-Only)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // 64 Square Blocks Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(64) { index ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.extraSmall)
                ) {
                    if (index < board.squareBitmaps.size) {
                        Image(
                            bitmap = board.squareBitmaps[index].asImageBitmap(),
                            contentDescription = "Square $index",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(id = board.imageResId),
                            contentDescription = "Square $index",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}
