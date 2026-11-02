# Whisker AI Agent Skills & Model Context Protocol (MCP) Guide

This document specifies the capabilities, tool interfaces, and operational guidelines for AI agents (such as Claude Desktop, Cursor, OpenClaw, AutoGPT, LangChain, or custom LLM harnesses) integrating with the **Whisker Home Server & Mobile Alerting Ecosystem** via the native **Model Context Protocol (MCP)** server or CLI.

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

### 6. `whisker_initiate_call`
Initiates a WebRTC audio/video call to the host mobile device.
- **Inputs**:
  - `contact` *(optional, string)*: Target host contact UUID.
  - `username` *(optional, string)*: Client username.
  - `server_name` *(optional, string)*: Home server name.
  - `video_source` *(optional, string)*: Linux video device (e.g. `/dev/video0`) or video file path.
  - `audio_source` *(optional, string)*: Microphone source (e.g. `default`, `pulse`) or audio file path.
  - `save_streams` *(optional, boolean)*: Record incoming and outgoing media streams to MP4 on disk.
- **Returns**: Call initiation confirmation with target host name and server status.

### 7. `whisker_poll_events`
Polls the Home Server for pending events, approval status, and connectivity updates.
- **Inputs**:
  - `username` *(optional, string)*: Client username.
  - `server_name` *(optional, string)*: Home server name.
- **Returns**: Active session status and host connection state.

### 8. `whisker_server_status`
Directly verifies connectivity, API health, and registered endpoints of a Whisker Home Server URL.
- **Inputs**:
  - `server_url` *(required, string)*: Full URL of the Whisker server (e.g. `http://192.168.1.50:3000`).
- **Returns**: Server metadata, registered mobile hosts count, and push configuration status.

### 9. `whisker_remove_client`
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
