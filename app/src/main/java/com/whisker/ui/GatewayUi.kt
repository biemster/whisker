package com.whisker.ui

import com.whisker.BuildConfig
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
import android.provider.OpenableColumns
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val hostUsernameVal by viewModel.networkManager.hostUsername.collectAsState()

    val selectedTab by viewModel.selectedTab.collectAsState()
    val homeServers by viewModel.allHomeServers.collectAsState()
    var showServersModal by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<HomeServer?>(null) }
    var showServerEditDialog by remember { mutableStateOf(false) }
    var showDozeInfoDialog by remember { mutableStateOf(false) }
    var showAddServerDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    val allInvites by viewModel.allInvites.collectAsState()
    val incomingSharedUris by viewModel.incomingSharedUris.collectAsState()

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
                        Text(
                            text = com.whisker.BuildConfig.VERSION_NAME,
                            fontSize = 10.sp,
                            color = Color.LightGray.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .background(Color(0xFF1F2937), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = Color.White
                ),
                actions = {
                    // Connection Status Bubble (Clickable -> opens modal with all configured home servers)
                    val enabledServers = homeServers.filter { it.isEnabled }
                    val reachableServers = enabledServers.filter { it.isReachable }
                    val (statusColor, statusText) = when {
                        homeServers.isEmpty() && serverUrlVal.isEmpty() -> Pair(Color(0xFFEF4444), "No Server")
                        enabledServers.isEmpty() -> Pair(Color(0xFF64748B), "Disabled")
                        reachableServers.size == enabledServers.size -> {
                            val txt = if (enabledServers.size > 1) "${reachableServers.size}/${enabledServers.size} Online" else "Connected"
                            Pair(Color(0xFF10B981), txt)
                        }
                        reachableServers.isNotEmpty() -> Pair(Color(0xFF10B981), "${reachableServers.size}/${enabledServers.size} Online")
                        connStatus.contains("Connecting", ignoreCase = true) -> Pair(Color(0xFFFBBF24), "Connecting")
                        else -> Pair(Color(0xFFEF4444), "Disconnected")
                    }

                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .clickable {
                                showServersModal = true
                            }
                            .background(Color(0xFF1F2937).copy(alpha = 0.6f), RoundedCornerShape(100.dp))
                            .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(100.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
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
                        homeServers = homeServers,
                        onAddServer = {
                            serverToEdit = null
                            showServerEditDialog = true
                        },
                        onEditServer = {
                            serverToEdit = it
                            showServerEditDialog = true
                        },
                        onDeleteServer = { viewModel.deleteHomeServer(it) },
                        onToggleServerEnabled = { viewModel.toggleServerEnabled(it) },
                        onSetPrimaryServer = { viewModel.setPrimaryServer(it) },
                        onRefreshServer = { viewModel.refreshServer(it) },
                        onRefreshAllServers = { viewModel.refreshAllServers() },
                        onToggleDirectCalls = { viewModel.toggleAllowDirectCalls(it) },
                        onToggleServerDirectCalls = { server, allowed -> viewModel.toggleServerDirectCalls(server, allowed) },
                        onToggleServerWidgetAccess = { server, allowed -> viewModel.toggleServerWidgetAccess(server, allowed) },
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
                            homeServers = homeServers,
                            onSendMessage = { uuid, name, text -> viewModel.sendMessage(uuid, name, text) },
                            onMarkAsRead = { viewModel.markMessagesRead(it) },
                            onActiveChatChanged = { viewModel.setSelectedChatUuid(it) },
                            onRedial = { uuid, name -> viewModel.redialClient(uuid, name) },
                            viewModel = viewModel
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

            val hostHomeServers = remember(homeServers) {
                val hosts = homeServers.filter { it.isHost && it.isEnabled }
                if (hosts.isNotEmpty()) hosts
                else {
                    val anyHost = homeServers.filter { it.isHost }
                    if (anyHost.isNotEmpty()) anyHost
                    else {
                        val primaries = homeServers.filter { it.isPrimary }
                        if (primaries.isNotEmpty()) primaries
                        else homeServers.filter { it.isEnabled }.ifEmpty { homeServers }
                    }
                }
            }
            val hostServerIds = remember(hostHomeServers) { hostHomeServers.map { it.id } }
            var selectedServerId by remember {
                mutableStateOf(
                    hostHomeServers.firstOrNull { it.isPrimary }?.id
                        ?: hostHomeServers.firstOrNull()?.id
                        ?: ""
                )
            }
            LaunchedEffect(hostServerIds) {
                if (selectedServerId.isEmpty() || !hostServerIds.contains(selectedServerId)) {
                    selectedServerId = hostHomeServers.firstOrNull { it.isPrimary }?.id
                        ?: hostHomeServers.firstOrNull()?.id
                        ?: ""
                }
            }

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

                                if (hostHomeServers.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0F172A))
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "TARGET HOME SERVER",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            letterSpacing = 0.8.sp
                                        )
                                        Text(
                                            text = "Select home server (Admin or Companion):",
                                            fontSize = 11.sp,
                                            color = Color.LightGray
                                        )

                                        hostHomeServers.forEach { srv ->
                                            val isSelected = (srv.id == selectedServerId)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                                    .clickable { selectedServerId = srv.id }
                                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { selectedServerId = srv.id },
                                                    colors = RadioButtonDefaults.colors(
                                                        selectedColor = MaterialTheme.colorScheme.primary,
                                                        unselectedColor = Color.Gray
                                                    ),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = srv.name.ifEmpty { "Home Server" },
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White
                                                        )
                                                        when (srv.serverRole) {
                                                            "ADMIN" -> {
                                                                Spacer(Modifier.width(6.dp))
                                                                Text(
                                                                    text = "👑 ADMIN",
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.ExtraBold,
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier
                                                                        .clip(RoundedCornerShape(3.dp))
                                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                            "COMPANION" -> {
                                                                Spacer(Modifier.width(6.dp))
                                                                Text(
                                                                    text = "📱 COMPANION",
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.ExtraBold,
                                                                    color = Color(0xFFA78BFA),
                                                                    modifier = Modifier
                                                                        .clip(RoundedCornerShape(3.dp))
                                                                        .background(Color(0xFFA78BFA).copy(alpha = 0.2f))
                                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = srv.url,
                                                        fontSize = 10.sp,
                                                        color = Color.Gray,
                                                        fontFamily = FontFamily.Monospace,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

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
                                val inviteUrl = createdInvite!!.url
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
                                        val targetServer = hostHomeServers.find { it.id == selectedServerId } ?: hostHomeServers.firstOrNull()
                                        createdInvite = viewModel.createInvite(inviteName, targetServer)
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

        // HOME SERVERS STATUS MODAL (TRIGGERED FROM CONNECTION BUBBLE)
        if (showServersModal) {
            HomeServersStatusModal(
                servers = homeServers,
                hostUsername = hostUsernameVal,
                onUpdateHostUsername = { viewModel.networkManager.updateHostUsername(it) },
                onDismiss = { showServersModal = false },
                onRefreshAll = { viewModel.refreshAllServers() },
                onRefreshServer = { viewModel.refreshServer(it) },
                onToggleEnabled = { viewModel.toggleServerEnabled(it) },
                onEditServer = { server ->
                    serverToEdit = server
                    showServerEditDialog = true
                },
                onAddServer = {
                    serverToEdit = null
                    showServerEditDialog = true
                }
            )
        }

        // PER-SERVER CONFIGURATION & ADD DIALOG
        if (showServerEditDialog) {
            HomeServerEditDialog(
                server = serverToEdit,
                defaultClientIdentity = hostUsernameVal.ifEmpty { Build.MODEL ?: "Mobile Device" },
                onDismiss = {
                    showServerEditDialog = false
                    serverToEdit = null
                },
                onSave = { name, url, clientIdentity, isEnabled ->
                    viewModel.saveHomeServer(
                        id = serverToEdit?.id ?: "",
                        name = name,
                        url = url,
                        clientIdentity = clientIdentity,
                        isEnabled = isEnabled,
                        isAdmin = serverToEdit?.isAdmin ?: true
                    )
                    showServerEditDialog = false
                    serverToEdit = null
                },
                onDelete = serverToEdit?.let { s ->
                    {
                        viewModel.deleteHomeServer(s)
                        showServerEditDialog = false
                        serverToEdit = null
                    }
                }
            )
        }

        if (incomingSharedUris.isNotEmpty()) {
            val approvedClients = requests.filter { it.status == "APPROVED" || it.status == "MOBILE_APPROVED" }
            AlertDialog(
                onDismissRequest = { viewModel.clearIncomingSharedUris() },
                containerColor = Color(0xFF0F172A),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Share via Whisker", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Select a recipient for ${incomingSharedUris.size} shared item(s):",
                            color = Color(0xFFCBD5E1),
                            fontSize = 13.sp
                        )
                        if (approvedClients.isEmpty()) {
                            Text("No paired connections found. Please authorize or pair a client first.", color = Color.Gray, fontSize = 12.sp)
                        } else {
                            approvedClients.forEach { client ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val urisToShare = incomingSharedUris.toList()
                                            viewModel.clearIncomingSharedUris()
                                            viewModel.setSelectedTab(1)
                                            viewModel.setSelectedChatUuid(client.uuid)
                                            Toast.makeText(context, "Sending ${urisToShare.size} item(s) to ${client.name}...", Toast.LENGTH_SHORT).show()
                                            coroutineScope.launch {
                                                urisToShare.forEach { uri ->
                                                    var fileName = "shared_file_${System.currentTimeMillis()}"
                                                    var fileSize = 0L
                                                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                                                    try {
                                                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                                            if (cursor.moveToFirst()) {
                                                                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                                                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                                                                if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                                                                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                                                            }
                                                        }
                                                    } catch (_: Exception) {}
                                                    viewModel.sendFile(client.uuid, client.name, uri, fileName, mimeType, fileSize)
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            if (client.clientType == "MOBILE") Icons.Default.PhoneAndroid else Icons.Default.Laptop,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column {
                                            Text(client.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                            Text(client.uuid.take(12) + "...", color = Color.Gray, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.clearIncomingSharedUris() }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
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
    onToggleAutoAccept: (ClientRequest, Boolean) -> Unit,
    homeServers: List<HomeServer> = emptyList()
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val isMobileApp = request.status == "MOBILE_APPROVED" || request.clientType.equals("MOBILE", ignoreCase = true)
    val isCli = request.clientType.equals("CLI", ignoreCase = true) || request.name.contains("cli", ignoreCase = true) || request.message.contains("CLI", ignoreCase = true)
    val isEmbed = request.clientType.equals("EMBED", ignoreCase = true) || request.clientType.equals("WIDGET", ignoreCase = true) || request.message.contains("embed", ignoreCase = true)
    val rawAddress = request.serverAddress.trimEnd('/')
    val matchedServer = homeServers.firstOrNull { it.url.trimEnd('/') == rawAddress }
        ?: if (rawAddress == "local" || rawAddress.isEmpty()) {
            homeServers.firstOrNull { it.isEnabled } ?: homeServers.firstOrNull()
        } else {
            null
        }
    val serverName = matchedServer?.name?.ifEmpty { "Home Server" } ?: "Remote Server"
    val serverAddress = matchedServer?.url ?: (if (rawAddress.isNotEmpty() && rawAddress != "local") rawAddress else "server")
    val isSharedServer = (matchedServer != null)

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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Client type badge
                    Surface(
                        color = if (isMobileApp) Color(0xFF8B5CF6).copy(alpha = 0.25f)
                                else if (isCli) Color(0xFF10B981).copy(alpha = 0.25f)
                                else if (isEmbed) Color(0xFF06B6D4).copy(alpha = 0.25f)
                                else Color(0xFF3B82F6).copy(alpha = 0.25f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isMobileApp) "📱 Mobile"
                                   else if (isCli) "⌨️ CLI"
                                   else if (isEmbed) "🌐 Embed"
                                   else "💻 Web",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMobileApp) Color(0xFFA78BFA)
                                    else if (isCli) Color(0xFF6EE7B7)
                                    else if (isEmbed) Color(0xFF67E8F9)
                                    else Color(0xFF93C5FD),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    // Server attribution for all approved contacts
                    Surface(
                        color = Color(0xFF1E293B).copy(alpha = 0.7f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, Color.Gray.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "🏠 $serverName",
                            fontSize = 9.sp,
                            color = Color(0xFFCBD5E1),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                .widthIn(max = 110.dp)
                        )
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
                            // Home Server Details for all approved contacts
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
                                    text = "$serverName ($serverAddress)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF93C5FD),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Connection Type
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CONNECTION:",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = if (isMobileApp) "Mobile App Connection" else "Direct Host Connection",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE2E8F0)
                                )
                            }

                            // Client Type
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CLIENT TYPE:",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray
                                )
                                Text(
                                    text = if (isMobileApp) "Mobile App Peer" else if (isCli) "CLI Client" else "Web Client",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isMobileApp) Color(0xFFA78BFA) else if (isCli) Color(0xFF6EE7B7) else Color(0xFF93C5FD)
                                )
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
// COMPACT HOME SERVER CARD (Single line with expandable details)
// ==========================================
@Composable
fun CompactHomeServerCard(
    server: HomeServer,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSettingsClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    onToggleDirectCalls: (Boolean) -> Unit,
    onToggleWidgetAccess: (Boolean) -> Unit = {},
    onSetPrimary: () -> Unit,
    onDelete: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val ledColor = when {
        !server.isEnabled -> Color(0xFF64748B)
        server.isReachable -> Color(0xFF10B981)
        server.statusMessage.contains("Connecting", ignoreCase = true) -> Color(0xFFFBBF24)
        else -> Color(0xFFEF4444)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (!server.isEnabled) Color(0xFF111827).copy(alpha = 0.5f) else Color(0xFF1A1F2C).copy(alpha = 0.85f)
        ),
        border = BorderStroke(
            1.dp,
            if (server.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Gray.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Main Compact Line: LED + Server Name + Badges + Enable Toggle + Expand Chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: LED & Name with Badges - clicking expands/collapses
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onToggleExpand() }
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(ledColor)
                    )
                    Text(
                        text = server.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    when (server.serverRole) {
                        "ADMIN" -> {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "👑 ADMIN",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        "COMPANION" -> {
                            Surface(
                                color = Color(0xFFA78BFA).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "📱 COMPANION",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFA78BFA),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        else -> { // MEMBER
                            Surface(
                                color = Color(0xFF10B981).copy(alpha = 0.18f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "🔗 MEMBER",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6EE7B7),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    if (!server.isEnabled) {
                        Surface(
                            color = Color(0xFF64748B).copy(alpha = 0.25f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "DISABLED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Right: Enable Toggle + Expand Chevron
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Disable/Enable Toggle directly on collapsed card
                    Switch(
                        checked = server.isEnabled,
                        onCheckedChange = { onToggleEnabled() },
                        modifier = Modifier
                            .scale(0.8f)
                            .height(28.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color(0xFF374151)
                        )
                    )

                    // Expand Chevron
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                    // Server Address & Status Box
                    Surface(
                        color = Color.Black.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SERVER ADDRESS",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = server.url,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(server.url))
                                        Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Copy URL",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "STATUS: ${server.statusMessage.ifEmpty { if (server.isReachable) "Online • Synced" else "Disconnected" }}",
                                    fontSize = 10.sp,
                                    color = if (server.isReachable) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                                if (server.lastSeen > 0) {
                                    Text(
                                        text = SimpleDateFormat("HH:mm • dd MMM", Locale.getDefault()).format(Date(server.lastSeen)),
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                            }

                            // Mobile Client Identity
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "MOBILE CLIENT IDENTITY",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = server.clientIdentity.ifEmpty { "Mobile Device" },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }

                            // Pairing ID (Registered UUID)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PAIRING ID",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = server.registeredUuid.ifEmpty { "Pending registration..." },
                                    fontSize = 10.sp,
                                    color = if (server.registeredUuid.isNotEmpty()) Color(0xFFA78BFA) else Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Per-Server Allow Direct Calls Toggle
                    Surface(
                        color = Color.Black.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (server.allowDirectCalls) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Gray.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("⚡", fontSize = 16.sp)
                                Column {
                                    Text(
                                        text = "Allow Direct Calls",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Allow visitors to call immediately before pre-approval",
                                        fontSize = 9.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                            Switch(
                                checked = server.allowDirectCalls,
                                onCheckedChange = { onToggleDirectCalls(it) },
                                modifier = Modifier
                                    .scale(0.8f)
                                    .testTag("direct_calls_toggle_${server.id.take(6)}"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = Color.LightGray,
                                    uncheckedTrackColor = Color(0xFF374151)
                                )
                            )
                        }
                    }

                    // Per-Server Allow Widget Access Toggle
                    Surface(
                        color = Color.Black.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (server.allowWidgetAccess) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Gray.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🌐", fontSize = 16.sp)
                                Column {
                                    Text(
                                        text = "Allow Widget Access",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Allow messages and calls from the embeddable web widget",
                                        fontSize = 9.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                            Switch(
                                checked = server.allowWidgetAccess,
                                onCheckedChange = { onToggleWidgetAccess(it) },
                                modifier = Modifier
                                    .scale(0.8f)
                                    .testTag("widget_access_toggle_${server.id.take(6)}"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = Color.LightGray,
                                    uncheckedTrackColor = Color(0xFF374151)
                                )
                            )
                        }
                    }

                    // Action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSettingsClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Configure", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete", color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// HOME SERVERS STATUS MODAL (Opened from Connection Bubble)
// ==========================================
@Composable
fun HomeServersStatusModal(
    servers: List<HomeServer>,
    hostUsername: String,
    onUpdateHostUsername: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefreshAll: () -> Unit,
    onRefreshServer: (HomeServer) -> Unit,
    onEditServer: (HomeServer) -> Unit,
    onToggleEnabled: (HomeServer) -> Unit = {},
    onAddServer: () -> Unit
) {
    val validServers = servers.filter {
        val u = it.url.trim().lowercase()
        u.isNotBlank() && (u.startsWith("http://") || u.startsWith("https://")) && !u.contains("10.0.2.2") && !u.contains("localhost") && u != "http://" && u != "https://"
    }
    val scrollState = rememberScrollState()
    var editingUsername by remember { mutableStateOf(false) }
    var usernameText by remember(hostUsername) { mutableStateOf(hostUsername) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = "Home Servers",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Home Servers Status",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                IconButton(
                    onClick = onRefreshAll,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh All",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Host Username banner
                Surface(
                    color = Color.Black.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("MOBILE CLIENT IDENTITY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            if (editingUsername) {
                                OutlinedTextField(
                                    value = usernameText,
                                    onValueChange = { usernameText = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = Color.Gray
                                    )
                                )
                            } else {
                                Text(hostUsername.ifEmpty { "Host" }, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        IconButton(
                            onClick = {
                                if (editingUsername) {
                                    if (usernameText.isNotBlank()) {
                                        onUpdateHostUsername(usernameText.trim())
                                    }
                                    editingUsername = false
                                } else {
                                    editingUsername = true
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (editingUsername) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (editingUsername) "Save" else "Edit Name",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (validServers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "No Home Servers Configured",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Add your Whisker home server to sync events, messages, and calls.",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    validServers.forEach { server ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (!server.isEnabled) Color(0xFF1E293B).copy(alpha = 0.4f) else Color(0xFF1E293B).copy(alpha = 0.85f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (server.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Gray.copy(alpha = 0.25f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        val ledColor = when {
                                            !server.isEnabled -> Color(0xFF64748B)
                                            server.isReachable -> Color(0xFF10B981)
                                            server.statusMessage.contains("Connecting", ignoreCase = true) -> Color(0xFFFBBF24)
                                            else -> Color(0xFFEF4444)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(ledColor)
                                        )
                                        Text(
                                            text = server.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        when (server.serverRole) {
                                            "ADMIN" -> {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "👑 ADMIN",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            "COMPANION" -> {
                                                Surface(
                                                    color = Color(0xFFA78BFA).copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "📱 COMPANION",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFA78BFA),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            else -> {
                                                Surface(
                                                    color = Color(0xFF10B981).copy(alpha = 0.18f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "🔗 MEMBER",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF6EE7B7),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Switch(
                                            checked = server.isEnabled,
                                            onCheckedChange = { onToggleEnabled(server) },
                                            modifier = Modifier.scale(0.75f).height(24.dp),
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.Black,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                                uncheckedThumbColor = Color.LightGray,
                                                uncheckedTrackColor = Color(0xFF374151)
                                            )
                                        )
                                        IconButton(
                                            onClick = { onEditServer(server) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = "Settings",
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = server.url,
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (server.clientIdentity.isNotEmpty()) {
                                    Text(
                                        text = "Identity: ${server.clientIdentity}",
                                        fontSize = 10.sp,
                                        color = Color.LightGray
                                    )
                                }

                                if (server.registeredUuid.isNotEmpty()) {
                                    Text(
                                        text = "Pairing ID: ${server.registeredUuid}",
                                        fontSize = 9.sp,
                                        color = Color(0xFFA78BFA),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                val statusLabel = when {
                                    !server.isEnabled -> "Disabled"
                                    server.isReachable -> "Online • Synced"
                                    server.statusMessage.isNotEmpty() -> server.statusMessage
                                    else -> "Disconnected"
                                }
                                Text(
                                    text = statusLabel,
                                    fontSize = 10.sp,
                                    color = if (server.isReachable && server.isEnabled) Color(0xFF10B981) else Color.LightGray
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "App Version: ${com.whisker.BuildConfig.VERSION_NAME}",
                    fontSize = 10.sp,
                    color = Color.Gray.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAddServer,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Server", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Gray, fontSize = 12.sp)
            }
        },
        containerColor = Color(0xFF151926),
        tonalElevation = 6.dp
    )
}

// ==========================================
// HOME SERVER EDIT / ADD DIALOG
// ==========================================
@Composable
fun HomeServerEditDialog(
    server: HomeServer?,
    defaultClientIdentity: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, clientIdentity: String, isEnabled: Boolean) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var resolvedServerName by remember(server) { mutableStateOf(server?.name ?: "") }
    var inputUrl by remember(server) { mutableStateOf(server?.url ?: "") }
    var clientIdentity by remember(server) {
        mutableStateOf(
            server?.clientIdentity?.ifBlank { defaultClientIdentity }
                ?: defaultClientIdentity.ifBlank { Build.MODEL ?: "Mobile Device" }
        )
    }
    var isEnabled by remember(server) { mutableStateOf(server?.isEnabled ?: true) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Auto-resolve server name from public endpoint when URL changes
    LaunchedEffect(inputUrl) {
        val trimmed = inputUrl.trim().removeSuffix("/")
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val conn = java.net.URL("$trimmed/api/public").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(text)
                        val name = json.optString("server_name", json.optString("servername", ""))
                        if (name.isNotBlank()) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                resolvedServerName = name
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (server == null) Icons.Default.Add else Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = if (server == null) "Add Home Server" else "Configure ${server.name}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Server name provided by home server (read-only info card)
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SERVER NAME (FROM HOME SERVER)",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (resolvedServerName.isNotBlank()) resolvedServerName else (if (inputUrl.isNotBlank()) "Connecting..." else "Discovered automatically from server"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (resolvedServerName.isNotBlank()) Color.White else Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    label = { Text("Server URL (e.g. http://192.168.1.50:8000)", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = clientIdentity,
                    onValueChange = { clientIdentity = it },
                    label = { Text("Mobile Client Identity", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) },
                    placeholder = { Text(defaultClientIdentity.ifBlank { "Mobile Device" }, fontSize = 12.sp, color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth().testTag("client_identity_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                // Test Connection Button querying public unauthenticated endpoint
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (inputUrl.isNotBlank()) {
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    isTestingConnection = true
                                    testResult = null
                                    try {
                                        val base = inputUrl.trim().removeSuffix("/")
                                        val urlObj = java.net.URL("$base/api/public")
                                        val conn = urlObj.openConnection() as java.net.HttpURLConnection
                                        conn.connectTimeout = 4000
                                        conn.readTimeout = 4000
                                        val code = conn.responseCode
                                        if (code == 200) {
                                            val respText = conn.inputStream.bufferedReader().use { it.readText() }
                                            val json = org.json.JSONObject(respText)
                                            val sName = json.optString("server_name", json.optString("servername", ""))
                                            if (sName.isNotBlank()) {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    resolvedServerName = sName
                                                }
                                            }
                                            val shortName = if (sName.isNotBlank()) sName.take(12) else "OK"
                                            testResult = "Connected ($shortName)"
                                        } else {
                                            testResult = "HTTP $code"
                                        }
                                        conn.disconnect()
                                    } catch (e: Exception) {
                                        testResult = when {
                                            e is java.net.ConnectException -> "Refused"
                                            e is java.net.SocketTimeoutException -> "Timeout"
                                            e is java.net.UnknownHostException -> "No host"
                                            else -> "Error"
                                        }
                                    } finally {
                                        isTestingConnection = false
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isTestingConnection && inputUrl.isNotBlank()
                    ) {
                        Text(
                            text = if (isTestingConnection) "Testing..." else "Test Connection",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    testResult?.let { res ->
                        Text(
                            text = res,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                            color = if (res.startsWith("Connected")) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                    }
                }



                if (onDelete != null) {
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete This Server", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (inputUrl.isNotBlank()) {
                        val finalName = if (resolvedServerName.isNotBlank()) resolvedServerName else (server?.name?.ifBlank { "Home Server" } ?: "Home Server")
                        onSave(
                            finalName,
                            inputUrl.trim(),
                            clientIdentity.trim().ifBlank { defaultClientIdentity.ifBlank { "Mobile Device" } },
                            isEnabled
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save Server", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray, fontSize = 12.sp)
            }
        },
        containerColor = Color(0xFF151926),
        tonalElevation = 6.dp
    )
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
    homeServers: List<HomeServer> = emptyList(),
    onAddServer: () -> Unit = {},
    onEditServer: (HomeServer) -> Unit = {},
    onDeleteServer: (HomeServer) -> Unit = {},
    onToggleServerEnabled: (HomeServer) -> Unit = {},
    onSetPrimaryServer: (HomeServer) -> Unit = {},
    onRefreshServer: (HomeServer) -> Unit = {},
    onRefreshAllServers: () -> Unit = {},
    onToggleDirectCalls: (Boolean) -> Unit = {},
    onToggleServerDirectCalls: (HomeServer, Boolean) -> Unit = { _, _ -> },
    onToggleServerWidgetAccess: (HomeServer, Boolean) -> Unit = { _, _ -> },
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
    val expandedServerIds = remember { mutableStateMapOf<String, Boolean>() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val mobilePending = pendingRequests.filter { it.status == "MOBILE_PENDING" }
    val visitorPending = pendingRequests.filter { it.status == "PENDING" }
    val validHomeServers = homeServers.filter {
        val u = it.url.trim().lowercase()
        u.isNotBlank() && (u.startsWith("http://") || u.startsWith("https://")) && !u.contains("10.0.2.2") && !u.contains("localhost") && u != "http://" && u != "https://"
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        // Configured Home Servers Section
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🌐", fontSize = 16.sp)
                    Text(
                        text = "CONFIGURED HOME SERVERS (${validHomeServers.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }

                IconButton(
                    onClick = onAddServer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Home Server",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (validHomeServers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onAddServer() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "+ Tap to Configure Home Server",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Connect Whisker to your local LAN or remote home server to start receiving calls and messages.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(validHomeServers, key = { it.id }) { server ->
                val isExpanded = expandedServerIds[server.id] ?: false
                CompactHomeServerCard(
                    server = server,
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        expandedServerIds[server.id] = !isExpanded
                    },
                    onSettingsClick = { onEditServer(server) },
                    onToggleEnabled = { onToggleServerEnabled(server) },
                    onToggleDirectCalls = { allowed -> onToggleServerDirectCalls(server, allowed) },
                    onToggleWidgetAccess = { allowed -> onToggleServerWidgetAccess(server, allowed) },
                    onSetPrimary = { onSetPrimaryServer(server) },
                    onDelete = { onDeleteServer(server) }
                )
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
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val isEmbedReq = request.clientType.equals("EMBED", ignoreCase = true) || request.message.contains("embed", ignoreCase = true) || request.message.contains("widget", ignoreCase = true)
                                val isCliReq = request.clientType.equals("CLI", ignoreCase = true)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = request.name,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Surface(
                                        color = if (isEmbedReq) Color(0xFF06B6D4).copy(alpha = 0.25f)
                                                else if (isCliReq) Color(0xFF10B981).copy(alpha = 0.25f)
                                                else Color(0xFF3B82F6).copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, if (isEmbedReq) Color(0xFF06B6D4).copy(alpha = 0.6f) else if (isCliReq) Color(0xFF10B981).copy(alpha = 0.6f) else Color(0xFF3B82F6).copy(alpha = 0.6f))
                                    ) {
                                        Text(
                                            text = if (isEmbedReq) "🌐 EMBED WIDGET" else if (isCliReq) "⌨️ CLI CLIENT" else "💻 WEB VISITOR",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isEmbedReq) Color(0xFF67E8F9) else if (isCliReq) Color(0xFF6EE7B7) else Color(0xFF93C5FD),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "CLIENT ID: ${request.uuid}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isEmbedReq) Color(0xFF06B6D4) else MaterialTheme.colorScheme.primary
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
                    onToggleAutoAccept = onToggleAutoAccept,
                    homeServers = homeServers
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

            item {
                Spacer(modifier = Modifier.height(24.dp))
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
data class PendingSendFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val isImage: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTab(
    selectedClientUuid: String?,
    approvedRequests: List<ClientRequest>,
    messages: List<Message>,
    isTablet: Boolean = false,
    homeServers: List<HomeServer> = emptyList(),
    onSendMessage: (String, String, String) -> Unit,
    onMarkAsRead: (String) -> Unit,
    onActiveChatChanged: (String?) -> Unit,
    onRedial: (String, String) -> Unit,
    viewModel: GatewayViewModel? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var replyText by remember { mutableStateOf("") }
    var showFilePickerModal by remember { mutableStateOf(false) }
    var pendingFileToSend by remember { mutableStateOf<PendingSendFile?>(null) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    var downloadingMsgId by remember { mutableStateOf<String?>(null) }

    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var tempCameraFile by remember { mutableStateOf<File?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraFile != null && tempCameraUri != null) {
            val file = tempCameraFile!!
            val uri = tempCameraUri!!
            pendingFileToSend = PendingSendFile(
                uri = uri,
                name = file.name,
                size = file.length(),
                mimeType = "image/jpeg",
                isImage = true
            )
            showFilePickerModal = false
        }
    }

    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            var fileName = "photo_${System.currentTimeMillis()}.jpg"
            var fileSize = 0L
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                    }
                }
            } catch (_: Exception) {}
            pendingFileToSend = PendingSendFile(
                uri = uri,
                name = fileName,
                size = fileSize,
                mimeType = mimeType,
                isImage = mimeType.startsWith("image/")
            )
            showFilePickerModal = false
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            var fileName = "file_${System.currentTimeMillis()}"
            var fileSize = 0L
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                    }
                }
            } catch (_: Exception) {}
            val isImg = mimeType.startsWith("image/") || fileName.lowercase().let { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".jpeg") || it.endsWith(".webp") || it.endsWith(".gif") }
            pendingFileToSend = PendingSendFile(
                uri = uri,
                name = fileName,
                size = fileSize,
                mimeType = mimeType,
                isImage = isImg
            )
            showFilePickerModal = false
        }
    }

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
                    val isCli = client.clientType.equals("CLI", ignoreCase = true) || client.name.contains("cli", ignoreCase = true) || client.message.contains("CLI", ignoreCase = true)
                    val isEmbed = client.clientType.equals("EMBED", ignoreCase = true) || client.clientType.equals("WIDGET", ignoreCase = true) || client.message.contains("embed", ignoreCase = true)
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
                                    text = if (isMobile) "📱 ${client.name}" else if (isCli) "⌨️ ${client.name}" else if (isEmbed) "🌐 ${client.name}" else client.name,
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
                val isCli = client.clientType.equals("CLI", ignoreCase = true) || client.name.contains("cli", ignoreCase = true) || client.message.contains("CLI", ignoreCase = true)
                val isEmbed = client.clientType.equals("EMBED", ignoreCase = true) || client.clientType.equals("WIDGET", ignoreCase = true) || client.message.contains("embed", ignoreCase = true)
                val rawAddress = client.serverAddress.trimEnd('/')
                val matchedServer = homeServers.firstOrNull { it.url.trimEnd('/') == rawAddress }
                    ?: if (rawAddress == "local" || rawAddress.isEmpty()) {
                        homeServers.firstOrNull { it.isEnabled } ?: homeServers.firstOrNull()
                    } else {
                        null
                    }
                val sName = matchedServer?.name?.ifEmpty { "Home Server" } ?: "Remote Server"
                val sAddress = matchedServer?.url ?: (if (rawAddress.isNotEmpty() && rawAddress != "local") rawAddress else "server")
                val isSharedServer = (matchedServer != null)

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
                                color = if (isMobile) Color(0xFF8B5CF6).copy(alpha = 0.25f)
                                        else if (isCli) Color(0xFF10B981).copy(alpha = 0.25f)
                                        else if (isEmbed) Color(0xFF06B6D4).copy(alpha = 0.25f)
                                        else Color(0xFF3B82F6).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isMobile) "📱 Mobile App"
                                           else if (isCli) "⌨️ CLI Client"
                                           else if (isEmbed) "🌐 Embed Widget"
                                           else "💻 Web Client",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isMobile) Color(0xFFA78BFA)
                                            else if (isCli) Color(0xFF6EE7B7)
                                            else if (isEmbed) Color(0xFF67E8F9)
                                            else Color(0xFF93C5FD),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }

                            Surface(
                                color = Color(0xFF1E293B).copy(alpha = 0.7f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.5.dp, Color.Gray.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "🏠 $sName",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFCBD5E1),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
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
                                            val pathStr = msg.filePath
                                            val isImg = remember(pathStr, msg.messageText) {
                                                val lower = pathStr.lowercase()
                                                val txt = msg.messageText.lowercase()
                                                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") ||
                                                txt.endsWith(".png") || txt.endsWith(".jpg") || txt.endsWith(".jpeg") || txt.endsWith(".webp") || txt.endsWith(".gif") ||
                                                lower.startsWith("data:image/") || (lower.contains("/api/host") && (lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".webp")))
                                            }

                                            if (isImg) {
                                                val imageUrl = remember(pathStr) {
                                                    if (pathStr.startsWith("http://") || pathStr.startsWith("https://") || pathStr.startsWith("data:") || pathStr.startsWith("content://") || pathStr.startsWith("file://")) {
                                                        pathStr
                                                    } else if (pathStr.startsWith("/") || File(pathStr).exists()) {
                                                        "file://$pathStr"
                                                    } else {
                                                        pathStr
                                                    }
                                                }

                                                Card(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .clickable { fullScreenImageUrl = imageUrl },
                                                    colors = CardDefaults.cardColors(containerColor = if (msg.isIncoming) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                                    border = BorderStroke(1.dp, if (msg.isIncoming) Color.Gray.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                                ) {
                                                    Column {
                                                        AsyncImage(
                                                            model = imageUrl,
                                                            contentDescription = msg.messageText,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .heightIn(max = 240.dp)
                                                                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                        )
                                                        if (msg.messageText.isNotBlank() && msg.messageText != msg.filePath && !msg.messageText.startsWith("data:") && !msg.messageText.startsWith("http")) {
                                                            Text(
                                                                text = msg.messageText,
                                                                fontSize = 12.sp,
                                                                color = Color.White,
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                val fileName = remember(msg.filePath, msg.messageText) {
                                                    if (msg.messageText.isNotBlank() && !msg.messageText.startsWith("data:") && !msg.messageText.startsWith("http") && !msg.messageText.startsWith("/")) {
                                                        msg.messageText
                                                    } else {
                                                        pathStr.substringAfterLast('/').substringBefore('?')
                                                    }
                                                }
                                                val ext = fileName.substringAfterLast('.', "").lowercase()
                                                val fileIcon = when (ext) {
                                                    "pdf" -> "📄"
                                                    "zip", "tar", "gz", "7z", "rar" -> "📦"
                                                    "mp3", "wav", "m4a", "ogg" -> "🎵"
                                                    "mp4", "mkv", "avi", "mov" -> "🎥"
                                                    "txt", "md", "json", "py", "kt", "csv" -> "📝"
                                                    else -> "📎"
                                                }

                                                Card(
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (msg.isIncoming) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                    ),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (msg.isIncoming) Color.Gray.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                                    ),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                    ) {
                                                        Surface(
                                                            shape = CircleShape,
                                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                            modifier = Modifier.size(42.dp)
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Text(fileIcon, fontSize = 20.sp)
                                                            }
                                                        }
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = fileName,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.White,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = "Downloads folder",
                                                                fontSize = 10.sp,
                                                                color = Color(0xFF94A3B8)
                                                            )
                                                        }
                                                        val isDownloading = (downloadingMsgId == msg.serverId || (downloadingMsgId != null && downloadingMsgId == msg.filePath))
                                                        if (isDownloading) {
                                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                                        } else {
                                                            IconButton(
                                                                onClick = {
                                                                    if (viewModel != null) {
                                                                        downloadingMsgId = msg.serverId.ifEmpty { msg.filePath }
                                                                        viewModel.downloadFile(msg.filePath, fileName, context) { savedFile ->
                                                                            downloadingMsgId = null
                                                                            if (savedFile != null) {
                                                                                Toast.makeText(context, "Saved to ${savedFile.name} in Downloads", Toast.LENGTH_SHORT).show()
                                                                                viewModel.openFile(context, savedFile)
                                                                            } else {
                                                                                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                                                            }
                                                                        }
                                                                    }
                                                                },
                                                                modifier = Modifier
                                                                    .size(36.dp)
                                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Download,
                                                                    contentDescription = "Save to Downloads",
                                                                    tint = MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
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
                        // Quick file & media attach button
                        IconButton(
                            onClick = { showFilePickerModal = true },
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Attach File & Media", tint = MaterialTheme.colorScheme.primary)
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

                // Full Screen File & Media Selector Modal
                if (showFilePickerModal) {
                    ModalBottomSheet(
                        onDismissRequest = { showFilePickerModal = false },
                        containerColor = Color(0xFF0F172A),
                        contentColor = Color.White,
                        modifier = Modifier.fillMaxHeight(0.92f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "Share Files & Media",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        "Send to ${client.name}",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                                IconButton(onClick = { showFilePickerModal = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Action Cards (Camera, Gallery, Storage)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Camera
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val photoDir = File(context.cacheDir, "camera_photos").apply { mkdirs() }
                                            val newPhoto = File(photoDir, "IMG_${System.currentTimeMillis()}.jpg")
                                            tempCameraFile = newPhoto
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", newPhoto)
                                            tempCameraUri = uri
                                            takePictureLauncher.launch(uri)
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFFEF4444).copy(alpha = 0.2f),
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFFF87171))
                                            }
                                        }
                                        Text("Camera", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // Gallery
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            pickVisualMediaLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                            )
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF3B82F6).copy(alpha = 0.2f),
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFF60A5FA))
                                            }
                                        }
                                        Text("Gallery", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // Storage / Documents
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            openDocumentLauncher.launch(arrayOf("*/*"))
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF10B981).copy(alpha = 0.2f),
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF34D399))
                                            }
                                        }
                                        Text("Storage", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // Recent Files on Device (Downloads & Local)
                            Text(
                                "Local Storage & Downloads",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFCBD5E1)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val localFiles = remember {
                                val list = mutableListOf<File>()
                                try {
                                    val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                    if (dlDir != null && dlDir.exists() && dlDir.isDirectory) {
                                        dlDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedByDescending { it.lastModified() }?.take(15)?.let { list.addAll(it) }
                                    }
                                } catch (_: Exception) {}
                                try {
                                    val extDir = context.getExternalFilesDir(null)
                                    if (extDir != null && extDir.exists()) {
                                        extDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.take(10).forEach { if (!list.contains(it)) list.add(it) }
                                    }
                                } catch (_: Exception) {}
                                list
                            }

                            if (localFiles.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("📁", fontSize = 32.sp)
                                        Text("No recent files found in Downloads", fontSize = 12.sp, color = Color.Gray)
                                        Text("Use Storage or Gallery above to pick any file", fontSize = 11.sp, color = Color.DarkGray)
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(localFiles) { file ->
                                        val fExt = file.extension.lowercase()
                                        val isImage = fExt in listOf("jpg", "jpeg", "png", "webp", "gif")
                                        val fileIcon = when (fExt) {
                                            "pdf" -> "📄"
                                            "zip", "tar", "gz" -> "📦"
                                            "mp3", "wav", "m4a" -> "🎵"
                                            "mp4", "mkv", "avi" -> "🎥"
                                            in listOf("jpg", "jpeg", "png", "webp", "gif") -> "🖼️"
                                            else -> "📎"
                                        }
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fExt) ?: "*/*"
                                                    pendingFileToSend = PendingSendFile(
                                                        uri = uri,
                                                        name = file.name,
                                                        size = file.length(),
                                                        mimeType = mimeType,
                                                        isImage = isImage
                                                    )
                                                    showFilePickerModal = false
                                                },
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(fileIcon, fontSize = 20.sp)
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    val sizeKb = (file.length() / 1024).coerceAtLeast(1)
                                                    val sizeStr = if (sizeKb > 1024) "${sizeKb / 1024} MB" else "$sizeKb KB"
                                                    Text("$sizeStr • Downloads", fontSize = 10.sp, color = Color.Gray)
                                                }
                                                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Confirmation Preview Dialog before transmitting photo/file
                pendingFileToSend?.let { pending ->
                    var captionText by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { pendingFileToSend = null },
                        containerColor = Color(0xFF0F172A),
                        title = {
                            Text(if (pending.isImage) "Send Photo" else "Send File", color = Color.White, fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (pending.isImage) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.Black),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = pending.uri,
                                            contentDescription = pending.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                        )
                                    }
                                } else {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("📎", fontSize = 28.sp)
                                            Column {
                                                Text(pending.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                val sizeKb = (pending.size / 1024).coerceAtLeast(1)
                                                val sizeStr = if (sizeKb > 1024) "${sizeKb / 1024} MB" else "$sizeKb KB"
                                                Text(sizeStr, color = Color.Gray, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = captionText,
                                    onValueChange = { captionText = it },
                                    placeholder = { Text("Add a caption...", color = Color.Gray, fontSize = 13.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f)
                                    ),
                                    maxLines = 3
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (viewModel != null) {
                                        viewModel.sendFile(
                                            clientUuid = client.uuid,
                                            clientName = client.name,
                                            uri = pending.uri,
                                            fileName = pending.name,
                                            mimeType = pending.mimeType,
                                            fileSize = pending.size,
                                            caption = captionText.trim()
                                        )
                                    } else {
                                        onSendMessage(client.uuid, client.name, captionText.ifEmpty { pending.name })
                                    }
                                    pendingFileToSend = null
                                    Toast.makeText(context, "Sending ${pending.name}...", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Send", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingFileToSend = null }) {
                                Text("Cancel", color = Color.Gray)
                            }
                        }
                    )
                }

                // Full-screen Image Viewer Lightbox
                fullScreenImageUrl?.let { imgUrl ->
                    Dialog(
                        onDismissRequest = { fullScreenImageUrl = null },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.95f))
                                .clickable { fullScreenImageUrl = null },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = imgUrl,
                                contentDescription = "Full view",
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                            IconButton(
                                onClick = { fullScreenImageUrl = null },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(24.dp)
                                    .size(40.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
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
