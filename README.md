# Whisker Home Server & Mobile App

> **Real-Time Messaging, WebRTC P2P Calls & File Backup**

Whisker is a fully self-hosted communication and backup system that lets you run your own private service where callers and visitors can reach you instantly from any web browser without creating an account or installing an app. As the host, you receive messages, live audio/video calls, and can easily back up files (such as photos) directly from your mobile device to your user-owned home server.

Whether you want a hassle-free personal communication system for external visitors, an effortless IP camera solution, or a secure way to back up your personal mobile files to your own hardware, Whisker provides a flexible, battery-friendly solution.

---

## Key Highlights

- **Fully Self-Hosted**: Retain complete ownership of your data, messages, and media by running your server on your own hardware or private cloud instance.
- **Zero-App Visitor Access**: Anyone can message or call you simply by opening your Home Server URL in a web browser—no user accounts or app downloads required.
- **Direct Mobile Alerting & WebRTC Calls**: Receive live audio/video calls, text messages, and visitor notifications directly on your Android phone with low-latency peer-to-peer WebRTC streaming.
- **Mobile-to-Mobile Messaging & Calling**: Full communication between mobile apps on the same Home Server (`local`) and across different Home Servers (cross-server federation) using invite links and direct signaling.
- **Mobile File & Photo Backup**: Easily back up photos, media, and files from your mobile devices directly to your user-owned home server storage, eliminating third-party cloud dependency.
- **Multi-Device Pairing & Management**: Pair multiple mobile apps with a single home server, complete with admin approvals, username recognition, and targeted message/call routing.
- **Responsive & Interactive Web Client**: Features active call video layout expansion, an interactive system status indicator with detailed connectivity breakdown modal, instant messaging thread, and local media fallback controls.
- **IP Camera & Hardware Repurposing**: Turn obsolete Android or mobile devices into dedicated IP cameras or home monitors by installing the Whisker app and enabling auto-answer for incoming calls.
- **AI Harness & Scriptable Integration**: Includes `whisker`, a scriptable CLI client and Python package engineered for AI agents, headless services, and automated workflow integrations.
- **Ultra Battery-Friendly**: Eliminates battery-draining background connections or constant polling loops on your mobile phone. Your phone wakes up on-demand strictly when an FCM push alert arrives.
- **Instant Invite Links & Visitor Approval**: Share single-use invite links or approve visitor access requests with a single tap.
- **Lightweight & Zero-Config**: Runs on a modular Python Home Server (`whisker/server.py`) backed by SQLite with minimal setup effort.

---

## Project Roadmap

### Current State (Operational)
- **Architecture**: Home Server paired with Android Mobile Applications, Web Visitor Portals, and CLI clients.
- **Messaging**: Real-time bidirectional text chat with automatic historical message chain sync (`previous_message_id`).
- **WebRTC Calling**: Full peer-to-peer audio and video calling operational with adaptive camera/mic permissions, candidate signaling, and synthetic fallback stream generation.
- **Multi-Device Pairing**: Support for multiple mobile host applications paired with a single home server, featuring admin approval controls, custom usernames, invite code generation, and multi-device routing for messages and calls.
- **Mobile-to-Mobile Communication**: Real-time messaging and WebRTC calling between mobile apps on the same Home Server (`local`) and across different Home Servers (`serverAddress`), unified by invite pairing and direct signaling.
- **Multi-Server Mobile Client**: Support for connecting a single mobile application to multiple home servers simultaneously, featuring independent controls to connect, disconnect, or mute specific servers (e.g. managing family, work, and community servers in one app).
- **Web Client UI**: Responsive fluid layout with auto-expanding video on active calls, interactive header status indicator with connectivity modal, and mobile-optimized columnar view.
- **Push Notifications**: FCM HTTP v1 push notification tickle integration complete with low-power battery optimization.

### In Development & Future Roadmap
- **Model Context Protocol (MCP) Server**: Python-native MCP server extending the Whisker client core with zero external dependencies, exposing tools and resources for AI agent platforms (e.g., Claude Desktop, Cursor) to automate messaging, initiate calls, and inspect event queues.
- **Embeddable Web Client Widget**: Lightweight, drop-in JavaScript widget / Web Component for personal websites (similar to customer support helpdesk applets), enabling site visitors to initiate direct messaging and WebRTC calls to host devices.
- **File & Media Transfer**: Secure attachment and file transfer directly between web visitors/mobile clients and the home server.
- **Automated Background Photo Backup**: Scheduled automatic background synchronization of camera roll photos and media folders to home server storage.
- **iOS Mobile Application**: Native iOS client app built with Swift / SwiftUI, featuring WebRTC P2P calling, background APNs (Apple Push Notification service) push tickle support, and call notification alerts.

