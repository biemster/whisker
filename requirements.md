# Whisker System Requirements Specification

## 1. Executive Overview & System Architecture

**Whisker** is a secure, privacy-focused WebRTC communication gateway system supporting low-latency peer-to-peer audio/video calls, real-time messaging, guest request approval flows, and local tunnel connectivity.

The system consists of three primary components:
1. **Whisker Mobile Gateway App**: Primary host interface, WebRTC signaling endpoint, notification receiver, and call management hub (Android active; native iOS in roadmap).
2. **Whisker Home Server & CLI (`whisker`)**: Unified PyPI-compatible Python package (`whisker/server.py`, `whisker/cli.py`, `whisker/fcm.py`) acting as an event queue, FCM push tickle hub, WebRTC signaling bridge, and scriptable CLI client engine.
3. **Whisker Web Visitor Client (`whisker/index.html`)**: Web application served directly by the Home Server allowing instant visitor access, instant messaging, and audio/video calling without user registration or app installation.

---

## 2. Whisker Mobile App (Android Client) Requirements

### 2.1 Target API Level & Legacy Android Device Support (Repurposing Old Phones)
- **Minimum Supported API Version**: **API Level 23 (Android 6.0 Marshmallow)**.
  - **Rationale**: To maximize hardware reuse and allow older smartphones (from 2015+) to be repurposed as dedicated IP cameras, doorbell hubs, and Whisker gateways, the minimum SDK is pegged to the lowest viable API level permitted by modern Google Play Services and Firebase Messaging SDKs.
  - **Language & Runtime Compatibility**: Core Library Desugaring (`desugar_jdk_libs`) is enabled to allow modern Java 8+ APIs and Kotlin coroutines/flows to run seamlessly on Android 6.0+ devices without runtime missing symbol exceptions.
  - **Permission & Hardware Handling**:
    - Leverages dynamic runtime permissions (`CAMERA`, `RECORD_AUDIO`) introduced in Android 6.0 (API 23).
    - Features defensive camera hardware checks (`android.hardware.camera`, `android.hardware.camera.autofocus` declared with `required="false"`) ensuring smooth operation even on legacy devices with partial or front-only camera modules.
    - Uses backward-compatible screen wake & lockscreen dismissal flags (`FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`, `FLAG_KEEP_SCREEN_ON`, `FLAG_DISMISS_KEYGUARD`) alongside modern Android O+ APIs so incoming video calls wake legacy displays reliably.
    - Employs version-gated notification channels (supporting legacy notifications on Android 6.0-7.1 and Notification Channels on Android 8.0+).

### 2.2 Core Functionality
- **Home Server Connectivity**:
  - Connects to the Home Server via standard HTTP/HTTPS API routes.
  - Pure FCM Signaling & Battery Efficiency: When the app is in the background or dormant, persistent background connections (such as continuous SSE background streams or background polling loops) are explicitly prohibited to maximize battery life. Background event fetching is triggered strictly on-demand when an FCM push tickle notification is received. The app connects to the Home Server to fetch pending events (`/api/host/events`), processes the events, and immediately returns to a low-power sleep state.
  - Active Foreground Health & Client Presence Polling: When the mobile app is open and active in the foreground, the app runs a lightweight foreground polling loop to query client online statuses and verify server health, keeping the real-time Connection bubble and guest online indicators up-to-date.
  - No Battery Optimization Exemption Required: Every feature and background/Doze wakeup function must work out-of-the-box on standard Android power management without requiring battery optimization exemptions (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) or special whitelisting.
- **WebRTC Calling & Video**:
  - Receives incoming calls and places outgoing WebRTC audio/video calls with guest clients with live video rendering and WebRTC PeerConnection setup.
  - Requests runtime CAMERA and RECORD_AUDIO permissions upon setup and gracefully falls back to synthetic video/audio generators if physical media hardware is unavailable.
  - Handles SDP exchange (`offer`, `answer`) and WebRTC ICE candidate signaling (`webrtc_ice_candidate`) through the Home Server event pipeline.
