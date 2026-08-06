package com.example.ui

import com.example.R
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.pager.HorizontalPager
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.pager.rememberPagerState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.*
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayDashboard(
    viewModel: GatewayViewModel,
    initialChatUuid: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val requests by viewModel.allRequests.collectAsState()
    val pendingReqs by viewModel.pendingRequests.collectAsState()
    val messages by viewModel.allMessages.collectAsState()
    val callLogs by viewModel.allCallLogs.collectAsState()

    val currentCallState by viewModel.activeCallState.collectAsState()
    val connStatus by viewModel.connectionStatus.collectAsState()
    val serverUrlVal by viewModel.serverUrl.collectAsState()
    val apiKeyVal by viewModel.apiKey.collectAsState()
    val pushTokenVal by viewModel.pushToken.collectAsState()
    val serverReachableVal by viewModel.serverReachable.collectAsState()

    val selectedTab by viewModel.selectedTab.collectAsState()
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var showDozeInfoDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    val allInvites by viewModel.allInvites.collectAsState()

    val pagerState = rememberPagerState(initialPage = 1) { 3 }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialChatUuid) {
        if (initialChatUuid != null) {
            viewModel.setSelectedChatUuid(initialChatUuid)
        }
    }

    // Sync selectedTab with pagerState
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setSelectedTab(pagerState.currentPage)
    }

    // Tick the call timer if call is active
    LaunchedEffect(currentCallState) {
        if (currentCallState is CallState.Active) {
            while (true) {
                delay(1000)
                viewModel.onTimeTick()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.systemBars,
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_app_logo_transparent),
                                contentDescription = "App Icon",
                                modifier = Modifier.height(28.dp)
                            )
                            Text(
                                text = "Whisker",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = Color.White
                    ),
                    actions = {
                        // Quick configuration icon button
                        IconButton(
                            onClick = { showServerConfigDialog = true },
                            modifier = Modifier.padding(end = 4.dp).size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Configure LAN Host Server",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Connection Status Bubble (Clickable & Connection status aware)
                        val hasValidFcmToken = pushTokenVal.isNotEmpty() && !pushTokenVal.startsWith("device-push-token-")
                        val (statusColor, statusText, detailedStatus) = when {
                            serverUrlVal.isEmpty() -> Triple(Color(0xFFEF4444), "Disconnected", "No Home Server configured")
                            !serverReachableVal -> Triple(Color(0xFFFBBF24), "Connecting", "Waiting for server connection ($connStatus)")
                            !hasValidFcmToken -> Triple(Color(0xFFFBBF24), "No FCM Token", "Connected to Home Server, but FCM push token not registered")
                            else -> Triple(Color(0xFF10B981), "Connected", "Connected to Home Server with active FCM Push")
                        }

                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .clickable { showDozeInfoDialog = true }
                                .background(Color(0xFF1F2937).copy(alpha = 0.6f), RoundedCornerShape(100.dp))
                                .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(100.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // LED status dot representing push service optimization level
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                        .padding(1.dp)
                                )

                                Text(
                                    text = statusText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                if (!isImeVisible) {
                    NavigationBar(
                        modifier = Modifier.height(72.dp),
                        windowInsets = WindowInsets(0.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp
                    ) {
                    val badgeCount = pendingReqs.size
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = {
                            viewModel.setSelectedTab(0)
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        },
                        label = { Text("Contacts", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (badgeCount > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                            Text(badgeCount.toString(), color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Person, contentDescription = "Contacts")
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = Color.Gray,
                            unselectedIconColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = {
                            viewModel.setSelectedTab(1)
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        label = { Text("Chat", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.Email, contentDescription = "Chat") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = Color.Gray,
                            unselectedIconColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = {
                            viewModel.setSelectedTab(2)
                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                        },
                        label = { Text("Call Logs", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Outlined.Call, contentDescription = "Call Logs") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = Color.Gray,
                            unselectedIconColor = Color.Gray
                        )
                    )
                }
                }
            },
            floatingActionButton = {
                val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                if (!isImeVisible && selectedTab == 0) {
                    FloatingActionButton(
                        onClick = { showInviteDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create Invite")
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> ApprovalsTab(
                            pendingRequests = pendingReqs,
                            allRequests = requests,
                            activeInvites = allInvites,
                            onApprove = { viewModel.approveClient(it) },
                            onReject = { viewModel.rejectClient(it) },
                            onDelete = { viewModel.deleteClient(it) },
                            onRename = { request, newName -> viewModel.renameClient(request, newName) },
                            onDeleteInvite = { viewModel.deleteInvite(it) }
                        )
                        1 -> {
                            val selectedChatUuid by viewModel.selectedChatUuid.collectAsState()
                            val approvedRequests = requests.filter { it.status == "APPROVED" }
                            LaunchedEffect(approvedRequests) {
                                if (selectedChatUuid == null || !approvedRequests.any { it.uuid == selectedChatUuid }) {
                                    val firstUuid = approvedRequests.firstOrNull()?.uuid
                                    viewModel.setSelectedChatUuid(firstUuid)
                                }
                            }

                            ChatTab(
                                selectedClientUuid = selectedChatUuid,
                                approvedRequests = approvedRequests,
                                messages = messages,
                                onSendMessage = { uuid, name, text -> viewModel.sendMessage(uuid, name, text) },
                                onMarkAsRead = { viewModel.markMessagesRead(it) },
                                onActiveChatChanged = { viewModel.setSelectedChatUuid(it) },
                                onRedial = { uuid, name -> viewModel.redialClient(uuid, name) }
                            )
                        }
                        2 -> CallLogsTab(
                            callLogs = callLogs,
                            onRedial = { uuid, name -> viewModel.redialClient(uuid, name) },
                            onClearAll = { viewModel.clearAllHistory() }
                        )
                    }
                }
            }
        }

        // HEAVY DUTY FULL SCREEN WEBRTC STREAMING LAYOUT (When calling)
        AnimatedVisibility(
            visible = currentCallState !is CallState.Idle,
            enter = fadeIn() + expandIn(expandFrom = Alignment.Center),
            exit = fadeOut() + shrinkOut(shrinkTowards = Alignment.Center)
        ) {
            CallScreenOverlay(
                callState = currentCallState,
                onHangup = { viewModel.declineOrEndCall() },
                onAnswer = { clientUuid, clientName -> viewModel.acceptCall(clientUuid, clientName) }
            )
        }

        if (showInviteDialog) {
            var inviteName by remember { mutableStateOf("") }
            var createdInvite by remember { mutableStateOf<Invite?>(null) }
            val clipboardManager = LocalClipboardManager.current
            val scrollState = rememberScrollState()

            AlertDialog(
                onDismissRequest = { 
                    showInviteDialog = false 
                    createdInvite = null
                    inviteName = ""
                },
                title = { Text("Generate Invite Link", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (createdInvite == null) {
                            Text("Create a single-use invite code for a guest.", fontSize = 12.sp, color = Color.Gray)
                            OutlinedTextField(
                                value = inviteName,
                                onValueChange = { inviteName = it },
                                label = { Text("Guest Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                                )
                            )
                        } else {
                            val inviteUrl = "${serverUrlVal.removeSuffix("/")}/invite/${createdInvite!!.code}"
                            Text("Invite Generated!", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            
                            val qrBitmap = remember(inviteUrl) { generateQrCodeBitmap(inviteUrl, 512) }
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "QR Code",
                                    modifier = Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)
                                )
                            }
                            
                            SelectionContainer {
                                Text(
                                    text = inviteUrl,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            
                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(inviteUrl))
                                    Toast.makeText(context, "Invite link copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Copy Link", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {
                    if (createdInvite == null) {
                        Button(
                            onClick = {
                                if (inviteName.isNotBlank()) {
                                    createdInvite = viewModel.createInvite(inviteName)
                                }
                            },
                            enabled = inviteName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Generate", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { 
                                showInviteDialog = false 
                                createdInvite = null
                                inviteName = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    if (createdInvite == null) {
                        TextButton(onClick = { showInviteDialog = false }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    }
                },
                containerColor = Color(0xFF151926)
            )
        }

        // CORE NETWORK CONFIGURATION AND PAIRING SETTINGS DIALOG
        if (showServerConfigDialog) {
            var inputUrl by remember { mutableStateOf(serverUrlVal) }
            val clipboardManager = LocalClipboardManager.current
            val scrollState = rememberScrollState()

            AlertDialog(
                onDismissRequest = { showServerConfigDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Network & guest Settings",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // HOME SERVER SECTION
                        Text(
                            text = "HOME SERVER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )

                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            label = { Text("Home Server Address", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "FCM PUSH TOKEN",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                            val isTokenAvailable = pushTokenVal.isNotEmpty() && !pushTokenVal.startsWith("device-push-token-")
                            Text(
                                text = if (isTokenAvailable) "Active FCM" else "No FCM Token",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isTokenAvailable) Color(0xFF10B981) else Color(0xFFEF4444),
                                modifier = Modifier
                                    .background(
                                        if (isTokenAvailable) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                .border(1.dp, Color.Gray.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (pushTokenVal.isNotEmpty() && !pushTokenVal.startsWith("device-push-token-")) {
                                Text(
                                    text = "Your Home Server sends FCM push notifications to this token:",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    lineHeight = 14.sp
                                )
                                SelectionContainer {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = pushTokenVal,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(pushTokenVal))
                                                Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Copy Token",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                var manualApiKeyInput by remember {
                                    val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
                                    mutableStateOf(prefs.getString("fcm_api_key", "") ?: "")
                                }

                                Text(
                                    text = "FCM push token is not registered yet. Ensure your Home Server has generated 'whisker-push-sa.json' using 'python3 fcm_setup.py' or enter your Firebase Web API Key (AIzaSy...) below.",
                                    fontSize = 10.sp,
                                    color = Color(0xFFEF4444),
                                    lineHeight = 14.sp
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                        .border(1.dp, Color.Gray.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Firebase Web API Key",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    OutlinedTextField(
                                        value = manualApiKeyInput,
                                        onValueChange = { manualApiKeyInput = it },
                                        placeholder = { Text("AIzaSy...", fontSize = 11.sp, color = Color.Gray) },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            if (manualApiKeyInput.isNotBlank()) {
                                                viewModel.networkManager.updateCustomApiKey(manualApiKeyInput)
                                                Toast.makeText(context, "API Key saved. Re-registering FCM token...", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.align(Alignment.End),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Save & Connect FCM", fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))

                        Text(
                            text = "FCM SERVER KEY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))

                        // HOME SERVER ACCESS KEY SECTION
                        Text(
                            text = "HOME SERVER ACCESS KEY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                .border(1.dp, Color.Gray.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "This unique home server access key is randomly generated on first launch. On first connection, your home server pairs and locks to this key, preventing any unauthorized devices from connecting.",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                lineHeight = 14.sp
                            )
                            SelectionContainer {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = apiKeyVal,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(apiKeyVal))
                                            Toast.makeText(context, "Access key copied!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Copy Access Key",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.networkManager.updateServerUrl(inputUrl)
                            showServerConfigDialog = false
                            Toast.makeText(context, "Server link updated!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save & Apply", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showServerConfigDialog = false }
                    ) {
                        Text("Close", color = Color.Gray, fontSize = 12.sp)
                    }
                },
                containerColor = Color(0xFF151926),
                tonalElevation = 6.dp
            )
        }

        if (showDozeInfoDialog) {
            val (statusColor, statusText, detailedStatus) = when {
                serverUrlVal.isEmpty() -> Triple(Color(0xFFEF4444), "Disconnected", "No Home Server configured")
                !serverReachableVal -> Triple(Color(0xFFFBBF24), "Connecting", "Waiting for server connection ($connStatus)")
                else -> Triple(Color(0xFF10B981), "Connected", "Connected to Home Server")
            }

            AlertDialog(
                onDismissRequest = { showDozeInfoDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val titleIcon = when {
                            serverUrlVal.isEmpty() -> Icons.Default.Close
                            serverReachableVal -> Icons.Default.Check
                            else -> Icons.Default.Warning
                        }
                        Icon(titleIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(24.dp))
                        Text(statusText, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = detailedStatus,
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )
                        if (serverReachableVal && serverUrlVal.isNotEmpty()) {
                            Text(
                                text = "Home Server URL: $serverUrlVal",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showDozeInfoDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("OK", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                containerColor = Color(0xFF151926),
                tonalElevation = 6.dp
            )
        }
    }
}

// ==========================================
// APPROVALS TAB
// ==========================================
@Composable
fun ApprovalsTab(
    pendingRequests: List<ClientRequest>,
    allRequests: List<ClientRequest>,
    activeInvites: List<Invite>,
    onApprove: (ClientRequest) -> Unit,
    onReject: (ClientRequest) -> Unit,
    onDelete: (ClientRequest) -> Unit,
    onRename: (ClientRequest, String) -> Unit = { _, _ -> },
    onDeleteInvite: (Invite) -> Unit = {}
) {
    var editingRequest by remember { mutableStateOf<ClientRequest?>(null) }
    var renameInputText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Pending Queue header
        item {
            Text(
                text = "INCOMING VISITOR REQUESTS (${pendingRequests.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        }

        if (pendingRequests.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🛡️", fontSize = 24.sp)
                        Text(
                            text = "No Guest Requests",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "New guests scanning your guest web page will appear here to introduce themselves before gaining call authorization.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(pendingRequests) { request ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = request.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "GUEST ID: ${request.uuid}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = SimpleDateFormat("HH:mm • dd MMM", Locale.getDefault()).format(Date(request.timestamp)),
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }

                        // Visitor Statement area
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "REASON FOR REQUEST:",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = request.message,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { onApprove(request) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Allow Calls", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { onReject(request) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Decline Request", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Approved list
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "APPROVED CALLERS & DEVICES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                letterSpacing = 1.sp
            )
        }

        val approved = allRequests.filter { it.status == "APPROVED" }
        if (approved.isEmpty()) {
            item {
                Text(
                    text = "No approved guests yet. Allow a caller request above to start chatting and calling.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            }
        } else {
            items(approved) { request ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (request.isOnline) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = request.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    IconButton(
                                        onClick = {
                                            editingRequest = request
                                            renameInputText = request.name
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Rename",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = { onDelete(request) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }

                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "GUEST ACCESS PASS:",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = request.token,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        // Active Invites
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ACTIVE GUEST INVITES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                letterSpacing = 1.sp
            )
        }

        val active = activeInvites.filter { !it.isUsed }
        if (active.isEmpty()) {
            item {
                Text(
                    text = "No active invites.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            }
        } else {
            items(active) { invite ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                Text(
                                    text = invite.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            IconButton(
                                onClick = { onDeleteInvite(invite) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Revoke Invite", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }

                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "INVITE LINK:",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = invite.url,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingRequest != null) {
        val requestToRename = editingRequest!!
        AlertDialog(
            onDismissRequest = { editingRequest = null },
            title = { Text("Rename Contact", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Change display name for ${requestToRename.uuid}:", color = Color.Gray, fontSize = 12.sp)
                    TextField(
                        value = renameInputText,
                        onValueChange = { renameInputText = it },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.4f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInputText.isNotBlank()) {
                            onRename(requestToRename, renameInputText.trim())
                        }
                        editingRequest = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRequest = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// SECURE CHAT TAB
// ==========================================
@Composable
fun ChatTab(
    selectedClientUuid: String?,
    approvedRequests: List<ClientRequest>,
    messages: List<Message>,
    onSendMessage: (String, String, String) -> Unit,
    onMarkAsRead: (String) -> Unit,
    onActiveChatChanged: (String?) -> Unit,
    onRedial: (String, String) -> Unit
) {
    var replyText by remember { mutableStateOf("") }

    val selectedClient = approvedRequests.find { it.uuid == selectedClientUuid }

    LaunchedEffect(selectedClientUuid) {
        selectedClientUuid?.let { onMarkAsRead(it) }
    }

    if (approvedRequests.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("💬", fontSize = 48.sp)
                Text(
                    text = "No Authorized Chat Peers",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Chat sessions will become active once you authorize terminal connection requests under the 'Approvals' tab.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Horizontal client selector
            ScrollableTabRow(
                selectedTabIndex = approvedRequests.indexOf(selectedClient).coerceAtLeast(0),
                containerColor = MaterialTheme.colorScheme.background,
                edgePadding = 16.dp,
                divider = {}
            ) {
                approvedRequests.forEach { client ->
                    Tab(
                        selected = selectedClient?.uuid == client.uuid,
                        onClick = {
                            onActiveChatChanged(client.uuid)
                        },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Box(modifier = Modifier.size(6.dp).background(if (client.isOnline) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape))
                                Text(client.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }
            }

            // Divider
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))

            selectedClient?.let { client ->
                val clientMsgs = messages.filter { it.clientUuid == client.uuid }.sortedBy { it.timestamp }

                // Sub-header for the individual active conversation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1F2937).copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(if (client.isOnline) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape)
                        )
                        Text(
                            text = client.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    IconButton(
                        onClick = { onRedial(client.uuid, client.name) },
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Call Client",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color.Gray.copy(alpha = 0.1f))

                Box(modifier = Modifier.weight(1f)) {
                    if (clientMsgs.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📁", fontSize = 28.sp)
                            Text(
                                text = "Communication Terminal Active",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Send files, write text parameters, or wait for WebRTC triggers from ${client.name}.",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(clientMsgs.size) {
                            if (clientMsgs.isNotEmpty()) {
                                listState.scrollToItem(clientMsgs.size - 1)
                            }
                        }
                        
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(clientMsgs) { msg ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = if (msg.isIncoming) Alignment.CenterStart else Alignment.CenterEnd
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(0.85f),
                                        horizontalAlignment = if (msg.isIncoming) Alignment.Start else Alignment.End
                                    ) {
                                        Text(
                                            text = if (msg.isIncoming) msg.clientName else "Terminal Node (You)",
                                            fontSize = 9.sp,
                                            color = Color.Gray,
                                            modifier = Modifier.padding(bottom = 2.dp, start = 4.dp, end = 4.dp)
                                        )

                                        if (msg.filePath != null && msg.filePath != "null" && msg.filePath.isNotBlank()) {
                                            // Render as file transfer message
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (msg.isIncoming) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                                ),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (msg.isIncoming) Color.Gray.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                                ),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Text("📎", fontSize = 20.sp)
                                                    Column {
                                                        Text(
                                                            text = msg.messageText,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White
                                                        )
                                                        Text(
                                                            text = if (msg.filePath.startsWith("data:")) "File Attachment Data Stream" else msg.filePath,
                                                            fontSize = 9.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = Color.Gray,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            // Render as text msg
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (msg.isIncoming) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                                                        RoundedCornerShape(
                                                            topStart = 12.dp,
                                                            topEnd = 12.dp,
                                                            bottomStart = if (msg.isIncoming) 0.dp else 12.dp,
                                                            bottomEnd = if (msg.isIncoming) 12.dp else 0.dp
                                                        )
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (msg.isIncoming) Color.Gray.copy(alpha = 0.15f) else Color.Transparent,
                                                        RoundedCornerShape(
                                                            topStart = 12.dp,
                                                            topEnd = 12.dp,
                                                            bottomStart = if (msg.isIncoming) 0.dp else 12.dp,
                                                            bottomEnd = if (msg.isIncoming) 12.dp else 0.dp
                                                        )
                                                    )
                                                    .padding(12.dp)
                                            ) {
                                                Text(
                                                    text = msg.messageText,
                                                    fontSize = 13.sp,
                                                    color = if (msg.isIncoming) Color.White else Color.Black,
                                                    lineHeight = 18.sp
                                                )
                                            }
                                        }
                                        Text(
                                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                                            fontSize = 8.sp,
                                            color = Color.Gray,
                                            modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Chat Input line with prompt simulation controls
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Quick file payload attach button
                        var filePickerExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { filePickerExpanded = true },
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Attach File", tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(
                                expanded = filePickerExpanded,
                                onDismissRequest = { filePickerExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Share Document (LinkConfig.txt)", fontSize = 12.sp) },
                                    onClick = {
                                        onSendMessage(client.uuid, client.name, "LinkConfig.txt")
                                        filePickerExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share Image (Photo.png)", fontSize = 12.sp) },
                                    onClick = {
                                        onSendMessage(client.uuid, client.name, "Photo.png")
                                        filePickerExpanded = false
                                    }
                                )
                            }
                        }

                        // Text Field
                        BasicTextField(
                            value = replyText,
                            onValueChange = { replyText = it },
                            modifier = Modifier
                                .weight(1f)
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 3,
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (replyText.isEmpty()) {
                                        Text("Type a message...", color = Color.Gray, fontSize = 14.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        // Send back button
                        IconButton(
                            onClick = {
                                if (replyText.isNotBlank()) {
                                    onSendMessage(client.uuid, client.name, replyText)
                                    replyText = ""
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            enabled = replyText.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Transmit parameters",
                                tint = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// CALL LOGS TAB
// ==========================================
@Composable
fun CallLogsTab(
    callLogs: List<CallLog>,
    onRedial: (String, String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PEER CALL ATTEMPTS (${callLogs.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            if (callLogs.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text("Wipe Registers", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (callLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📞", fontSize = 32.sp)
                    Text(
                        text = "History Clear",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "All signal history and WebRTC log entries will register here.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(callLogs) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(14.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val iconTint = when (log.status) {
                                    "MISSED" -> Color.Red
                                    "CONNECTED" -> Color.Green
                                    "COMPLETED" -> Color(0xFF10B981)
                                    else -> Color.Gray
                                }
                                val icon = Icons.Default.Call

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(iconTint.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = log.status, tint = iconTint, modifier = Modifier.size(18.dp))
                                }

                                Column {
                                    Text(
                                        text = log.clientName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    val durationText = if (log.duration > 0) {
                                        val m = log.duration / 60
                                        val s = log.duration % 60
                                        if (m > 0) "${m}m ${s}s" else "${s}s duration"
                                    } else {
                                        log.status
                                    }
                                    Text(
                                        text = "${if (log.isIncoming) "Incoming" else "Outgoing"} • $durationText",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp)),
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                                IconButton(
                                    onClick = { onRedial(log.clientUuid, log.clientName) },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.Call,
                                        contentDescription = "Redial Router",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
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

// ==========================================
// INTEGRATED CALL SCREEN OVERLAY (WebRTC UI)
// ==========================================
@Composable
fun CallScreenOverlay(
    callState: CallState,
    onHangup: () -> Unit,
    onAnswer: (String, String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // Decorative glowing call signal rings
        val infiniteTransition = rememberInfiniteTransition()
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )

        when (callState) {
            is CallState.Incoming -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 64.dp)
                    ) {
                        Text(
                            text = "SECURE TUNNEL CALL INCOMING",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = if (callState.isVideo) "VIDEO ROUTE REQ" else "VOICE LINE REQ",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Pulsing Profile Box
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(160.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(pulseScale)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⚡",
                                fontSize = 42.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = callState.clientName,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "UUID: ${callState.clientUuid}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    // Acceptance controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        modifier = Modifier.padding(bottom = 64.dp)
                    ) {
                        // Decline
                        IconButton(
                            onClick = onHangup,
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color.Red, CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Decline call", tint = Color.White, modifier = Modifier.size(28.dp))
                        }

                        // Accept
                        IconButton(
                            onClick = { onAnswer(callState.clientUuid, callState.clientName) },
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFF10B981), CircleShape)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Accept call", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }

            is CallState.Outgoing -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 64.dp)
                    ) {
                        Text(
                            text = "HANDSHAKING WEBRTC GATEWAY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            letterSpacing = 2.sp
                        )
                    }

                    // Dialing animations
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(160.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(pulseScale)
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = pulseAlpha), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .border(2.dp, MaterialTheme.colorScheme.secondary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "🌀",
                                fontSize = 42.sp,
                                modifier = Modifier.scale(if (pulseScale > 1f) 1.2f else 1f)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = callState.clientName,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Ringing...",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    IconButton(
                        onClick = onHangup,
                        modifier = Modifier
                            .padding(bottom = 64.dp)
                            .size(64.dp)
                            .background(Color.Red, CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel Dialing", tint = Color.White)
                    }
                }
            }

            is CallState.Active -> {
                // Interactive split screen layout representing actual live connection
                Column(modifier = Modifier.fillMaxSize()) {
                    
                    // TOP GRID: Live peer remote visual stream (Simulated WebRTC canvas)
                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxWidth()
                            .background(Color.Black)
                            .border(1.dp, Color.Gray.copy(alpha = 0.2f))
                    ) {
                        // Drawing an active voice spectrum on canvas inside the layout to represent active call streaming
                        val wavePhase = rememberInfiniteTransition()
                        val waveOffset by wavePhase.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1800, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            )
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val centerY = size.height / 2
                            val points = 60
                            val spacing = size.width / points
                            val strokeBrush = Brush.linearGradient(
                                colors = listOf(Color(0xFF06B6D4), Color(0xFF8B5CF6))
                            )
                            for (i in 0 until points) {
                                val amplitude = (centerY * 0.4f) * kotlin.math.sin(Math.toRadians((i * 12 + waveOffset).toDouble())).toFloat()
                                drawLine(
                                    brush = strokeBrush,
                                    start = androidx.compose.ui.geometry.Offset(i * spacing, centerY - amplitude),
                                    end = androidx.compose.ui.geometry.Offset(i * spacing, centerY + amplitude),
                                    strokeWidth = 4.dp.value
                                )
                            }
                        }

                        // Peer details cardoverlay
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text(
                                    text = callState.clientName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "SECURE WEBRTC SESSION",
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Timer Duration overlay
                        val minutes = callState.durationSeconds / 60
                        val seconds = callState.durationSeconds % 60
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = String.format("%02d:%02d", minutes, seconds),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }

                    // BOTTOM GRID: Camera feed feedback (local view)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Local audio visualizer
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("🎤", fontSize = 36.sp)
                            Text(
                                text = "Audio Call Active",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Secure connection established",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }

                        // PiP Mini window previewing remote node status
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .size(110.dp, 160.dp)
                                .background(Color.Black, RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text("🌐", fontSize = 18.sp)
                                Text(
                                    text = "Peer Router",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "TUNNEL STABLE",
                                    fontSize = 8.sp,
                                    color = Color.Green,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Call controls bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .padding(vertical = 20.dp, horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var isMuted by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { isMuted = !isMuted },
                            modifier = Modifier
                                .size(48.dp)
                                .background(if (isMuted) Color.Red else Color.Gray.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Text(
                                text = if (isMuted) "MUTED" else "MIC ON",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = onHangup,
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color.Red, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "End Call",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        var camEnabled by remember { mutableStateOf(true) }
                        IconButton(
                            onClick = { camEnabled = !camEnabled },
                            modifier = Modifier
                                .size(48.dp)
                                .background(if (!camEnabled) Color.Red else Color.Gray.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Text(
                                text = if (camEnabled) "CAM ON" else "CAM OFF",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            else -> {}
        }
    }
}

fun generateQrCodeBitmap(content: String, size: Int): Bitmap? {
    if (content.isEmpty()) return null
    return try {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