---

## System Architecture

Whisker consists of three primary components:

1. **Android Mobile App**: The primary mobile interface, WebRTC calling endpoint, and notification center for the host.
2. **Home Server & CLI (`whisker`)**: A unified Python package (`whisker/server.py`, `whisker/cli.py`, `whisker/fcm.py`) that runs the Home Server (backed by SQLite, event queue, WebRTC signaling, FCM push tickles) and CLI client engine.
3. **Web Visitor Client (`whisker/index.html`)**: An embedded web application served directly by the Home Server allowing instant visitor access, messaging, and audio/video calling without account registration.

> For the full protocol spec, API schema, and security details, see [requirements.md](requirements.md).

---

## Quick Start Guide

### 1. Install & Launch the Home Server

Before launching, complete the [FCM Push Setup](#fcm-push-setup-whisker-setup-fcm) at the bottom of this document to enable push notifications.

Install the package via `pip` or run directly with Python 3. The `--name` parameter is **obligatory** and determines the Home Server's display name and dedicated database file (`whisker_server_<name>.db`):

```bash
pip install .
whisker server --name "Home Base"
```

Or run directly without installation:

```bash
python3 -m whisker server --name "Home Base"
```

To run multiple servers from the same directory without database clashes, specify different names and ports:

```bash
# Server instance 1 (creates whisker_server_home_base.db):
whisker server --name "Home Base" --port 3000

# Server instance 2 (creates whisker_server_work_office.db):
whisker server --name "Work Office" --port 3001
```

### 2. Connect the Android Mobile App

1. Open the **Whisker** app on Android.
2. Open **Settings** and enter your **Home Server Address** (e.g. `http://<your-server-ip>:3000`).
3. Copy the **Home Server Access Key** and **FCM Push Token** displayed in Settings to pair the app with your server.

---

## AI Agent & CLI Integration (`whisker`)

The `whisker` CLI enables AI harnesses (such as OpenClaw, AutoGPT, or LangChain agents), headless Linux servers, and automated scripts to pair with host devices, send messages, initiate WebRTC audio/video calls, and stream real-time events.

Each client connection to a home server is isolated in its own dedicated database file (`whisker_client_<username>_<servername>.db`). A CLI client connects to one home server per database, allowing multiple users and multiple servers to run side-by-side in the same directory without cross-talk or state files.

### Client Workflows & Automatic Resolution:

- **Automatic Single-DB Resolution**: When only a single client database exists in the working directory, operations (`send`, `call`, `listen`, `info`) automatically use it without requiring parameters.
- **Disambiguation Rules**:
  - If a single username is registered across multiple servers, specify `-s <server_name>`.
  - If multiple usernames are registered on a single server, specify `-u <username>`.
  - Both parameters (`-u / --username` and `-s / --server`) can always be specified explicitly.

- **Claim an Invite & Pair with Host**:
  ```bash
  # Pair with invite (creates whisker_client_<user>_<server>.db automatically):
  whisker init "http://<server-ip>:3000/invite/INVITE123"

  # Pair specifying username and server name:
  whisker init "http://<server-ip>:3000/invite/INVITE123" --name "Alice" --server "Family Server"
  ```
- **List Local Client Databases**:
  ```bash
  # View all local client databases in the directory:
  whisker clients
  # Or: whisker list
  ```
- **Send a Message**:
  ```bash
  # Send message (auto-resolves DB if single or unambiguous):
  whisker send "Hello from OpenClaw AI agent! Event processing complete."

  # Specify username and/or home server:
  whisker send -u "Alice" -s "Family Server" "Hello! Status update."

  # Target specific remote host by UUID or name:
  whisker send -u "Alice" -c "Office Tablet" "Alert: High CPU temperature detected."
  ```
- **Initiate a WebRTC Audio / Video Call**:
  ```bash
  # Initiate audio+video call:
  whisker call

  # Initiate call specifying username and server:
  whisker call -u "Alice" -s "Family Server"

  # Stream Linux camera (V4L2 device) & audio (ALSA/Pulse) during call:
  whisker call -u "Alice" --video /dev/video0 --audio default

  # Save incoming and outgoing media streams to disk (<caller>.<callee>.<unixepoch>.mp4):
  whisker call -u "Alice" --save-streams
  ```
- **Stream Events (SSE Listener) & Auto-Answer Calls**:
  ```bash
  # Listen for events & auto-answer calls:
  whisker listen

  # Listen specifying username and server:
  whisker listen -u "Alice" -s "Family Server"

  # Listen across all local client databases simultaneously:
  whisker listen --all

  # Listen with camera/mic configured for incoming calls:
  whisker listen -u "Alice" --video /dev/video0 --audio default --save-streams
  ```
- **Client Configuration & Server Status**:
  ```bash
  whisker info
  whisker info -u "Alice" -s "Family Server"
  ```
- **Delete Client Database**:
  ```bash
  whisker remove -u "Alice" -s "Family Server"
  ```
- **CLI WebRTC Calling & Media Features**:
  - **Full Audio & Video Sessions**: Every call establishes a complete bidirectional audio and video session. If hardware sources are omitted, standard fallback tracks (48 kHz silence audio and dynamic video placeholder card) ensure full reciprocal mobile camera streaming.
  - **Aspect-Ratio-Preserved Video Window**: Resizable GUI window (`480x720` portrait default) dynamically scales incoming portrait and landscape camera feeds while preserving their exact aspect ratio without distortion.
  - **Stream Recording (`--save-streams` / Headless)**: Pass `--save-streams` to record the synchronized audio and video stream to `<caller>.<callee>.<unixepoch>.mp4`. When run in headless environments (without an active display server), calls automatically record to disk.
  - **Robust Remote Hangup Handling**: Multi-layered hangup detection (SSE events, active call status polling, ICE state tracking) guarantees the CLI immediately returns to the shell when the mobile app hangs up.
- **Start Home Server with Custom DB / Port / Name**:
  ```bash
  whisker server --name "Home Base" --port 3000 --db /path/to/custom_server.db
  ```
- **Show Built-In Help**:
  ```bash
  whisker help
  ```

---

## FCM Push Setup (`whisker setup-fcm`)

To enable instant push notifications without draining your mobile battery, Whisker uses Google Cloud Platform (GCP) and Firebase Cloud Messaging (FCM) v1.

### Prerequisites (One-Time Setup)

Before running the FCM setup command, complete these two one-time steps in your Google account:

1. **Accept Google Cloud Terms of Service**:
   - Log into [console.cloud.google.com](https://console.cloud.google.com) and accept the Terms of Service if prompted.
2. **Initialize Firebase Profile**:
   - Log into [console.firebase.google.com](https://console.firebase.google.com).
   - Create at least **one** sample project (e.g. `my-first-app`) and accept the Firebase Terms of Service. This initializes your Google account's Firebase developer profile.

### Automated Setup

Run the automated provisioning tool:

```bash
whisker setup-fcm
# Or: python3 -m whisker setup-fcm
```

#### What `whisker setup-fcm` Does Automatically:

- **Browser OAuth2 Authentication**: Authenticates with your Google account.
- **Smart Project Management**: Reuses an existing `"Whisker FCM"` project (`whisker-push-sa-XXXXXX`) or creates a new one if none exists.
- **Batch API Enablement**: Enables `iam.googleapis.com`, `firebase.googleapis.com`, `fcm.googleapis.com`, `cloudmessaging.googleapis.com`, `apikeys.googleapis.com`, and `cloudresourcemanager.googleapis.com`.
- **Firebase & App Provisioning**: Links Firebase to the GCP project and creates the default Android app (`com.whisker`).
- **Service Account & IAM Binding**: Creates `whisker-push@<PROJECT_ID>.iam.gserviceaccount.com` and grants the necessary admin and usage roles.
- **Credentials Export**: Automatically downloads and saves `whisker-push-sa.json` containing the service account key, project number (Sender ID), API key, and App ID.

---

## Documentation & Technical Details

For complete technical specifications, database schemas, REST API documentation, and security protocols, refer to:

- **[requirements.md](requirements.md)** — Comprehensive Whisker System Requirements Specification.

---

## Disclaimer

This codebase was put together with the assistance of Google AI Studio.
