# Whisker AI Agent Skills & Model Context Protocol (MCP) Guide

> ⚠️ **CRITICAL DIRECTIVE FOR AI AGENTS (Claude, Cursor, DSH, OpenClaw, AutoGPT, etc.)**:
> **DO NOT USE THE BASH/TERMINAL CLI IF MCP TOOLS ARE AVAILABLE.**
> If you have access to tools starting with `whisker_` (such as `whisker_send_message`, `whisker_get_messages`, `whisker_list_clients`, `whisker_poll_events`), **ALWAYS invoke the native MCP tools directly**. Do NOT execute shell commands like `whisker send ...` or `whisker list` unless explicitly instructed to run a shell command by the user. Using MCP tools directly ensures structured JSON input/output, proper session management, and eliminates shell escaping or terminal hang issues.

---

## 1. Overview

Whisker allows AI agents running in headless servers, local workstations, or cloud containers to communicate directly with mobile app hosts (and their paired Android devices) without proprietary third-party messaging services.

Via the Whisker MCP server, an AI agent can:
- **Alert Mobile Users Instantly**: Send high-priority notifications that trigger real-time FCM push alerts on Android devices.
- **Bi-Directional Messaging**: Query message history, exchange live chat messages, and receive host responses.
- **Two-Way WebRTC Audio/Video**: Initiate WebRTC calls directly to the mobile device for voice updates, remote camera feeds, or live alerts.
- **Inspect Connectivity & Devices**: Check whether the home server is running, which mobile apps are paired, and whether the primary host is online.
- **Multi-Server & Multi-Device Isolation**: Manage distinct client profiles and server pairings seamlessly across dedicated database files (`whisker_client_<user>_<server>.db`).

---

## 2. Setting Up the Whisker MCP Server

The Whisker MCP Server runs over **standard input/output (`stdio`)** using JSON-RPC 2.0 and has **zero external dependencies** (runs on Python 3 standard library).

### Option A: Claude Desktop Configuration

Add the following to your `claude_desktop_config.json`:

**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`  
**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`  
**Linux**: `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "whisker": {
      "command": "python3",
      "args": [
        "-m",
        "whisker.mcp"
      ],
      "cwd": "/path/to/your/whisker/directory"
    }
  }
}
```

*Or if installed via `pip install .`:*

```json
{
  "mcpServers": {
    "whisker": {
      "command": "whisker-mcp",
      "cwd": "/path/to/your/whisker/directory"
    }
  }
}
```

### Option B: Cursor Configuration

In Cursor's **Features > MCP Servers** or in `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "whisker": {
      "command": "whisker",
      "args": ["mcp"],
      "cwd": "/path/to/your/whisker/directory"
    }
  }
}
```

### Option C: Standalone CLI Launch

```bash
# Run via CLI subcommand:
whisker mcp

# Or run via Python module:
python3 -m whisker.mcp

# Or run with explicit working directory:
whisker mcp --directory /path/to/whisker/dbs
```

---

## 3. Tool Reference

The Whisker MCP Server exposes 9 comprehensive tools covering all CLI functions:

### 1. `whisker_list_clients`
Lists all local client databases and paired profiles in the working directory.
- **Inputs**:
  - `directory` *(optional, string)*: Directory to scan (defaults to `"."`).
- **Returns**: Count and list of client profiles with `username`, `server_name`, `server_url`, `host_name`, `host_uuid`, and `file`.
- **Usage Guideline**: Always invoke this tool first if you do not know which home server or username profile to use.

### 2. `whisker_init`
Pairs the AI agent with a Whisker Home Server and host mobile device using an invite URL.
- **Inputs**:
  - `invite_url` *(required, string)*: The single-use invite URL (e.g. `http://<server-ip>:3000/invite/CODE`).
  - `username` *(optional, string)*: Name for this client profile (e.g. `"OpenClaw AI"`, `"Claude"`).
  - `server_name` *(optional, string)*: Custom alias for the home server.
  - `db_file` *(optional, string)*: Custom SQLite database file path.
- **Returns**: Pairing confirmation with `client_uuid`, `server_name`, `host_name`, and database filename.

