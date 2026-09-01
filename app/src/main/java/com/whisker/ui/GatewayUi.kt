package com.whisker.ui

import com.whisker.R
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisker.data.*
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
    val allowDirectCalls by viewModel.allowDirectCalls.collectAsState()

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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isTablet = maxWidth >= 600.dp

        val topBarContent: @Composable () -> Unit = {
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
                    val (statusColor, statusText, detailedStatus) = when {
                        serverUrlVal.isEmpty() -> Triple(Color(0xFFEF4444), "Disconnected", "No Home Server configured")
                        serverReachableVal -> Triple(Color(0xFF10B981), "Connected", "Connected to Home Server with active events sync")
                        connStatus.contains("Connecting", ignoreCase = true) -> Triple(Color(0xFFFBBF24), "Connecting", "Connecting to Home Server ($connStatus)")
                        else -> Triple(Color(0xFFEF4444), "Disconnected", "Waiting for server connection ($connStatus)")
                    }

                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .clickable {
                                showDozeInfoDialog = true
                            }
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
        }

        val fabContent: @Composable () -> Unit = {
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

        val mainPagerContent: @Composable () -> Unit = {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> ApprovalsTab(
                        pendingRequests = pendingReqs,
                        allRequests = requests,
                        activeInvites = allInvites,
                        allowDirectCalls = allowDirectCalls,
                        isTablet = isTablet,
                        onToggleDirectCalls = { viewModel.toggleAllowDirectCalls(it) },
                        onApprove = { viewModel.approveClient(it) },
                        onReject = { viewModel.rejectClient(it) },
                        onDelete = { viewModel.deleteClient(it) },
                        onRename = { request, newName -> viewModel.renameClient(request, newName) },
                        onDeleteInvite = { viewModel.deleteInvite(it) },
                        onToggleAutoAccept = { request, autoAccept -> viewModel.toggleAutoAccept(request, autoAccept) },
                        onChat = { uuid ->
                            viewModel.setSelectedChatUuid(uuid)
                            viewModel.setSelectedTab(1)
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        onCall = { uuid, name ->
                            viewModel.redialClient(uuid, name)
                        }
                    )
                    1 -> {
                        val selectedChatUuid by viewModel.selectedChatUuid.collectAsState()
                        val approvedRequests = requests.filter { it.status == "APPROVED" || it.status == "MOBILE_APPROVED" }
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
                            isTablet = isTablet,
                            onSendMessage = { uuid, name, text -> viewModel.sendMessage(uuid, name, text) },
                            onMarkAsRead = { viewModel.markMessagesRead(it) },
                            onActiveChatChanged = { viewModel.setSelectedChatUuid(it) },
                            onRedial = { uuid, name -> viewModel.redialClient(uuid, name) }
                        )
                    }
                    2 -> CallLogsTab(
                        callLogs = callLogs,
                        isTablet = isTablet,
                        onRedial = { uuid, name -> viewModel.redialClient(uuid, name) },
                        onClearAll = { viewModel.clearAllHistory() }
                    )
                }
            }
        }

        if (isTablet) {
            // Adaptive Tablet Layout with Side NavigationRail
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_app_logo_transparent),
                                contentDescription = "App Icon",
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Whisker",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }
                ) {
                    val badgeCount = pendingReqs.size
                    NavigationRailItem(
                        selected = selectedTab == 0,
                        onClick = {
                            viewModel.setSelectedTab(0)
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        },
                        label = { Text("Contacts", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
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
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = Color.Gray,
                            unselectedIconColor = Color.Gray
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    NavigationRailItem(
                        selected = selectedTab == 1,
                        onClick = {
                            viewModel.setSelectedTab(1)
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        label = { Text("Chat", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.Email, contentDescription = "Chat") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = Color.Gray,
                            unselectedIconColor = Color.Gray
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    NavigationRailItem(
                        selected = selectedTab == 2,
                        onClick = {
                            viewModel.setSelectedTab(2)
                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                        },
                        label = { Text("Call Logs", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Outlined.Call, contentDescription = "Call Logs") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = Color.Gray,
                            unselectedIconColor = Color.Gray
                        )
                    )
                }

                VerticalDivider(color = Color.Gray.copy(alpha = 0.2f))

                Scaffold(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentWindowInsets = WindowInsets.systemBars,
                    topBar = topBarContent,
                    floatingActionButton = fabContent
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .imePadding()
                    ) {
                        mainPagerContent()
                    }
                }
            }
        } else {
            // Mobile Layout with Standard NavigationBar respecting window insets
            Scaffold(
                contentWindowInsets = WindowInsets.systemBars,
                topBar = topBarContent,
                bottomBar = {
                    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    if (!isImeVisible) {
                        NavigationBar(
                            windowInsets = NavigationBarDefaults.windowInsets,
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
                floatingActionButton = fabContent
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                        .imePadding()
                ) {
                    mainPagerContent()
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
                networkManager = viewModel.networkManager,
                onHangup = { viewModel.declineOrEndCall() },
                onAnswer = { clientUuid, clientName -> viewModel.acceptCall(clientUuid, clientName) }
            )
        }

        val callDeclinedMsg by viewModel.networkManager.callDeclinedEvent.collectAsState()
        callDeclinedMsg?.let { declinedReason ->
            AlertDialog(
                onDismissRequest = { viewModel.networkManager.dismissCallDeclined() },
                icon = {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "Call Declined",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "Call Declined",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = declinedReason,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.networkManager.dismissCallDeclined() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("OK", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        if (showInviteDialog) {
            var selectedDialogTab by remember { mutableStateOf(0) } // 0: Generate, 1: Claim
            var inviteName by remember { mutableStateOf("") }
            var createdInvite by remember { mutableStateOf<Invite?>(null) }
            var inviteInput by remember { mutableStateOf("") }
            var isConnecting by remember { mutableStateOf(false) }
            var claimErrorMessage by remember { mutableStateOf<String?>(null) }
            val clipboardManager = LocalClipboardManager.current
            val scrollState = rememberScrollState()

            AlertDialog(
                onDismissRequest = { 
                    if (!isConnecting) {
                        showInviteDialog = false 
                        createdInvite = null
                        inviteName = ""
                        inviteInput = ""
                        claimErrorMessage = null
                    }
                },
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Invite & Connect",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        TabRow(
                            selectedTabIndex = selectedDialogTab,
                            containerColor = Color(0xFF0F172A),
                            contentColor = MaterialTheme.colorScheme.primary,
                            divider = {}
                        ) {
                            Tab(
                                selected = selectedDialogTab == 0,
                                onClick = { selectedDialogTab = 0 },
                                text = {
                                    Text(
                                        "➕ Generate",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (selectedDialogTab == 0) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                }
                            )
                            Tab(
                                selected = selectedDialogTab == 1,
                                onClick = { selectedDialogTab = 1 },
                                text = {
                                    Text(
                                        "🔗 Claim",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (selectedDialogTab == 1) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                }
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (selectedDialogTab == 0) {
                            // GENERATE INVITE TAB
                            if (createdInvite == null) {
                                Text(
                                    text = "Create a single-use invite code for a visitor or another mobile app on your home server.",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    lineHeight = 16.sp
                                )
                                OutlinedTextField(
                                    value = inviteName,
                                    onValueChange = { inviteName = it },
                                    label = { Text("Guest / Peer Name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else {
                                val inviteUrl = "${serverUrlVal.removeSuffix("/")}/invite/${createdInvite!!.code}"
                                Text("Invite Generated!", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                
                                val qrBitmap = remember(inviteUrl) { generateQrCodeBitmap(inviteUrl, 512) }
                                if (qrBitmap != null) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = "QR Code",
                                        modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)
                                    )
                                }
                                
                                SelectionContainer {
                                    Text(
                                        text = inviteUrl,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(top = 4.dp)
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
                        } else {
                            // CLAIM INVITE TAB (Same Home Server or Different Home Server)
                            Text(
                                text = "Paste an invite code (same home server) or full invite URL (different home server) to connect:",
                                fontSize = 12.sp,
                                color = Color.LightGray,
                                lineHeight = 16.sp
                            )

                            OutlinedTextField(
                                value = inviteInput,
                                onValueChange = {
                                    inviteInput = it
                                    claimErrorMessage = null
                                },
                                label = { Text("Invite Code or Link", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) },
                                placeholder = { Text("e.g. abc12 or http://.../invite/abc12", fontSize = 11.sp, color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isConnecting,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val clipText = clipboardManager.getText()?.text
                                            if (!clipText.isNullOrBlank()) {
                                                inviteInput = clipText.trim()
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Paste",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )

                            if (claimErrorMessage != null) {
                                Text(
                                    text = claimErrorMessage!!,
                                    color = Color(0xFFEF4444),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }

                            if (isConnecting) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Connecting to peer...", color = Color.LightGray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (selectedDialogTab == 0) {
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
                    } else {
                        Button(
                            onClick = {
                                if (inviteInput.isNotBlank()) {
                                    isConnecting = true
                                    claimErrorMessage = null
                                    viewModel.claimInvite(inviteInput.trim()) { success, msg ->
                                        isConnecting = false
                                        if (success) {
                                            Toast.makeText(context, "Successfully connected to peer!", Toast.LENGTH_LONG).show()
                                            showInviteDialog = false
                                            inviteInput = ""
                                        } else {
                                            claimErrorMessage = msg
                                        }
                                    }
                                }
                            },
                            enabled = inviteInput.isNotBlank() && !isConnecting,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Claim & Connect", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    if (!isConnecting && (selectedDialogTab == 1 || createdInvite == null)) {
                        TextButton(onClick = { 
                            showInviteDialog = false 
                            createdInvite = null
                            inviteName = ""
                            inviteInput = ""
                            claimErrorMessage = null
                        }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    }
                },
                containerColor = Color(0xFF151926)
            )
        }

        // CORE NETWORK CONFIGURATION AND PAIRING SETTINGS DIALOG
        if (showServerConfigDialog) {
            val usernameVal by viewModel.networkManager.hostUsername.collectAsState()
            var inputUrl by remember(serverUrlVal) { mutableStateOf(serverUrlVal) }
            var inputUsername by remember(usernameVal) { mutableStateOf(usernameVal) }
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
                            text = "Network & Guest Settings",
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
                            .heightIn(max = 520.dp)
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
                            value = inputUsername,
                            onValueChange = { inputUsername = it },
                            label = { Text("Mobile App Username (Visible to Guests)", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(10.dp)
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
                            viewModel.networkManager.updateSettings(inputUrl, inputUsername)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                viewModel.refreshConnectionAndEvents()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Refresh", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { showDozeInfoDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("OK", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                },
                containerColor = Color(0xFF151926),
                tonalElevation = 6.dp
            )
        }

        // Active Mobile Pairing Prompt Dialog
        val mobilePendingRequest = pendingReqs.firstOrNull { it.status == "MOBILE_PENDING" }
        if (mobilePendingRequest != null) {
            AlertDialog(
                onDismissRequest = { },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0xFF8B5CF6).copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Companion Mobile Device",
                            tint = Color(0xFFA78BFA),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                },
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Mobile Companion Pairing",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = "Secondary Mobile App Request",
                            fontSize = 12.sp,
                            color = Color(0xFFA78BFA),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E1B4B).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Device Name:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = mobilePendingRequest.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Device UUID:",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = mobilePendingRequest.uuid,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFA78BFA)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "This mobile app is requesting pairing authorization with your Home Server. Approving allows it to act as an authorized companion client.",
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            lineHeight = 16.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.approveClient(mobilePendingRequest) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Approve Mobile App", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { viewModel.rejectClient(mobilePendingRequest) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Decline", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                containerColor = Color(0xFF151926),
                tonalElevation = 6.dp
            )
        }
    }
}

// ==========================================
// COMPACT CONTACT CARD (Single line with expandable details)
// ==========================================
@Composable
fun CompactContactCard(
    request: ClientRequest,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onChat: (String) -> Unit,
    onCall: (String, String) -> Unit,
    onRename: (ClientRequest) -> Unit,
    onDelete: (ClientRequest) -> Unit,
    onToggleAutoAccept: (ClientRequest, Boolean) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val isMobileApp = request.status == "MOBILE_APPROVED" || request.clientType == "MOBILE"
    val isRemote = request.serverAddress.isNotBlank() && request.serverAddress != "local"
    val serverDisplay = if (isRemote) request.serverAddress else "local"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggleExpand() },
        colors = CardDefaults.cardColors(
            containerColor = if (isMobileApp) Color(0xFF1E1B4B).copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        ),
        border = BorderStroke(
            1.dp,
            if (isMobileApp) Color(0xFF8B5CF6).copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Main Compact Single Line: Status LED + Avatar / Name + Badges + Quick Action Buttons (Chat, Call) + Expand Chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: LED & Name with Mobile/Server badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (request.isOnline) Color(0xFF10B981) else Color(0xFF64748B), CircleShape)
                    )
                    Text(
                        text = request.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Compact badge
                    Surface(
                        color = if (isMobileApp) Color(0xFF8B5CF6).copy(alpha = 0.25f) else Color(0xFF3B82F6).copy(alpha = 0.25f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isMobileApp) "📱" else "💻",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    if (isMobileApp && isRemote) {
                        Surface(
                            color = Color(0xFF1E3A8A).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "🌐 $serverDisplay",
                                fontSize = 9.sp,
                                color = Color(0xFF93C5FD),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Right: Quick Chat button + Quick Call button + Expand chevron
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .clickable { onChat(request.uuid) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = "Chat",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { onCall(request.uuid, request.name) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { onToggleExpand() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Expanded Details Section
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                    // Identifier details & copy button
                    Surface(
                        color = Color.Black.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isMobileApp) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "HOME SERVER:",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = if (isRemote) serverDisplay else "local (Same Server)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRemote) Color(0xFF93C5FD) else Color(0xFF6EE7B7)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isMobileApp) "PEER UUID:" else "GUEST PASS:",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = if (isMobileApp) request.uuid else request.token,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isMobileApp) Color(0xFFA78BFA) else MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(if (isMobileApp) request.uuid else request.token))
                                            Toast.makeText(context, "Copied ID to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy ID",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "STATUS:",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = if (request.isOnline) "● Online / Ready" else "○ Offline",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (request.isOnline) Color(0xFF10B981) else Color.Gray
                                )
                            }
                        }
                    }

                    // Auto Accept Switch & Management Actions (Rename / Delete)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Switch(
                                checked = request.autoAcceptCall,
                                onCheckedChange = { isChecked ->
                                    onToggleAutoAccept(request, isChecked)
                                },
                                modifier = Modifier
                                    .scale(0.75f)
                                    .testTag("auto_accept_switch_${request.uuid}"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = Color.LightGray,
                                    uncheckedTrackColor = Color(0xFF374151)
                                )
                            )
                            Text(
                                text = "Auto Accept Calls",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (request.autoAcceptCall) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { onRename(request) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Rename", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }

                            TextButton(
                                onClick = { onDelete(request) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Delete", fontSize = 11.sp, color = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }
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
    allowDirectCalls: Boolean = false,
    isTablet: Boolean = false,
    onToggleDirectCalls: (Boolean) -> Unit = {},
    onApprove: (ClientRequest) -> Unit,
    onReject: (ClientRequest) -> Unit,
    onDelete: (ClientRequest) -> Unit,
    onRename: (ClientRequest, String) -> Unit = { _, _ -> },
    onDeleteInvite: (Invite) -> Unit = {},
    onToggleAutoAccept: (ClientRequest, Boolean) -> Unit = { _, _ -> },
    onChat: (String) -> Unit = {},
    onCall: (String, String) -> Unit = { _, _ -> }
) {
    var editingRequest by remember { mutableStateOf<ClientRequest?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    val expandedContactUuids = remember { mutableStateMapOf<String, Boolean>() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val mobilePending = pendingRequests.filter { it.status == "MOBILE_PENDING" }
    val visitorPending = pendingRequests.filter { it.status == "PENDING" }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        // Direct Calls (Pre-Approval) Setting Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, if (allowDirectCalls) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("⚡", fontSize = 20.sp)
                        Column {
                            Text(
                                text = "Allow Direct Calls (Pre-Approval)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Permit web visitors to call immediately before access approval by stating their reason (10s timeout).",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                lineHeight = 13.sp
                            )
                        }
                    }
                    Switch(
                        checked = allowDirectCalls,
                        onCheckedChange = onToggleDirectCalls,
                        modifier = Modifier.scale(0.85f).testTag("direct_calls_toggle_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color(0xFF374151)
                        )
                    )
                }
            }
        }
        // Companion Mobile App Queue (if any)
        if (mobilePending.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📱", fontSize = 16.sp)
                    Text(
                        text = "COMPANION MOBILE APP REQUESTS (${mobilePending.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFA78BFA),
                        letterSpacing = 1.sp
                    )
                }
            }

            items(mobilePending) { request ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B).copy(alpha = 0.8f)),
                    border = BorderStroke(1.5.dp, Color(0xFF8B5CF6))
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(Color(0xFF8B5CF6).copy(alpha = 0.25f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = "Mobile Device",
                                        tint = Color(0xFFA78BFA),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = request.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Surface(
                                            color = Color(0xFF8B5CF6).copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.6f))
                                        ) {
                                            Text(
                                                text = "📱 MOBILE APP",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFA78BFA),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "DEVICE UUID: ${request.uuid}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFA78BFA).copy(alpha = 0.8f)
                                    )
                                }
                            }

                            Text(
                                text = SimpleDateFormat("HH:mm • dd MMM", Locale.getDefault()).format(Date(request.timestamp)),
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "PAIRING REQUEST:",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFA78BFA),
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "A secondary mobile app or tablet ('${request.name}') wants to connect to your Home Server to receive intercom calls and manage events.",
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Approve Mobile App", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { onReject(request) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Decline", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Web Visitor Pending Queue header
        item {
            Text(
                text = "INCOMING VISITOR REQUESTS (${visitorPending.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        }

        if (visitorPending.isEmpty() && mobilePending.isEmpty()) {
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
        } else if (visitorPending.isNotEmpty()) {
            items(visitorPending) { request ->
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

        // Approved list with compact expandable cards
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "APPROVED CALLERS & PEER CONTACTS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                letterSpacing = 1.sp
            )
        }

        val approved = allRequests.filter { it.status == "APPROVED" || it.status == "MOBILE_APPROVED" }
        if (approved.isEmpty()) {
            item {
                Text(
                    text = "No approved contacts yet. Connect to a mobile app or allow a visitor request to start chatting and calling.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            }
        } else {
            items(approved, key = { it.uuid }) { request ->
                val isExpanded = expandedContactUuids[request.uuid] ?: false
                CompactContactCard(
                    request = request,
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        expandedContactUuids[request.uuid] = !isExpanded
                    },
                    onChat = onChat,
                    onCall = onCall,
                    onRename = {
                        editingRequest = it
                        renameInputText = it.name
                    },
                    onDelete = onDelete,
                    onToggleAutoAccept = onToggleAutoAccept
                )
            }
        }

        // Active Invites
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ACTIVE GUEST & PEER INVITES",
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
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = invite.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = invite.url,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(invite.url))
                                    Toast.makeText(context, "Copied invite link", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy Invite URL",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            IconButton(
                                onClick = { onDeleteInvite(invite) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Revoke Invite",
                                    tint = Color.Gray,
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
    isTablet: Boolean = false,
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
                    val isMobile = client.status == "MOBILE_APPROVED" || client.clientType == "MOBILE"
                    val isRemote = client.serverAddress.isNotBlank() && client.serverAddress != "local"
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
                                Text(
                                    text = if (isMobile) "📱 ${client.name}" else client.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isRemote) {
                                    Text("🌐", fontSize = 10.sp)
                                }
                            }
                        }
                    )
                }
            }

            // Divider
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))

            selectedClient?.let { client ->
                val clientMsgs = messages.filter { it.clientUuid == client.uuid }.sortedBy { it.timestamp }
                val isMobile = client.status == "MOBILE_APPROVED" || client.clientType == "MOBILE"
                val isRemote = client.serverAddress.isNotBlank() && client.serverAddress != "local"
                val serverDisplay = if (isRemote) client.serverAddress else "local"

                // Sub-header for the individual active conversation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1F2937).copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
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

                        // Badges Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = if (isMobile) Color(0xFF8B5CF6).copy(alpha = 0.25f) else Color(0xFF3B82F6).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isMobile) "📱 Mobile App" else "💻 Web Client",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isMobile) Color(0xFFA78BFA) else Color(0xFF93C5FD),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }

                            Surface(
                                color = if (isRemote) Color(0xFF1E3A8A).copy(alpha = 0.5f) else Color(0xFF064E3B).copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isRemote) "🌐 $serverDisplay" else "🏠 local",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRemote) Color(0xFF93C5FD) else Color(0xFF6EE7B7),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    
                    IconButton(
                        onClick = { onRedial(client.uuid, client.name) },
                        modifier = Modifier
                            .size(32.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Call Client",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
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
    isTablet: Boolean = false,
    onRedial: (String, String) -> Unit,
    onClearAll: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .fillMaxWidth()
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
}

// ==========================================
// INTEGRATED CALL SCREEN OVERLAY (WebRTC UI)
// ==========================================
@Composable
fun CallScreenOverlay(
    callState: CallState,
    networkManager: GatewayNetworkManager,
    onHangup: () -> Unit,
    onAnswer: (String, String) -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager }
    
    var isMicEnabled by remember { mutableStateOf(true) }
    var isCamEnabled by remember { mutableStateOf(true) }
    var isSpeakerOn by remember { mutableStateOf(true) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    val callSessionKey = remember(callState::class, (callState as? CallState.Active)?.clientUuid, (callState as? CallState.Incoming)?.clientUuid, (callState as? CallState.Outgoing)?.clientUuid) {
        when (callState) {
            is CallState.Active -> "active_${callState.clientUuid}_${callState.isVideo}"
            is CallState.Outgoing -> "outgoing_${callState.clientUuid}_${callState.isVideo}"
            is CallState.Incoming -> "incoming_${callState.clientUuid}_${callState.isVideo}"
            is CallState.Idle -> "idle"
        }
    }

    LaunchedEffect(callSessionKey) {
        if (callState !is CallState.Idle) {
            val hasCam = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasCam || !hasMic) {
                permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
            }
        }

        if (callState is CallState.Active) {
            isCamEnabled = callState.isVideo
            isMicEnabled = true
            isSpeakerOn = callState.isVideo
            try {
                audioManager?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                audioManager?.isSpeakerphoneOn = callState.isVideo
            } catch (e: Exception) {
                Log.e("GatewayUi", "Error configuring audio manager", e)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                audioManager?.mode = android.media.AudioManager.MODE_NORMAL
                audioManager?.isSpeakerphoneOn = false
            } catch (e: Exception) {
                Log.e("GatewayUi", "Error resetting audio mode", e)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "CallPulseTransition")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseScale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseAlpha"
        )

        when (callState) {
            is CallState.Incoming -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // TOP BADGE
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 24.dp)
                    ) {
                        Surface(
                            color = if (callState.isDirectCall) Color(0xFFD97706).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, if (callState.isDirectCall) Color(0xFFF59E0B).copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (callState.isDirectCall) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                Text(
                                    text = if (callState.isDirectCall) "PRE-APPROVAL DIRECT CALL" else "INCOMING CALL",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (callState.isDirectCall) Color(0xFFFBBF24) else MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        Text(
                            text = if (callState.isVideo) "VIDEO CALL REQUEST" else "VOICE CALL REQUEST",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // CENTER AVATAR WITH PULSE
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(170.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(pulseScale)
                                    .background(
                                        (if (callState.isDirectCall) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary).copy(alpha = pulseAlpha),
                                        CircleShape
                                    )
                            )
                            Surface(
                                modifier = Modifier.size(105.dp),
                                shape = CircleShape,
                                color = Color(0xFF0F172A),
                                border = BorderStroke(2.dp, if (callState.isDirectCall) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary),
                                shadowElevation = 12.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (callState.isDirectCall) "⚡" else "📞",
                                        fontSize = 44.sp
                                    )
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = callState.clientName,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Guest ID: ${callState.clientUuid.take(8)}...",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF64748B),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        if (callState.isDirectCall && callState.reason.isNotBlank()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                color = Color(0xFF1E293B).copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = 8.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "VISITOR'S MESSAGE:",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFBBF24),
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        text = callState.reason,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = Color.White,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }

                    // BOTTOM ACTION BUTTONS
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onHangup,
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626))))
                                    .border(1.5.dp, Color(0xFFF87171).copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = "Decline call", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            Text("Decline", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFCA5A5))
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { onAnswer(callState.clientUuid, callState.clientName) },
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669))))
                                    .border(1.5.dp, Color(0xFF34D399).copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Accept call", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            Text("Accept", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6EE7B7))
                        }
                    }
                }
            }

            is CallState.Outgoing, is CallState.Active -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    // LIVE WEBRTC CAMERA & PEER VIDEO STREAM ENGINE
                    WebRtcCallContainer(
                        callState = callState,
                        networkManager = networkManager,
                        isMicEnabled = isMicEnabled,
                        isCamEnabled = isCamEnabled,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (callState is CallState.Outgoing) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF020617).copy(alpha = 0.9f))
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(top = 24.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        )
                                        Text(
                                            text = "DIALING PEER GATEWAY",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(170.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .scale(pulseScale)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha), CircleShape)
                                    )
                                    Surface(
                                        modifier = Modifier.size(105.dp),
                                        shape = CircleShape,
                                        color = Color(0xFF0F172A),
                                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                        shadowElevation = 12.dp
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "📞",
                                                fontSize = 44.sp
                                            )
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = callState.clientName,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Connecting P2P tunnel...",
                                        fontSize = 13.sp,
                                        color = Color(0xFF94A3B8),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 24.dp)
                            ) {
                                IconButton(
                                    onClick = onHangup,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626))))
                                        .border(1.5.dp, Color(0xFFF87171).copy(alpha = 0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Default.CallEnd, contentDescription = "Cancel Dialing", tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                                Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFCA5A5))
                            }
                        }
                    } else if (callState is CallState.Active) {
                        if (!callState.isVideo) {
                            // DEDICATED AUDIO-ONLY CALL UI (Native Jetpack Compose)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color(0xFF030712),
                                                Color(0xFF0F172A),
                                                Color(0xFF020617)
                                            )
                                        )
                                    )
                            ) {
                                // Background WebRTC engine (audio stream)
                                WebRtcCallContainer(
                                    callState = callState,
                                    networkManager = networkManager,
                                    isMicEnabled = isMicEnabled,
                                    isCamEnabled = false,
                                    modifier = Modifier.size(1.dp).alpha(0.01f)
                                )

                                // TOP OVERLAY: Peer Name + Duration Timer
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = Color(0xFF0F172A).copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                        shadowElevation = 8.dp
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = callState.clientName.take(1).uppercase(),
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(
                                                text = callState.clientName,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }

                                    val minutes = callState.durationSeconds / 60
                                    val seconds = callState.durationSeconds % 60
                                    Surface(
                                        color = Color(0xFF0F172A).copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                        shadowElevation = 8.dp
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .background(Color(0xFF10B981), CircleShape)
                                            )
                                            Text(
                                                text = String.format("%02d:%02d", minutes, seconds),
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }

                                // CENTER: Audio Call Avatar & Live Waveform Visualizer
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(24.dp)
                                ) {
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
                                        Surface(
                                            modifier = Modifier.size(110.dp),
                                            shape = CircleShape,
                                            color = Color(0xFF0F172A),
                                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                            shadowElevation = 12.dp
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = callState.clientName.take(1).uppercase(),
                                                    fontSize = 44.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
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
                                            text = "Voice Call Connected",
                                            fontSize = 13.sp,
                                            color = Color(0xFF94A3B8),
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    // Dynamic Audio Waveform Equalizer
                                    Row(
                                        modifier = Modifier
                                            .height(48.dp)
                                            .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val waveCount = 18
                                        for (i in 0 until waveCount) {
                                            val barHeightFraction = remember(i, pulseScale) {
                                                val phase = (i * 0.45f) + (pulseScale * 3.14f)
                                                0.25f + 0.75f * ((Math.sin(phase.toDouble()).toFloat() + 1f) / 2f)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .width(4.dp)
                                                    .fillMaxHeight(fraction = barHeightFraction.coerceIn(0.2f, 1f))
                                                    .background(
                                                        brush = Brush.verticalGradient(
                                                            listOf(
                                                                MaterialTheme.colorScheme.primary,
                                                                Color(0xFF38BDF8)
                                                            )
                                                        ),
                                                        shape = RoundedCornerShape(2.dp)
                                                    )
                                            )
                                        }
                                    }
                                }

                                // BOTTOM OVERLAY: Audio Call Controls Dock (Mic, Speaker, End)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .padding(horizontal = 24.dp, vertical = 16.dp)
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF0F172A).copy(alpha = 0.9f),
                                        shape = RoundedCornerShape(28.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                        shadowElevation = 16.dp
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 1. MIC MUTE BUTTON
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                IconButton(
                                                    onClick = { isMicEnabled = !isMicEnabled },
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isMicEnabled) Color.White.copy(alpha = 0.12f)
                                                            else Color(0xFFEF4444).copy(alpha = 0.9f)
                                                        )
                                                        .border(
                                                            1.dp,
                                                            if (isMicEnabled) Color.White.copy(alpha = 0.15f) else Color(0xFFEF4444),
                                                            CircleShape
                                                        )
                                                ) {
                                                    Icon(
                                                        imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                                        contentDescription = if (isMicEnabled) "Mute Microphone" else "Unmute Microphone",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                                Text(
                                                    text = if (isMicEnabled) "Mute" else "Muted",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (isMicEnabled) Color(0xFF94A3B8) else Color(0xFFFCA5A5)
                                                )
                                            }

                                            // 2. SPEAKERPHONE BUTTON
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        isSpeakerOn = !isSpeakerOn
                                                        try {
                                                            audioManager?.isSpeakerphoneOn = isSpeakerOn
                                                        } catch (e: Exception) {
                                                            Log.e("GatewayUi", "Error toggling speakerphone", e)
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isSpeakerOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                                            else Color.White.copy(alpha = 0.12f)
                                                        )
                                                        .border(
                                                            1.dp,
                                                            if (isSpeakerOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                                            else Color.White.copy(alpha = 0.15f),
                                                            CircleShape
                                                        )
                                                ) {
                                                    Icon(
                                                        imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                                        contentDescription = if (isSpeakerOn) "Switch to Earpiece" else "Switch to Speaker",
                                                        tint = if (isSpeakerOn) MaterialTheme.colorScheme.primary else Color.White,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                                Text(
                                                    text = if (isSpeakerOn) "Speaker" else "Earpiece",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (isSpeakerOn) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8)
                                                )
                                            }

                                            // 3. END CALL BUTTON
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                IconButton(
                                                    onClick = onHangup,
                                                    modifier = Modifier
                                                        .size(54.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626)))
                                                        )
                                                        .border(1.5.dp, Color(0xFFF87171).copy(alpha = 0.6f), CircleShape)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CallEnd,
                                                        contentDescription = "End Call",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(28.dp)
                                                    )
                                                }
                                                Text(
                                                    text = "End",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFFFCA5A5)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // VIDEO CALL OVERLAYS
                            // TOP OVERLAY: Peer info & Timer Duration
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = Color(0xFF0F172A).copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                    shadowElevation = 8.dp
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = callState.clientName.take(1).uppercase(),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = callState.clientName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                val minutes = callState.durationSeconds / 60
                                val seconds = callState.durationSeconds % 60
                                Surface(
                                    color = Color(0xFF0F172A).copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                    shadowElevation = 8.dp
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(Color(0xFFEF4444), CircleShape)
                                        )
                                        Text(
                                            text = String.format("%02d:%02d", minutes, seconds),
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }

                            // BOTTOM OVERLAY: Modern Frosted Call Controls Dock
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 16.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF0F172A).copy(alpha = 0.88f),
                                    shape = RoundedCornerShape(28.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                    shadowElevation = 16.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 1. MIC MUTE BUTTON
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            IconButton(
                                                onClick = { isMicEnabled = !isMicEnabled },
                                                modifier = Modifier
                                                    .size(50.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isMicEnabled) Color.White.copy(alpha = 0.12f)
                                                        else Color(0xFFEF4444).copy(alpha = 0.9f)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isMicEnabled) Color.White.copy(alpha = 0.15f) else Color(0xFFEF4444),
                                                        CircleShape
                                                    )
                                            ) {
                                                Icon(
                                                    imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                                    contentDescription = if (isMicEnabled) "Mute Microphone" else "Unmute Microphone",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                            Text(
                                                text = if (isMicEnabled) "Mute" else "Muted",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isMicEnabled) Color(0xFF94A3B8) else Color(0xFFFCA5A5)
                                            )
                                        }

                                        // 2. CAMERA TOGGLE BUTTON
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            IconButton(
                                                onClick = { isCamEnabled = !isCamEnabled },
                                                modifier = Modifier
                                                    .size(50.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isCamEnabled) Color.White.copy(alpha = 0.12f)
                                                        else Color(0xFFEF4444).copy(alpha = 0.9f)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isCamEnabled) Color.White.copy(alpha = 0.15f) else Color(0xFFEF4444),
                                                        CircleShape
                                                    )
                                            ) {
                                                Icon(
                                                    imageVector = if (isCamEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                                    contentDescription = if (isCamEnabled) "Disable Video" else "Enable Video",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                            Text(
                                                text = if (isCamEnabled) "Camera" else "Cam Off",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isCamEnabled) Color(0xFF94A3B8) else Color(0xFFFCA5A5)
                                            )
                                        }

                                        // 3. SPEAKERPHONE TOGGLE BUTTON
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    isSpeakerOn = !isSpeakerOn
                                                    try {
                                                        audioManager?.isSpeakerphoneOn = isSpeakerOn
                                                    } catch (e: Exception) {
                                                        Log.e("GatewayUi", "Error toggling speakerphone", e)
                                                    }
                                                },
                                                modifier = Modifier
                                                    .size(50.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSpeakerOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                                        else Color.White.copy(alpha = 0.12f)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isSpeakerOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                                        else Color.White.copy(alpha = 0.15f),
                                                        CircleShape
                                                    )
                                            ) {
                                                Icon(
                                                    imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                                    contentDescription = if (isSpeakerOn) "Switch to Earpiece" else "Switch to Speaker",
                                                    tint = if (isSpeakerOn) MaterialTheme.colorScheme.primary else Color.White,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                            Text(
                                                text = if (isSpeakerOn) "Speaker" else "Earpiece",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isSpeakerOn) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8)
                                            )
                                        }

                                        // 4. END CALL BUTTON
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            IconButton(
                                                onClick = onHangup,
                                                modifier = Modifier
                                                    .size(54.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626)))
                                                    )
                                                    .border(1.5.dp, Color(0xFFF87171).copy(alpha = 0.6f), CircleShape)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CallEnd,
                                                    contentDescription = "End Call",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                            Text(
                                                text = "End",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFFCA5A5)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {}
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebRtcCallContainer(
    callState: CallState,
    networkManager: GatewayNetworkManager,
    isMicEnabled: Boolean = true,
    isCamEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isWebViewReady by remember { mutableStateOf(false) }
    val pendingSignals = remember { mutableListOf<WebRtcSignal>() }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.evaluateJavascript("if (typeof closeCall === 'function') closeCall();", null)
                webViewRef?.stopLoading()
                webViewRef?.loadUrl("about:blank")
                webViewRef?.clearHistory()
                webViewRef?.removeAllViews()
                webViewRef?.destroy()
                webViewRef = null
                isWebViewReady = false
            } catch (e: Exception) {
                Log.e("GatewayUi", "Error cleaning up WebRtcCallContainer WebView", e)
            }
        }
    }

    LaunchedEffect(isMicEnabled, isWebViewReady) {
        if (isWebViewReady) {
            webViewRef?.evaluateJavascript("toggleMic($isMicEnabled);", null)
        }
    }

    LaunchedEffect(isCamEnabled, isWebViewReady) {
        if (isWebViewReady) {
            webViewRef?.evaluateJavascript("toggleCam($isCamEnabled);", null)
        }
    }

    fun processSignal(wv: WebView, signal: WebRtcSignal) {
        if ((signal.type == "webrtc_call_answer" || signal.type == "call_answered") && signal.sdp.isNotEmpty()) {
            wv.evaluateJavascript("setRemoteAnswer(${JSONObject.quote(signal.sdp)});", null)
        } else if ((signal.type == "webrtc_ice_candidate" || signal.type == "ice_candidate") && signal.candidateJson.isNotEmpty()) {
            wv.evaluateJavascript("addRemoteIceCandidate(${JSONObject.quote(signal.candidateJson)});", null)
        }
    }

    fun triggerInit(wv: WebView?) {
        if (wv == null || !isWebViewReady) return
        when (callState) {
            is CallState.Active -> {
                val offerSdp = callState.sdp
                val isVideo = callState.isVideo
                val js = "initWebRtc($isVideo, ${if (offerSdp.isNotEmpty()) JSONObject.quote(offerSdp) else "null"});"
                wv.evaluateJavascript(js, null)
            }
            is CallState.Outgoing -> {
                val js = "initWebRtc(true, null);"
                wv.evaluateJavascript(js, null)
            }
            else -> {}
        }
        val copy = ArrayList(pendingSignals)
        pendingSignals.clear()
        copy.forEach { signal ->
            processSignal(wv, signal)
        }
    }

    LaunchedEffect(callState) {
        networkManager.incomingSignal.collect { signal ->
            val active = callState as? CallState.Active
            val outgoing = callState as? CallState.Outgoing
            val incoming = callState as? CallState.Incoming
            val currentUuid = active?.clientUuid ?: outgoing?.clientUuid ?: incoming?.clientUuid
            val currentCallId = active?.callId ?: outgoing?.callId ?: incoming?.callId ?: ""
            val matchesCallId = signal.callId.isEmpty() || currentCallId.isEmpty() || signal.callId == currentCallId
            if (currentUuid != null && signal.clientUuid == currentUuid && matchesCallId) {
                val wv = webViewRef
                if (wv != null && isWebViewReady) {
                    processSignal(wv, signal)
                } else {
                    pendingSignals.add(signal)
                }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = true
                settings.allowContentAccess = true

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        (ctx as? android.app.Activity)?.runOnUiThread {
                            try {
                                val hasCam = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!hasCam || !hasMic) {
                                    (ctx as? android.app.Activity)?.requestPermissions(
                                        arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO),
                                        101
                                    )
                                }
                                request.grant(request.resources)
                            } catch (e: Exception) {
                                Log.e("GatewayUi", "Error granting WebChromeClient permissions", e)
                                try { request.grant(request.resources) } catch (e2: Exception) {}
                            }
                        }
                    }
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onWebViewReady() {
                        post {
                            isWebViewReady = true
                            triggerInit(this@apply)
                        }
                    }

                    @JavascriptInterface
                    fun onLocalSdpGenerated(sdp: String, isOffer: Boolean) {
                        val active = callState as? CallState.Active
                        val outgoing = callState as? CallState.Outgoing
                        val clientUuid = active?.clientUuid ?: outgoing?.clientUuid ?: ""
                        val callId = active?.callId ?: outgoing?.callId ?: ""
                        if (clientUuid.isNotEmpty()) {
                            if (isOffer) {
                                networkManager.publishOutgoingSdpOffer(clientUuid, sdp, callId)
                            } else {
                                networkManager.publishSdpAnswer(clientUuid, sdp, callId)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onLocalIceCandidateGenerated(candidateJson: String) {
                        val active = callState as? CallState.Active
                        val outgoing = callState as? CallState.Outgoing
                        val clientUuid = active?.clientUuid ?: outgoing?.clientUuid ?: ""
                        val callId = active?.callId ?: outgoing?.callId ?: ""
                        if (clientUuid.isNotEmpty()) {
                            networkManager.publishIceCandidate(clientUuid, candidateJson, callId)
                        }
                    }
                }, "AndroidWebRTCBridge")

                loadDataWithBaseURL("https://whisker.local", WEBRTC_HTML_CONTENT, "text/html", "UTF-8", null)
                webViewRef = this
            }
        },
        update = { webView ->
            webViewRef = webView
            if (isWebViewReady) {
                triggerInit(webView)
            }
        },
        modifier = modifier
    )
}

