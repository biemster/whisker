# Whisker Home Server & Mobile App

> **Real-Time Messaging, WebRTC P2P Calls & AI Automation System**

Whisker is a fully self-hosted communication system that lets you run your own private service where callers and visitors can reach you instantly from any web browser without creating an account or installing an app. As the host, you receive messages and live audio/video calls directly on your Android phone through the Whisker mobile app.

Whether you want a hassle-free personal communication system for external visitors, an effortless IP camera solution, or need to connect AI agents (like **OpenClaw**, **AutoGPT**, or custom autonomous harnesses) to real-world messaging and calling workflows, Whisker provides a flexible, battery-friendly solution.

---

## Key Highlights

- **Fully Self-Hosted**: Retain complete ownership of your data, messages, and media by running your server on your own hardware or private cloud instance.
- **Zero-App Visitor Access**: Anyone can message or call you simply by opening your Home Server URL in a web browser—no user accounts or app downloads required.
- **Direct Mobile Alerting & WebRTC Calls**: Receive live audio/video calls, text messages, and visitor notifications directly on your Android phone with low-latency peer-to-peer WebRTC streaming.
- **Responsive & Interactive Web Client**: Features active call video layout expansion, an interactive system status indicator with detailed connectivity breakdown modal, instant messaging thread, and local media fallback controls.
- **IP Camera & Hardware Repurposing**: Turn obsolete Android or mobile devices into dedicated IP cameras or home monitors by installing the Whisker app and enabling auto-answer for incoming calls.
- **AI Harness & Business Automation Ready**: Includes `whisker`, a scriptable CLI client and Python package engineered for AI agents, headless services, and automated workflow integrations.
- **Ultra Battery-Friendly**: Eliminates battery-draining background connections or constant polling loops on your mobile phone. Your phone wakes up on-demand strictly when an FCM push alert arrives.
- **Instant Invite Links & Visitor Approval**: Share single-use invite links or approve visitor access requests with a single tap.
- **Lightweight & Zero-Config**: Runs on a modular Python Home Server (`whisker/server.py`) backed by SQLite with minimal setup effort.

---

## Project Roadmap

### Current State (Operational)
- **Architecture**: Single Home Server paired with Android Mobile Application, Web Visitor Portal, and CLI clients.
- **Messaging**: Real-time bidirectional text chat with automatic historical message chain sync (`previous_message_id`).
- **WebRTC Calling**: Full peer-to-peer audio and video calling operational with adaptive camera/mic permissions, candidate signaling, and synthetic fallback stream generation.
- **Web Client UI**: Responsive fluid layout with auto-expanding video on active calls, interactive header status indicator with connectivity modal, and mobile-optimized columnar view.
- **Push Notifications**: FCM HTTP v1 push notification tickle integration complete with low-power battery optimization.

### In Development & Future Roadmap
- **iOS Mobile Application**: Native iOS client app built with Swift / SwiftUI, featuring WebRTC P2P calling, background APNs (Apple Push Notification service) push tickle support, and call notification alerts.
- **Multi-Device Pairing**: Support for multiple mobile applications paired with a single home server, enabling paired mobile devices to call and message each other (ideal for family, small office, or team environments).
- **Multi-Server Mobile Client**: Support for connecting a single mobile application to multiple home servers simultaneously, featuring independent controls to disconnect or mute specific servers (e.g. managing family, work, and community servers in one app).
- **Cross-Server Calling**: Direct audio/video calling between mobile applications registered across different home servers.

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

Install the package via `pip` or run directly with Python 3:

```bash
pip install .
whisker server
```

Or run directly without installation:

```bash
python3 -m whisker server
```

The server listens on `http://localhost:3000` (or your configured port via `--port`) and initializes the SQLite database (`whisker_server.db`).

### 2. Connect the Android Mobile App

1. Open the **Whisker** app on Android.
2. Open **Settings** and enter your **Home Server Address** (e.g. `http://<your-server-ip>:8085`).
3. Copy the **Home Server Access Key** and **FCM Push Token** displayed in Settings to pair the app with your server.

---

## AI Agent & CLI Integration (`whisker`)

The `whisker` CLI enables AI harnesses (such as OpenClaw, AutoGPT, or LangChain agents) and automated scripts to send messages, initiate calls, or listen for events.

### CLI Workflows:

- **Claim an Invite Code**:
  ```bash
  whisker init "http://<server-ip>:3000/invite/INVITE123"
  # Or: python3 -m whisker init "http://<server-ip>:3000/invite/INVITE123"
  ```
- **Send a Message (AI / Script Output)**:
  ```bash
  whisker send "Hello from OpenClaw AI agent! Event processing complete."
  ```
- **Initiate a Call**:
  ```bash
  whisker call
  ```
- **Stream Events (SSE Listener)**:
  ```bash
  whisker listen
  ```
- **Check Connectivity & System Info**:
  ```bash
  whisker info
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