### 3. `whisker_info`
Inspects client configuration and queries the Home Server for live status.
- **Inputs**:
  - `username` *(optional, string)*: Filter by client username.
  - `server_name` *(optional, string)*: Filter by server name.
  - `db_file` *(optional, string)*: Direct path to client DB file.
- **Returns**: Client configuration and Home Server live health status (`host_connected`, `push_configured`, `host_reachable`).

### 4. `whisker_send_message`
Sends a real-time text message or alert to the paired mobile phone host.
- **Inputs**:
  - `message` *(required, string)*: Content of the message.
  - `contact` *(optional, string)*: Target specific host contact UUID or name.
  - `username` *(optional, string)*: Client username.
  - `server_name` *(optional, string)*: Home server name.
  - `db_file` *(optional, string)*: Custom database path.
- **Returns**: Delivery status, timestamp, recipient host name, and server confirmation.
- **Usage Guideline**: If the mobile phone is backgrounded or locked, this automatically triggers a low-power Google FCM push notification waking up the device.

### 5. `whisker_get_messages`
Retrieves recent message history between the agent and the mobile host.
- **Inputs**:
  - `limit` *(optional, integer, default: 50)*: Maximum messages to retrieve.
  - `username` *(optional, string)*: Client username.
  - `server_name` *(optional, string)*: Home server name.
  - `contact` *(optional, string)*: Filter by host UUID.
- **Returns**: List of message objects (`id`, `sender`, `text`, `timestamp`).

### 6. `whisker_wait_new_messages`
Blocks on the Home Server's SSE event stream until a new host message arrives (or timeout occurs). Implements server-side filtering for `sender == 'phone'`, deduplication against `known_message_ids`, and catch-up checks.
- **Inputs**:
  - `timeout_seconds` *(optional, integer, default: 60)*: Maximum wait duration.
  - `known_message_ids` *(optional, array of strings)*: Set of message IDs already seen.
  - `username` *(optional, string)*: Client username.
  - `server_name` *(optional, string)*: Home server name.
  - `contact` *(optional, string)*: Filter by host contact UUID.
  - `db_file` *(optional, string)*: Direct database path.
- **Returns**: `{ count: N, messages: [...], timed_out: bool }`.

### 7. `whisker_initiate_call`
Initiates a WebRTC audio/video call to the host mobile device.
- **Inputs**:
  - `contact` *(optional, string)*: Target host contact UUID.
  - `username` *(optional, string)*: Client username.
  - `server_name` *(optional, string)*: Home server name.
  - `video_source` *(optional, string)*: Linux video device (e.g. `/dev/video0`) or video file path.
  - `audio_source` *(optional, string)*: Microphone source (e.g. `default`, `pulse`) or audio file path.
  - `save_streams` *(optional, boolean)*: Record incoming and outgoing media streams to MP4 on disk.
- **Returns**: Call initiation confirmation with target host name and server status.

### 8. `whisker_poll_events`
Polls the Home Server for pending events, approval status, and connectivity updates.
- **Inputs**:
  - `username` *(optional, string)*: Client username.
  - `server_name` *(optional, string)*: Home server name.
- **Returns**: Active session status and host connection state.

### 9. `whisker_server_status`
Directly verifies connectivity, API health, and registered endpoints of a Whisker Home Server URL.
- **Inputs**:
  - `server_url` *(required, string)*: Full URL of the Whisker server (e.g. `http://192.168.1.50:3000`).
- **Returns**: Server metadata, registered mobile hosts count, and push configuration status.

### 10. `whisker_remove_client`
Deletes a local client database file, disconnecting the pairing.
- **Inputs**:
  - `username` *(optional, string)*: Client username.
  - `server_name` *(optional, string)*: Home server name.
  - `db_file` *(optional, string)*: Path to database file.
- **Returns**: Deletion confirmation.

---

## 4. MCP Resources

The server exposes read-only JSON resources:
- `whisker://clients`: Snapshot of all local client profiles in the working directory.
- `whisker://status`: Live connectivity breakdown for the active or resolved client.
- `whisker://messages`: The 50 most recent chat messages exchanged with the host.