private const val WEBRTC_HTML_CONTENT = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  * { box-sizing: border-box; }
  body { margin: 0; padding: 0; background-color: #030712; color: white; font-family: system-ui, -apple-system, sans-serif; height: 100vh; width: 100vw; overflow: hidden; }
  .video-container { position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; background: #020617; }
  #remoteVideo { width: 100%; height: 100%; object-fit: cover; background: radial-gradient(circle at center, #0f172a 0%, #020617 100%); }
  #localVideo { position: absolute; bottom: 96px; right: 18px; width: 112px; height: 156px; object-fit: cover; border-radius: 16px; border: 2px solid #06b6d4; box-shadow: 0 10px 25px rgba(0,0,0,0.7); background: radial-gradient(circle at center, #1e293b 0%, #0f172a 100%); z-index: 10; }
  .hidden { display: none !important; }
</style>
</head>
<body>
<div class="video-container">
  <video id="remoteVideo" autoplay playsinline></video>
  <video id="localVideo" autoplay playsinline muted></video>
</div>

<script>
  let pc = null;
  let localStream = null;
  let initialized = false;
  let pendingRemoteCandidates = [];

  const rtcConfig = {
    iceServers: [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' }
    ]
  };

  window.onload = function() {
    if (window.AndroidWebRTCBridge && window.AndroidWebRTCBridge.onWebViewReady) {
      window.AndroidWebRTCBridge.onWebViewReady();
    }
  };

  function createHostSyntheticStream() {
    const canvas = document.createElement('canvas');
    canvas.width = 640;
    canvas.height = 480;
    const ctx = canvas.getContext('2d');
    let t = 0;
    setInterval(() => {
      t += 0.05;
      const w = canvas.width;
      const h = canvas.height;
      const grad = ctx.createLinearGradient(0, 0, w, h);
      grad.addColorStop(0, '#030712');
      grad.addColorStop(1, '#0f172a');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, w, h);

      for (let i = 1; i <= 3; i++) {
        const radius = (Math.sin(t + i * 1.5) * 0.2 + 0.8) * (55 + i * 30);
        ctx.beginPath();
        ctx.arc(w / 2, h / 2, radius, 0, Math.PI * 2);
        ctx.strokeStyle = 'rgba(16, 185, 129, ' + (0.35 / i) + ')';
        ctx.lineWidth = 2.5;
        ctx.stroke();
      }

      ctx.beginPath();
      ctx.arc(w / 2, h / 2, 30 + Math.sin(t * 2) * 5, 0, Math.PI * 2);
      ctx.fillStyle = '#10b981';
      ctx.shadowColor = '#34d399';
      ctx.shadowBlur = 15;
      ctx.fill();
      ctx.shadowBlur = 0;

      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 22px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('Phone Portal Stream', w / 2, h / 2 + 70);
    }, 50);

    const stream = canvas.captureStream(30);
    try {
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const osc = audioCtx.createOscillator();
      const dest = audioCtx.createMediaStreamDestination();
      osc.frequency.value = 520;
      osc.connect(dest);
      osc.start();
      const audioTrack = dest.stream.getAudioTracks()[0];
      if (audioTrack) stream.addTrack(audioTrack);
    } catch(e) {}
    return stream;
  }

  async function flushPendingCandidates() {
    if (!pc || !pc.remoteDescription) return;
    while (pendingRemoteCandidates.length > 0) {
      const cand = pendingRemoteCandidates.shift();
      try {
        await pc.addIceCandidate(new RTCIceCandidate(cand));
      } catch (e) {
        console.error("Error flushing queued candidate", e);
      }
    }
  }

  async function initWebRtc(isVideo, remoteOfferSdp) {
    if (initialized) return;
    initialized = true;

    const localVid = document.getElementById('localVideo');
    const remoteVid = document.getElementById('remoteVideo');

    if (!isVideo) {
      if (localVid) localVid.classList.add('hidden');
      if (remoteVid) remoteVid.classList.add('hidden');
    } else {
      if (localVid) localVid.classList.remove('hidden');
      if (remoteVid) remoteVid.classList.remove('hidden');
    }

    // Release any previous tracks cleanly
    if (localStream) {
      localStream.getTracks().forEach(t => {
        try { t.enabled = false; t.stop(); } catch(e) {}
      });
      localStream = null;
    }

    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      if (isVideo) {
        const videoConstraints = [
          { video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } }, audio: true },
          { video: { facingMode: 'user' }, audio: true },
          { video: true, audio: true }
        ];

        for (let i = 0; i < videoConstraints.length; i++) {
          try {
            localStream = await navigator.mediaDevices.getUserMedia(videoConstraints[i]);
            if (localStream && localStream.getVideoTracks().length > 0) {
              break;
            }
          } catch(err) {
            console.warn("Attempt " + i + " failed with constraint", videoConstraints[i], err);
            await new Promise(r => setTimeout(r, 250));
          }
        }

        if (!localStream || localStream.getVideoTracks().length === 0) {
          try {
            localStream = await navigator.mediaDevices.getUserMedia({ video: false, audio: true });
          } catch(e) {
            console.error("Audio fallback error", e);
          }
        }
      } else {
        try {
          localStream = await navigator.mediaDevices.getUserMedia({ video: false, audio: true });
        } catch(e) {
          console.error("Audio capture error", e);
        }
      }
    }

    if (!localStream || localStream.getTracks().length === 0) {
      localStream = createHostSyntheticStream();
    }

    if (localVid && isVideo) {
      localVid.srcObject = localStream;
      localVid.play().catch(e => console.warn("localVid play error", e));
    }

    pc = new RTCPeerConnection(rtcConfig);

    if (localStream) {
      localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
    }

    pc.ontrack = (event) => {
      const rVid = document.getElementById('remoteVideo');
      if (rVid && isVideo) {
        if (event.streams && event.streams[0]) {
          rVid.srcObject = event.streams[0];
        } else if (event.track) {
          if (!rVid.srcObject) {
            rVid.srcObject = new MediaStream();
          }
          rVid.srcObject.addTrack(event.track);
        }
      }
    };

    pc.onicecandidate = (event) => {
      if (event.candidate && window.AndroidWebRTCBridge) {
        window.AndroidWebRTCBridge.onLocalIceCandidateGenerated(JSON.stringify(event.candidate.toJSON()));
      }
    };

    if (remoteOfferSdp) {
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: remoteOfferSdp }));
      await flushPendingCandidates();
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      if (window.AndroidWebRTCBridge) {
        window.AndroidWebRTCBridge.onLocalSdpGenerated(answer.sdp, false);
      }
    } else {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      if (window.AndroidWebRTCBridge) {
        window.AndroidWebRTCBridge.onLocalSdpGenerated(offer.sdp, true);
      }
    }
  }

  function setRemoteAnswer(sdp) {
    if (pc && sdp) {
      pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: sdp }))
        .then(() => flushPendingCandidates())
        .catch(e => console.error("Error setting remote answer SDP", e));
    }
  }

  function addRemoteIceCandidate(candidateJsonStr) {
    if (!candidateJsonStr) return;
    try {
      const cand = typeof candidateJsonStr === 'string' ? JSON.parse(candidateJsonStr) : candidateJsonStr;
      if (pc && pc.remoteDescription) {
        pc.addIceCandidate(new RTCIceCandidate(cand))
          .catch(e => {
            console.warn("ICE candidate add retry queued", e);
            pendingRemoteCandidates.push(cand);
          });
      } else {
        pendingRemoteCandidates.push(cand);
      }
    } catch(e) {
      console.error("Candidate parse error", e);
    }
  }

  function toggleMic(enabled) {
    if (localStream) {
      localStream.getAudioTracks().forEach(t => t.enabled = enabled);
    }
  }

  function toggleCam(enabled) {
    if (localStream) {
      localStream.getVideoTracks().forEach(t => t.enabled = enabled);
    }
  }

  function closeCall() {
    pendingRemoteCandidates = [];
    initialized = false;
    if (localStream) {
      localStream.getTracks().forEach(t => {
        try { t.enabled = false; t.stop(); } catch(e) {}
      });
      localStream = null;
    }
    const localVid = document.getElementById('localVideo');
    if (localVid) {
      try { localVid.pause(); } catch(e) {}
      localVid.srcObject = null;
    }
    const remoteVid = document.getElementById('remoteVideo');
    if (remoteVid) {
      try { remoteVid.pause(); } catch(e) {}
      remoteVid.srcObject = null;
    }
    if (pc) {
      try { pc.close(); } catch(e) {}
      pc = null;
    }
  }

  window.addEventListener('beforeunload', closeCall);
  window.addEventListener('unload', closeCall);
  window.addEventListener('pagehide', closeCall);
</script>
</body>
</html>
"""

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