- **Client & Access Control**:
  - Manages guest visitor approval flows (approving or rejecting pending guest registration requests).
  - Generates single-use guest invite links and codes for fast onboarding.
  - Maintains guest client profiles and active messaging threads.
- **Real-Time Messaging & Historical Chain Sync**:
  - Bidirectional text chat with approved guest clients.
  - Automatically syncs complete message history chains using `previousMessageId` links and `/api/host/message` queries when receiving new message event notifications.
  - Message status delivery indicators and thread organization.

### 2.3 Settings Modal UI & Configuration
- **Home Server Field**:
  - Text input field labeled **"Home Server Address"** (e.g., `https://whisker-tunnel.local`).
- **Home Server Access Key Box**:
  - Info textbox displaying the auto-generated **Home Server Access Key** (local private access key).
  - Includes a quick "Copy" action to copy the key to the system clipboard.
- **FCM Push Token Box**:
  - Info textbox displaying the current **FCM Push Token**.
  - Includes a quick "Copy" action to copy the token to the system clipboard for server registration.
- **Simplified Scope**:
  - Obsolete settings elements are removed in favor of a clean, streamlined setup.

---

## 3. Whisker Home Server (`whisker server`) Requirements

### 3.1 Architecture & Storage
- **Runtime**: Python 3.7+ standalone HTTP server with minimum dependencies (`sqlite3`, `requests`).
- **Data Persistence**:
  - Primary: SQLite database engine (`sqlite3` / `better-sqlite3`).
  - Fallback: Graceful in-memory data store if SQLite native drivers are unavailable.
- **Database Schema**:
  - `requests`: Guest UUID, client name, request reason, status (`PENDING` / `APPROVED`), token, timestamp.
  - `invites`: Single-use invite code, reserved client name, used flag (`0`/`1`), timestamp.
  - `messages`: Message ID (`id`), Client UUID (`client_uuid`), sender (`guest`/`host`), message text (`message_text`), file path (`file_path`), timestamp (`timestamp`), previous message ID (`previous_message_id`).
  - `host_events`: Event ID, event type (`visitor`, `message`, `call`, `invite_claimed`), JSON payload, timestamp.
  - `settings`: Key-value configuration pairs (including host access PIN and FCM push token).

### 3.2 Host API Endpoints (`/api/host/*` and Host Management)
- `POST /api/register_mobile`: Registers or pairs a mobile app with the Home Server (`username`, `pin_token`, `push_token`). The first paired device is automatically granted Admin status (`APPROVED`, `is_admin: 1`); subsequent mobile devices are registered as `PENDING` and enqueue a `mobile_pairing_request` host event for admin approval. [Access: Public / Host Pairing]
- `POST /api/host/register_push`: Registers or updates the mobile app's FCM push token with the Home Server. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/fcm_config`: Retrieves FCM project configuration (Project ID, Sender ID, App ID, API Key) derived from `whisker-push-sa.json` for initial mobile app pairing. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/fcm_config`: Updates the FCM API key in the server settings. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/direct_calls`: Enables or disables direct visitor calling without prior approval for this host (`allow_direct_calls: true/false`). [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/events`: Fetches pending `host_events` queued for the host upon FCM tickle notification. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/ack`: Acknowledges and clears processed host events from the server queue by `eventId`. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/message`: Retrieves specific message details and `previousMessageId` chain references by message `id` (`/api/host/message?id=<id>`). [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/create_invite`: Generates single-use guest invite links/codes with optional reserved guest name. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/invites`: Retrieves list of active guest invite codes created by the host. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/delete_invite`: Revokes and deletes an active guest invite code by `code`. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/clients`: Retrieves registered guest clients and companion mobile hosts with live online presence status. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/delete_client`: Removes an approved caller or companion mobile host (aliases: `/api/host/remove_client`, `/api/host/remove_contact`, `/api/host/delete_host`). Purges database records, revokes host-client approvals, evicts active in-memory streams, and logs removal events. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/claim_remote_invite`: Connects/federates to a remote Whisker Home Server by claiming an invite code from the remote server and establishing a paired client record. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/response`: Unified endpoint for host actions:
  - `registration_response`: Approves guest visitor access requests.
  - `mobile_pairing_response`: Approves, rejects, or deletes companion mobile app pairing requests.
  - `chat_message` / `message`: Sends text chat or media file messages to guest clients or companion mobile peers.
  - `webrtc_call_initiate` / `call`: Initiates outgoing WebRTC call offers to companion mobile peers or guests.
  - `webrtc_call_answer` / `webrtc_ice_candidate` / `signal`: Submits call answer SDP or ICE candidates.
  - `webrtc_call_hangup` / `hangup`: Terminates or declines active WebRTC calls.
  - `delete_client` / `remove_contact`: Removes client or mobile contact approval.
  [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]