---

## 5. MCP Prompt Templates

- `send_alert`:
  - Arguments: `topic` (required), `details` (optional).
  - Pre-formats an alert template for the agent to dispatch urgent notifications.
- `check_status`:
  - Pre-formats a diagnostic routine for checking server health and mobile host reachability.

---

## 6. Best Practices for AI Agents

1. **Automatic Database Resolution**:
   If only one client database exists in the working directory, you do not need to provide `username` or `server_name` in tool calls; Whisker auto-resolves the target database automatically.
2. **Disambiguation**:
   If the agent is paired with multiple home servers or uses multiple identities, specify `server_name` or `username` to target the exact profile.
3. **Emergency Alerting Flow**:
   When an agent detects a critical event (e.g. disk full, build failed, security alert):
   - First call `whisker_send_message` with a concise summary.
   - The Home Server sends an FCM tickle waking up the user's Android phone immediately.
4. **Non-blocking Execution**:
   All message and status checks complete in milliseconds. Calls run in asynchronous background tasks so the agent's reasoning loop is never blocked.

---

## 7. Receiving Messages via MCP & Polling Patterns

Unlike the CLI client which runs a continuous SSE stream (`whisker listen`), the **Whisker MCP server operates via request/response JSON-RPC**. This means the MCP server does not push unsolicited events into your context window.

To read incoming replies from the mobile host or user, agents must **poll or check for messages periodically** using `whisker_get_messages` or `whisker://messages`.

### Polling Pattern & Message Deduplication

When awaiting an answer from a mobile user, follow this pattern:

```python
# Polling algorithm for AI agents
last_seen_id = None
last_seen_timestamp = 0

def check_for_new_messages():
    # 1. Fetch recent messages
    result = whisker_get_messages(limit=20)
    messages = result.get("messages", [])
    
    # 2. Filter for incoming messages from the mobile host
    # Message objects contain: id, sender, text, timestamp, (optional attachments)
    new_messages = []
    for msg in messages:
        msg_id = msg.get("id")
        created_at = msg.get("timestamp") or msg.get("created_at", 0)
        
        # Check watermark
        if created_at > last_seen_timestamp and msg_id != last_seen_id:
            # Check sender: ignore messages sent by the agent itself
            if msg.get("sender") != "me" and msg.get("sender_type") != "CLIENT":
                new_messages.append(msg)
                
    if new_messages:
        # Update watermark to the newest message
        newest = max(new_messages, key=lambda m: m.get("timestamp", 0))
        last_seen_id = newest.get("id")
        last_seen_timestamp = newest.get("timestamp", 0)
        
    return new_messages
```

### Checking Mobile Host Online State (Optional)
To verify if the mobile phone host is currently connected/online before initiating actions, you can optionally call `whisker_poll_events` (which inspects `session.host_online`). Note that `whisker_poll_events` is **not required** on every iteration of a message polling loop—calling `whisker_get_messages` alone is sufficient to retrieve incoming messages. Furthermore, if the host is offline, sending a message via `whisker_send_message` automatically triggers a Google FCM notification to wake the mobile device.

### Handling File Attachments Received from Mobile
When a mobile user uploads files or photos to the agent, the message payload or download event points to `/api/file/download?path=<relative_path>&name=<filename>`.
The Home Server stores received files under:
`./uploads/<receiver>/<sender>/<YYYYMMDD.HHMMSS>/<filename>`
And the CLI client downloads files under:
`./downloads/<receiver>/<sender>/<YYYYMMDD.HHMMSS>/<filename>`

---

## 8. Promptly Answering Host Queries — Event-Driven Agent Wake-Up

While Section 7's polling loop (`whisker_get_messages` every N seconds) provides a reliable fallback, periodic polling **burns LLM context tokens** when an agent is running inside an autonomous harness (like DSH, Claude Desktop, Cursor, or OpenClaw).

To achieve **instant real-time replies with ZERO token cost while idle**, Whisker provides an **event-driven watcher mechanism** via `whisker-watch` and the MCP tool `whisker_wait_new_messages`.

