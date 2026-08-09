# Whisker System Requirements Specification

## 1. Executive Overview & System Architecture

**Whisker** is a secure, privacy-focused WebRTC communication gateway system supporting low-latency peer-to-peer audio/video calls, real-time messaging, guest request approval flows, and local tunnel connectivity.

The system consists of three primary components:
1. **Whisker Mobile Gateway App**: Primary host interface, WebRTC signaling endpoint, notification receiver, and call management hub (Android active; native iOS in roadmap).
2. **Whisker Home Server & CLI (`whisker`)**: Unified PyPI-compatible Python package (`whisker/server.py`, `whisker/cli.py`, `whisker/fcm.py`) acting as an event queue, FCM push tickle hub, WebRTC signaling bridge, and scriptable CLI client engine.
3. **Whisker Web Visitor Client (`whisker/index.html`)**: Web application served directly by the Home Server allowing instant visitor access, instant messaging, and audio/video calling without user registration or app installation.

---

## 2. Whisker Mobile App (Android Client) Requirements

### 2.1 Core Functionality
- **Home Server Connectivity**:
  - Connects to the Home Server via standard HTTP/HTTPS API routes.
  - Pure FCM Signaling & Battery Efficiency: Event fetching is triggered strictly on-demand when an FCM push tickle notification is received. The app connects to the Home Server to fetch pending events (`/api/host/events`), processes the events, and immediately returns to a low-power sleep state. Persistent background connections (such as continuous SSE background streams or periodic polling loops) are explicitly prohibited to maximize battery life.
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

### 2.2 Settings Modal UI & Configuration
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

### 3.2 Host API Endpoints (`/api/host/*`)
- `POST /api/host/register_push`: Registers the mobile app's FCM token with the Home Server. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/fcm_config`: Retrieves FCM project configuration (Sender ID, Project ID) derived from `whisker-push-sa.json` for initial mobile app pairing. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/events`: Fetches pending `host_events` queued for the host upon FCM tickle notification. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/ack`: Acknowledges and clears processed host events from the server queue. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/message`: Retrieves specific message details and `previousMessageId` chain references by message `id`. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/create_invite`: Generates single-use guest invite links/codes. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/invites`: Retrieves list of active guest invite codes. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/delete_invite`: Revokes/deletes a guest invite code. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/clients`: Retrieves registered guest clients and pending access requests. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `POST /api/host/response`: Unified endpoint for host actions (sending WebRTC call answer/reject/hangup SDP, ICE candidates, host text replies, approving/rejecting guest requests). [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)]
- `GET /api/host/stream`: SSE stream for host event pushes. [Access: Host Auth (`Authorization: Bearer <HOME_SERVER_ACCESS_KEY>`)] [NOT USED - Deprecated/Unused by Mobile App in favor of pure FCM push tickles for battery efficiency]

### 3.3 Guest & Web Client API Endpoints (`/api/*`)
- `POST /api/register`: Submits a visitor access request (creates a `PENDING` request record). [Access: Public Unauthenticated]
- `POST /api/claim_invite`: Claims a single-use invite code and receives a valid guest `uuid` and `token`. [Access: Public Unauthenticated (Requires valid Invite Code)]
- `GET /api/messages`: Retrieves message history for an approved guest client. [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `POST /api/message`: Delivers a guest chat message to the host event queue. [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `POST /api/call`: Submits a WebRTC call offer SDP from a guest client. [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `POST /api/signal`: Submits WebRTC ICE candidate signals from a guest client. [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `POST /api/hangup`: Submits a hangup signal from a guest client. [Access: Guest Auth (`uuid` + `token` parameter verification)]
- `GET /api/events`: SSE stream for guest clients to receive real-time host replies (chat messages, call accept/reject SDPs, ICE candidates, hangup). [Access: Guest Subscriber (`uuid` parameter)]
- `GET /api/info`: Returns Home Server health status, mobile app connectivity (`host_connected`, `host_reachable`), and push configuration status. [Access: Public Unauthenticated]
- `GET /api/init-status`: Checks if the Home Server has been initialized with a host PIN. [Access: Public Unauthenticated]

---

## 4. Web Visitor Application (`whisker/index.html`) Requirements

- **Responsive Web Portal**: Served directly by `whisker/server.py` at the server root URL.
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

### 5.1 Local Persistence & Client CLI Engine
- **Storage**: Manages client configuration in a local SQLite database (`whisker.db`).
- **Config Keys**: `server_url`, `uuid`, `token`, `name`.

### 5.2 Commands & Workflows
1. `whisker init <invite_url>`:
   - Parses the Home Server URL and single-use invite code from the invite URL.
   - Calls `POST /api/claim_invite` on the Home Server to retrieve client credentials (`uuid`, `token`, `name`).
   - Persists config into `whisker.db`.
2. `whisker send <message>`:
   - Validates client initialization.
   - Sends a text message to the host via `POST /api/message`.
3. `whisker call`:
   - Validates client initialization.
   - Initiates a WebRTC call offer to the host via `POST /api/call`.
4. `whisker listen`:
   - Connects to the SSE endpoint `GET /api/events?uuid=<uuid>&token=<token>`.
   - Listens continuously for real-time events: `chat_message`, `call_accepted`, `call_rejected`, `webrtc_call_initiate`, `hangup`.
5. `whisker info`:
   - Displays local client configuration (`Name`, `UUID`, `Home Server`).
   - Queries `GET /api/info` on the Home Server to check mobile app reachability and push status.

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
   - The Mobile App relies strictly on FCM push notifications to trigger fetching pending events from `/api/host/events` on-demand. Continuous background SSE streams and periodic polling loops are explicitly prohibited to conserve mobile device battery.

---

## 7. Future System Roadmap

1. **iOS Mobile Gateway Application**:
   - Native iOS application developed in Swift / SwiftUI.
   - WebRTC audio/video call integration with CallKit support.
   - Background APNs (Apple Push Notification service) push tickle support for power-efficient wakeup.
2. **Multi-Device & Multi-Server Client Support**:
   - Support for multiple mobile gateway clients paired with a single Home Server.
   - Support for connecting a single mobile app to multiple Home Servers with independent server toggles.
   - Direct cross-server audio/video call routing.