### 3.3 Guest & Web Client API Endpoints (`/api/*`)
- `GET /api/init-status`: Checks if the Home Server has been initialized with at least one paired mobile host. [Access: Public Unauthenticated]
- `GET /api/info`: Returns Home Server health status, mobile app connectivity (`host_connected`, `host_reachable`), push configuration status, and direct calling availability (`allow_direct_calls`, `direct_call_hosts`). [Access: Public Unauthenticated]
- `POST /api/register`: Submits a visitor access request (`uuid`, `name`, `reason`), creating a `PENDING` request record, enqueuing a `visitor` host event, and triggering an FCM tickle to hosts. [Access: Public Unauthenticated]
- `POST /api/claim_invite`: Claims a single-use invite code and receives a valid guest `uuid`, `token`, `name`, `host_uuid`, and `host_name`. [Access: Public Unauthenticated (Requires valid Invite Code)]
- `GET /api/messages`: Retrieves message history for an approved guest client (`/api/messages?uuid=<uuid>&token=<token>`). [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `GET /api/message`: Retrieves specific message details by ID with previous message chain link (`/api/message?id=<id>&uuid=<uuid>&token=<token>`). [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `POST /api/message`: Delivers a guest chat message or media attachment to the host event queue. Supports optional `contact` / `host_uuid` parameter to route to a specific approved host. [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `POST /api/call`: Submits a WebRTC call offer SDP from a guest client. Supports direct calling without prior registration when direct calls are enabled on the host, or standard approved guest calling targeted via `contact` / `host_uuid`. [Access: Guest Auth or Public Direct Call]
- `POST /api/signal`: Submits WebRTC ICE candidate signals from a guest client. [Access: Guest Auth or Public Direct Call]
- `POST /api/hangup`: Submits a hangup signal from a guest client. [Access: Guest Auth or Public Direct Call]
- `GET /api/events`: SSE stream for guest clients to receive real-time host replies (chat messages, call accept/reject SDPs, ICE candidates, hangup). [Access: Guest Subscriber (`uuid` parameter)]
- `GET /api/guest/events`: Polling endpoint for guest/federated clients to fetch and clear pending events (alias: `/api/guest/pending_events`). [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `GET /api/client/hosts`: Retrieves list of approved hosts available for this guest client on the server (`/api/client/hosts?uuid=<uuid>&token=<token>`). [Access: Guest Auth (`uuid` + `token` parameter verification)]

---

## 4. Web Visitor Application (`whisker/index.html`) Requirements

- **Responsive Web Portal**: Served directly by `whisker/server.py` at the server root URL.
- **Multi-Connection Browser Management**:
  - Automatically manages multiple paired host sessions in browser `localStorage` (`whisker_connections`).
  - Top navigation bar features a dynamic **Connected Devices** dropdown switcher showing paired host names, active indicator, and one-click session switching or manual invite code claiming.
- **Active Call Expansion**:
  - When an audio or video call is active, the call video window expands to fill the full container width and height (up to 2/3 view on mobile screens).
  - The instant messages thread smoothly re-positions below the active video element.
- **Interactive Header Connectivity Status**:
  - Clicking the "Connected" system status bubble opens a comprehensive **Connectivity Modal**.
  - Modal displays live status for Home Server, Mobile Phone App (Online/Sleeping), Direct Socket Stream, FCM Push Notifications, Guest Name, and Client UUID.
- **Clean Aesthetic & Mobile First**:
  - Minimal top padding between header and main control area.
  - "Instant messages:" thread view with local clear capabilities.
  - Synthetic canvas/AudioContext media stream generator fallback when webcam/mic permission is denied or unsupported in browser environment.

---

## 5. Whisker Python CLI (`whisker`) Requirements

### 5.1 Local Persistence & Multi-Connection CLI Engine
- **Storage**: Manages client configuration in a local SQLite database (`whisker.db`).
- **Database Tables**:
  - `connections`: `id` (INTEGER PK), `name` (TEXT), `uuid` (TEXT UNIQUE), `token` (TEXT), `server_url` (TEXT), `host_uuid` (TEXT), `host_name` (TEXT), `is_active` (INTEGER), `created_at` (INTEGER).
  - `config`: Legacy key-value table kept in sync with the active connection for backwards compatibility.
- **Connection Resolution**: Automatically resolves targets by connection ID (`#1`), host name (exact or substring), client UUID, or host UUID.

### 5.2 Commands & Workflows
1. `whisker init <invite_url>`:
   - Parses the Home Server URL and single-use invite code from the invite URL or prompt.
   - Calls `POST /api/claim_invite` on the Home Server to retrieve client credentials (`uuid`, `token`, `name`, `host_uuid`, `host_name`).
   - Saves the connection into the `connections` table in `whisker.db` and marks it as the active default connection.
2. `whisker connections` (aliases: `contacts`, `list`, `conns`):
   - Lists all configured paired host connections in a formatted table displaying active status indicator, connection ID, host name, client name, UUID, and server URL.
3. `whisker switch <id|name|uuid>` (aliases: `use`, `select`):
   - Switches the active default host connection.
4. `whisker remove <id|name|uuid>` (aliases: `delete`, `disconnect`):
   - Removes a saved paired host connection from local storage.
5. `whisker send [-c <contact>] <message>`:
   - Delivers a text message to the active host or targeted contact via `POST /api/message`.
6. `whisker call [-c <contact>]`:
   - Initiates a WebRTC call offer to the active host or targeted contact via `POST /api/call`.
7. `whisker listen [-c <contact>] [--all]`:
   - Listens to live SSE events from `GET /api/events`. Supports listening to the active connection, a specific targeted contact (`-c`), or across all saved connections simultaneously (`--all`).
8. `whisker info [-c <contact>]`:
   - Displays client configuration and queries Home Server `GET /api/info` to check mobile app reachability and push status.

---

## 6. Security, Authentication & Data Flow Protocols

1. **Host Authentication & Dynamic Pairing**:
   - The Mobile Gateway App authenticates to the Home Server using the **Home Server Access Key** sent in the `Authorization: Bearer <HOME_SERVER_ACCESS_KEY>` HTTP header.
   - On initial setup, the Home Server starts in uninitialized pairing mode. When the Mobile Gateway App makes its first API call, the Home Server pairs and locks onto the app's randomly generated Access Key (persisting it in the `settings` database).
   - Once paired, the Home Server restricts all subsequent `/api/host/*` host operations to requests carrying this exact Access Key, ensuring only one mobile app instance is linked and preventing unauthorized third-party access.
2. **Guest Authentication**:
   - Guests are issued a unique `uuid` and `token` upon registration approval or invite code claim.
   - Guest requests require valid `uuid` and `token` pairs verified against approved database records.
3. **Pure FCM Push Tickle Architecture & Battery Preservation**:
   - When a guest posts a call, message, or invite claim event, the Home Server enqueues the event and triggers an FCM HTTP v1 "tickle" payload via Google's OAuth2 service account credentials (`whisker-push-sa.json`) to wake up or notify the Whisker Mobile Gateway App.
   - FCM HTTP v1 authentication uses Google OAuth2 JWT tokens dynamically generated from `whisker-push-sa.json` (created via `whisker setup-fcm`). Legacy FCM server keys are deprecated and removed.
   - Zero Metadata in Push Messages: Push notifications sent via FCM are strictly empty tickles containing no payload data (no call SDP, contact names, tokens, or client UUIDs). They serve solely as a wake-up impulse informing the mobile device to fetch pending host events from `/api/host/events` on the Home Server.
   - The Mobile App relies strictly on FCM push notifications to trigger fetching pending events from `/api/host/events` on-demand when in the background or dormant. Continuous background SSE streams and background polling loops are explicitly prohibited to conserve mobile device battery.
   - When the mobile app is open in the foreground, a lightweight foreground presence/health polling loop is permitted to maintain accurate real-time connection bubbles and client online status indicators.
   - No battery optimization exemption is required for standard operation.

---

## 8. Multi-Mobile App Support (Implementation Requirements)

As part of the roadmap for supporting multiple mobile apps on a single Home Server, the following architecture and behavioral rules apply:

### 8.1 First Pairing (Admin Mobile App)
- **Auto-Approval**: The first mobile app to pair with the Home Server is automatically approved and becomes the **Admin** of the server.
- **Username Requirement**: The user must specify a **Username** in addition to the Home Server Address during pairing. This username is displayed in the web client and CLI.
- **ID-Based Connection**: Connections and routing remain ID-based (UUID). The mobile app can dynamically change its username at will without losing functionality. The Home Server will silently inform web/cli clients of this username change.
- **Legacy Compatibility**: For the first paired app, the setup experience and HTML presented to the web client remain identical to the single-host setup.

### 8.2 Subsequent Pairings (Secondary Mobile Apps)
- **Admin Approval Required**: A second (and any subsequent) mobile app attempting to pair must be explicitly approved by the Admin mobile app.
- **Pairing Request UI**: The pairing request appears in the Admin's mobile app similar to a web/cli visitor request, but is clearly badged and prioritized as a **Mobile App Pairing Request**.
- **Early FCM Registration**: Secondary mobile apps must register their FCM push token *during* the initial pairing request (before approval). 
- **Approval/Rejection Tickles**: The Admin's response (approve/reject) triggers an FCM tickle to the secondary mobile app. In the case of a rejection, the rejection status is exceptionally included directly in the FCM tickle payload, because the rejected app lacks the API credentials to fetch events from the Home Server's restricted endpoints.

### 8.3 Web & CLI Client Isolation
- **Scoped Visibility**: Web and CLI clients only have access to, and knowledge of, the specific mobile app(s) that invited or approved them. Other paired mobile apps on the same Home Server are completely invisible to them.
- **CLI `--contact` Parameter**: The `whisker` CLI will introduce a `--contact` parameter. If a CLI client has been approved by multiple mobile apps on the same Home Server, this parameter specifies which mobile app to message or call.
- **Web Client Contact Selection**: If a web client is approved by or holds invites for more than one mobile app on the same Home Server, the web client's landing page will present a contact selection screen before initiating communication.

### 8.4 Future-Proofing
- The implementation data model and routing logic supports direct connections (messaging/calling) between different mobile apps on the same Home Server, as well as cross-server federation.

### 8.5 Identified Edge Cases & Architectural Adjustments
1. **Unauthenticated FCM Registration (Edge Case)**: Secondary mobile apps submit their FCM token during the pairing request (`/api/register_mobile`) before approval.
2. **Data Model Restructuring**: The Home Server's database utilizes a dedicated `hosts` table (`host_uuid`, `username`, `pin_token`, `push_token`, `is_admin`, `status`) to replace global single-host settings entries.
3. **Routing Relationships**: The `requests`, `messages`, and `events` tables include `host_uuid` foreign keys to support multi-host isolation and targeted routing.
4. **Admin Single Point of Failure**: If the sole Admin mobile app is uninstalled or disconnected permanently, the Home Server can be reset or a secondary host promoted via CLI.
5. **FCM Payload Limits**: Rejection data sent in the FCM tickle is kept minimal to strictly adhere to FCM's 4KB data payload limits.

---

## 9. Mobile-to-Mobile Messaging & Communication Specification

Whisker supports full-duplex messaging, media transfer, and WebRTC audio/video calls directly between mobile apps—both on the **same Home Server** (local) and across **different Home Servers** (remote federation).

### 9.1 Technical Architecture & Signaling Flow

1. **Same Home Server (Local Mobile Peers)**:
   - Mobile apps paired to the same Home Server communicate using the same internal event broker (`_notify_host_streams`) as web and CLI clients.
   - Outgoing messages and WebRTC SDP offers/answers are posted to the Home Server's unified response/message endpoints with the target peer's `host_uuid`.
   - The Home Server enqueues the event, pushes an FCM tickle to the target mobile device, and delivers the event payload upon retrieval.
   - **UI Representation**: The contact card and chat header display `📱 MOBILE APP` and an emerald `🏠 local` badge indicating the peer resides on the same Home Server instance.

2. **Cross-Server (Remote Home Server Federation)**:
   - When connecting mobile apps across different Home Servers, Home Server A acts as a client on Home Server B and vice versa, establishing a symmetric bidirectional channel.
   - **Invite Mechanism**: Connection uses the same invite structure as standard visitors (`http://<remote-server-address>/invite/<invite-code>`).
   - **Claim Flow**: The local mobile app enters or scans the invite URL via the **Connect Peer via Invite** dialog (`/api/host/claim_remote_invite` or direct client claim). The local client claims the invite on the remote server, obtaining a guest `uuid` and `token` alongside the remote server's address.
   - **Outbound Signaling & Messaging**: When messaging or calling a remote mobile peer, the local app issues HTTP requests (`POST /api/message`, `POST /api/call`, `POST /api/signal`, `POST /api/hangup`) directly to the remote server's API endpoints using the claimed guest credentials.
   - **Inbound Event Polling & Push**: Remote events from the peer server are fetched via `/api/guest/events?uuid=<guest_uuid>` and synced into the local Room database.
   - **UI Representation**: In both mobile apps, the contact card and chat header prominently display `📱 MOBILE APP` alongside a blue `🌐 <remote_server_address>` badge, making it immediately transparent which Home Server address the peer is hosted on.

### 9.2 Zero Inter-Server Background Traffic & On-Demand Architecture
- **Strictly Event-Driven / On-Demand**: Home Servers never continuously ping, poll, or broadcast to other Home Servers. There are zero background heartbeats, synchronization cycles, or gossip protocols between servers.
- **Zero Idle Overhead**: When no user is actively sending a message or initiating a call, inter-server network traffic is strictly 0.0 KB/s.
- **Client-Initiated Action**: Communication to a remote Home Server occurs exclusively on-demand when the mobile application performs an explicit user-initiated action (e.g., sending a text message, uploading a file, or initiating a WebRTC call handshake) or upon receiving an incoming event wake-up tickle.
- **Infinite Scalability ($O(1)$ Overhead)**: Because servers operate in complete isolation without global discovery or mesh maintenance, global server capacity scales infinitely with zero performance degradation regardless of the total number of deployed Whisker instances.

### 9.3 Mobile UI Indicator Rules
- **Peer Type Badge**:
  - `📱 MOBILE APP` (Purple `#8B5CF6`) for paired mobile applications / companion apps.
  - `💻 WEB / CLI` (Blue `#3B82F6`) for web visitors and scriptable CLI agents.
- **Home Server Location Badge**:
  - `🏠 local` (Teal `#064E3B` / `#6EE7B7`) when `serverAddress` is empty or `"local"`.
  - `🌐 <server_address>` (Navy `#1E3A8A` / `#93C5FD`) when connected to an external Home Server.
- **Actions Available**:
  - Direct **💬 Chat** button opening the encrypted message stream for that specific peer.
  - Direct **📞 Call** button initiating a peer-to-peer WebRTC video/audio call.
  - **Inline Rename** to assign friendly custom contact names.
  - **Revoke / Disconnect** to permanently unpair the peer.

---

## 10. WebRTC Signaling Lifecycle & Call State Synchronization Protocol

To ensure robust call state transitions and prevent desynchronized call states (e.g. ghost calls where one client remains connected after the peer hangs up, or race conditions where stale ICE candidates/hangup signals pollute subsequent sessions), all Whisker clients (**Mobile App**, **Web Portal**, and **Python CLI**) adhere to the unified **`call_id` State Synchronization Protocol**.

### 10.1 `call_id` Session Lifecycle
1. **Generation on Call Init**:
   - Every outgoing call generates a unique session identifier: `call_<timestamp>_<random_hex>` (e.g., `call_1772442655000_a1b2c3`).
   - The `call_id` is included in all signaling messages: initial offer (`call` / `webrtc_call_initiate`), answer (`webrtc_call_answer`), ICE candidates (`webrtc_ice_candidate`), and termination (`webrtc_call_hangup` / `call_declined` / `call_ended`).
2. **State Scoping & Validation**:
   - **Incoming Calls**: The callee registers the incoming `call_id`. If an incoming call is received while another call is already active or in progress, conflicting signals from older/unmatched `call_id` sessions are ignored.
   - **Answer Processing**: An outgoing caller only transitions from `Outgoing` (ringing) to `Active` (connected) if the incoming `webrtc_call_answer` event matches the active session's `call_id`.
   - **ICE Candidate Routing**: Candidates received for expired or mismatched `call_id` sessions are dropped to prevent stale network candidate routing.
   - **Hangup & Decline Handling**: Hangup signals explicitly propagate the `call_id`. A client tearing down a call verifies that the hangup matches its current call session, ensuring that network jitter or duplicate hangup deliveries do not prematurely terminate subsequent new calls.

### 10.2 Client-Specific Implementations

1. **Python CLI Client (`whisker call` & `whisker listen`)**:
   - **Automatic WebRTC Answering**: `whisker listen` handles incoming `call` offers asynchronously, captures camera/microphone or generates synthetic test streams, and automatically posts the SDP answer and ICE candidates carrying the session's `call_id`.
   - **Tkinter Video Window & Headless Fallbacks**: When GUI libraries are present, incoming and outgoing video streams are rendered in a responsive Tkinter video frame; in headless environments, audio and synthetic pipelines operate seamlessly without raising unhandled GUI exceptions.
   - **Async Coroutine & PeerConnection Cleanup**: Uses strict asynchronous task lifecycle management (`await pc.close()`, `await session.handle_remote_ice_candidate`) to eliminate pending unawaited coroutine warnings and ensure clean socket closure upon hangup or Ctrl+C.

2. **Whisker Web Client (`whisker/index.html`)**:
   - Maintains a scoped `currentCallId` in the global application state.
   - Generates `call_id` in `initiateCall` and forwards it in the initial `POST /api/call` request.
   - Filters SSE signaling events (`webrtc_call_answer`, `webrtc_ice_candidate`, `webrtc_call_hangup`) against `currentCallId`.
   - Resets `currentCallId` and closes WebRTC tracks/peer connection on call hangup, decline, or window unload.

3. **Android Mobile App (`GatewayNetworkManager.kt` & `GatewayUi.kt`)**:
   - `CallState` hierarchy (`Incoming`, `Outgoing`, `Active`) tracks the associated `callId`.
   - `WebRtcSignal` payload wrapper parses and carries `callId`.
   - `processIncomingPayload` validates incoming answers and hangup signals against the current `CallState`'s `callId`.
   - Outbound signaling helpers (`publishOutgoingSdpOffer`, `publishSdpAnswer`, `publishIceCandidate`, `declineOrEndCall`) attach `call_id` / `callId` to all outgoing payloads.
   - `WebRtcCallContainer` WebView JS bridge filters incoming signals by `callId` and propagates `callId` back to native Android networking handlers.