### How It Works: The "Process Exit as Turn Trigger" Pattern

In modern AI agent harnesses, launching a background process (or sub-task) allows the agent to sleep. When the background job completes or exits, the harness receives a notification that triggers **exactly one agent reasoning turn** (like a new user message in chat).

```
   Mobile Phone (Host)                   Home Server                  whisker-watch                 AI Agent Harness
         |                                   |                              |                              |
         | --- POST /api/host/response ----> |                              |                              |
         |     (chat message from phone)     | --- SSE event (/api/events)->|                              |
         |                                   |                              | [Detects new host message]   |
         |                                   |                              | [Writes whisker.attn atomic] |
         |                                   |                              | === EXITS PROCESS (code 0)==>|
         |                                   |                              |                              | [Wakes agent session!]
         |                                   |                              |                              | Reads whisker.attn
         |                                   |                              |                              | Calls whisker_send_message
         | <-- FCM tickle & notification --- | <-- POST /api/message -------|                              | Deletes whisker.attn
         |                                   |                              |                              | Re-spawns whisker-watch &
```

1. A small background watcher (`whisker-watch`) blocks on the Home Server's SSE event stream (`/api/events`).
2. When a new message arrives from the host mobile device (`sender == 'phone'`), `whisker-watch`:
   - Validates the message against `/api/messages`.
   - Writes an atomic trigger file (`whisker.attn`) containing the incoming instructions and a clear runbook.
   - Appends to `whisker_inbox.log`.
   - **Immediately exits**.
3. The watcher exiting wakes the AI agent harness session for **one turn**.
4. The agent handles the instructions, replies to the host phone via `whisker_send_message`, deletes `whisker.attn`, and spawns `whisker-watch` in the background again.

### Core Correctness Rules

- **Sender Filtering (`sender == 'phone'`)**:
  In `/api/messages`, messages sent by the agent/client are labeled `sender="guest"` or `sender="client"`, while messages from the host phone are labeled `sender="phone"`. Only `sender="phone"` messages are actionable host instructions.
- **SSE Echo Verification**:
  Because the SSE stream can echo the client's own sent messages, `whisker_wait_new_messages` and `whisker-watch` never rely solely on raw SSE event payloads: upon receiving any event line, they verify against `/api/messages` and filter strictly for `sender == 'phone'`.
- **Deduplication & Watermark Tracking (`.whisker_watermark.json`)**:
  Message IDs are tracked in a persistent state file (`.whisker_watermark.json`).
- **Catch-Up on Startup**:
  If the watcher process was down or restarting when the phone sent a message, `whisker-watch` runs a catch-up check on startup. Any pending host messages not in the watermark state immediately write `whisker.attn` and exit. Only a first-ever run (with no state file) seeds existing history as seen.
- **Atomic Trigger Writes**:
  `whisker.attn` is written to a temporary file (`whisker.attn.tmp`) and moved via `os.replace` to prevent race conditions where an agent reads a partial file.

### Agent Runbook When Woken by `whisker.attn`

When your session is notified that `whisker-watch` finished:
1. **Read `whisker.attn`**: Inspect the incoming message text and any attachments.
2. **Execute instructions**: Perform the tasks requested by the mobile user.
3. **Reply via MCP tool**: Call `whisker_send_message` with your answer or status update.
4. **Delete trigger file**: Remove `whisker.attn` (`rm whisker.attn`).
5. **Restart watcher**: Relaunch `whisker-watch &` (or via your harness task runner) to resume sleeping until the next host message.

### CLI Options for `whisker-watch`
```bash
whisker-watch [--username USER] [--server SERVER] [--contact CONTACT] [--timeout SECONDS] [--attn-file PATH]
```
- `--username`, `-u`: Client profile username (auto-resolved if unambiguous).
- `--server`, `-s`: Server name.
- `--contact`, `-c`: Target host contact UUID or name.
- `--timeout`, `-t`: Maximum wait duration in seconds before cycling (default: 3600).
- `--attn-file`: Path to the atomic trigger file (default: `whisker.attn`).


