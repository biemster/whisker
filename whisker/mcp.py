"""
Whisker Model Context Protocol (MCP) Server
Implements JSON-RPC 2.0 stdio transport with zero external dependencies.
Exposes tools, resources, and prompts for AI agents (Claude Desktop, Cursor, OpenClaw, etc.)
to pair with Whisker home servers, send messages, inspect status, and initiate WebRTC calls.
"""

import sys
import os
import json
import sqlite3
import urllib.request
import urllib.parse
import threading
import time
import traceback
import socket

if __package__ is None or __package__ == '':
    sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from whisker.cli import (
    sanitize_name,
    format_client_db_name,
    read_client_db_info,
    get_all_client_dbs,
    resolve_client_db,
    save_client_credentials
)
from whisker.crypto import (
    load_or_create_client_key,
    derive_shared_secret,
    encrypt_payload,
    decrypt_payload,
    save_secret_key,
    get_secret_filename
)

MCP_PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "whisker-mcp"
SERVER_VERSION = "0.1.0"


def log_debug(msg: str):
    """Logs diagnostics to stderr only so stdout remains pristine for JSON-RPC."""
    sys.stderr.write(f"[whisker-mcp] {msg}\n")
    sys.stderr.flush()


# --- Tool Definitions ---

TOOLS = [
    {
        "name": "whisker_list_clients",
        "description": "Lists all locally paired Whisker client database profiles, including usernames, paired server names, host device info, and database files.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "directory": {
                    "type": "string",
                    "description": "Directory to inspect for client databases (defaults to current directory '.')"
                }
            }
        }
    },
    {
        "name": "whisker_init",
        "description": "Pairs an AI agent client profile with a Whisker Home Server and host mobile device using an invite URL or invite code.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "invite_url": {
                    "type": "string",
                    "description": "Invite URL provided by the Whisker host (e.g. http://192.168.1.50:3000/invite/INVITE123 or invite code)"
                },
                "username": {
                    "type": "string",
                    "description": "Display name or username for this AI agent client (e.g. 'Claude', 'Agent_01')"
                },
                "server_name": {
                    "type": "string",
                    "description": "Optional custom alias for the home server"
                },
                "db_file": {
                    "type": "string",
                    "description": "Optional custom path for the SQLite database file"
                }
            },
            "required": ["invite_url"]
        }
    },
    {
        "name": "whisker_info",
        "description": "Inspects client configuration and queries the Home Server for live status (direct host connection, mobile app reachability, push alerts, and paired contacts).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "username": {
                    "type": "string",
                    "description": "Client username to inspect (optional if only one database exists)"
                },
                "server_name": {
                    "type": "string",
                    "description": "Home server name to inspect (optional if unambiguous)"
                },
                "db_file": {
                    "type": "string",
                    "description": "Direct path to client database file"
                }
            }
        }
    },
    {
        "name": "whisker_send_message",
        "description": "Sends a real-time chat message or alert to the paired mobile phone host. Triggers immediate FCM push alerting on the mobile device if backgrounded.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "message": {
                    "type": "string",
                    "description": "Text content of the message to send"
                },
                "contact": {
                    "type": "string",
                    "description": "Target specific host contact UUID or name (optional, defaults to paired host)"
                },
                "username": {
                    "type": "string",
                    "description": "Client username to send as (optional if unambiguous)"
                },
                "server_name": {
                    "type": "string",
                    "description": "Home server name to connect to (optional if unambiguous)"
                },
                "db_file": {
                    "type": "string",
                    "description": "Direct path to client database file"
                }
            },
            "required": ["message"]
        }
    },
    {
        "name": "whisker_get_messages",
        "description": "Fetches recent message history between this client and the host mobile app from the Home Server.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of messages to retrieve (default: 50)"
                },
                "username": {
                    "type": "string",
                    "description": "Client username"
                },
                "server_name": {
                    "type": "string",
                    "description": "Home server name"
                },
                "contact": {
                    "type": "string",
                    "description": "Filter messages by contact host UUID"
                },
                "db_file": {
                    "type": "string",
                    "description": "Direct path to client database file"
                }
            }
        }
    },
    {
        "name": "whisker_wait_new_messages",
        "description": "Blocks on the Home Server's SSE event stream (POST /api/host with JSON body action=events) until a new message from the host mobile device arrives (sender='phone') or timeout is reached. Returns only new, deduplicated host messages.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "username": {
                    "type": "string",
                    "description": "Client username"
                },
                "server_name": {
                    "type": "string",
                    "description": "Home server name"
                },
                "contact": {
                    "type": "string",
                    "description": "Filter messages by host contact UUID"
                },
                "db_file": {
                    "type": "string",
                    "description": "Direct path to client database file"
                },
                "timeout_seconds": {
                    "type": "integer",
                    "description": "Maximum seconds to wait on the SSE event stream before returning (default: 60)"
                },
                "known_message_ids": {
                    "type": "array",
                    "items": { "type": "string" },
                    "description": "Optional list of message IDs already seen by the caller for strict deduplication"
                }
            }
        }
    },
    {
        "name": "whisker_poll_events",
        "description": "Polls the Home Server for pending events, approval status, and connectivity updates for a client profile.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "username": {
                    "type": "string",
                    "description": "Client username"
                },
                "server_name": {
                    "type": "string",
                    "description": "Home server name"
                },
                "db_file": {
                    "type": "string",
                    "description": "Direct path to client database file"
                }
            }
        }
    },
    {
        "name": "whisker_initiate_call",
        "description": "Initiates a WebRTC audio/video call to the host mobile device through the Home Server.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "contact": {
                    "type": "string",
                    "description": "Target specific host contact UUID or name"
                },
                "username": {
                    "type": "string",
                    "description": "Client username to call as"
                },
                "server_name": {
                    "type": "string",
                    "description": "Home server name to connect to"
                },
                "video_source": {
                    "type": "string",
                    "description": "Optional video input source (e.g. /dev/video0 or video file)"
                },
                "audio_source": {
                    "type": "string",
                    "description": "Optional audio input source (e.g. default, pulse)"
                },
                "save_streams": {
                    "type": "boolean",
                    "description": "Whether to record call streams to an MP4 file on disk"
                },
                "db_file": {
                    "type": "string",
                    "description": "Direct path to client database file"
                }
            }
        }
    },
    {
        "name": "whisker_remove_client",
        "description": "Deletes a local client database profile file, disconnecting from that home server pairing.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "username": {
                    "type": "string",
                    "description": "Client username to delete"
                },
                "server_name": {
                    "type": "string",
                    "description": "Home server name to delete"
                },
                "db_file": {
                    "type": "string",
                    "description": "Direct path to client database file to remove"
                }
            }
        }
    },
    {
        "name": "whisker_server_status",
        "description": "Directly queries a Whisker Home Server URL to verify network reachability, API health, and paired device availability.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "server_url": {
                    "type": "string",
                    "description": "Home server URL (e.g. http://192.168.1.50:3000)"
                }
            },
            "required": ["server_url"]
        }
    }
]

# --- Resource Definitions ---

RESOURCES = [
    {
        "uri": "whisker://clients",
        "name": "Local Whisker Client Profiles",
        "description": "JSON array of all local client databases, usernames, and paired server addresses in the current working directory",
        "mimeType": "application/json"
    },
    {
        "uri": "whisker://status",
        "name": "Whisker Connection Status",
        "description": "Live configuration and status of the primary or resolved Whisker client connection and home server",
        "mimeType": "application/json"
    },
    {
        "uri": "whisker://messages",
        "name": "Recent Whisker Messages",
        "description": "Recent messages exchanged between the active client profile and the mobile host",
        "mimeType": "application/json"
    }
]

# --- Prompt Definitions ---

PROMPTS = [
    {
        "name": "send_alert",
        "description": "Compose and send a high-priority alert notification to the host user's Android phone via Whisker",
        "arguments": [
            {
                "name": "topic",
                "description": "Topic or title of the alert (e.g. High CPU Load, Backup Failed)",
                "required": True
            },
            {
                "name": "details",
                "description": "Details, diagnostic info, or recommended action",
                "required": False
            }
        ]
    },
    {
        "name": "check_status",
        "description": "Check whether the Whisker Home Server is healthy and verify if the host mobile phone is online and reachable",
        "arguments": []
    }
]


# --- Tool Implementation Handlers ---

def handle_list_clients(args: dict) -> dict:
    directory = args.get('directory') or '.'
    dbs = get_all_client_dbs(directory)
    return {
        "count": len(dbs),
        "clients": dbs
    }


def handle_init(args: dict) -> dict:
    invite_url = args.get('invite_url', '').strip()
    username = args.get('username')
    server_name = args.get('server_name')
    custom_db = args.get('db_file')

    if not invite_url:
        raise ValueError("invite_url is required")

    if '/invite/' in invite_url:
        parts = invite_url.split('/invite/')
        server_url = parts[0].rstrip('/')
        code = parts[1].split('?')[0].split('#')[0].strip()
    elif '?code=' in invite_url:
        parts = invite_url.split('?code=')
        server_url = parts[0].rstrip('/')
        code = parts[1].split('&')[0].strip()
    elif '?invite=' in invite_url:
        parts = invite_url.split('?invite=')
        server_url = parts[0].rstrip('/')
        code = parts[1].split('&')[0].strip()
    else:
        raise ValueError("Invalid invite URL format. Expected: http://server:3000/invite/CODE")

    temp_user = (username or '').strip() or 'mcp_agent'
    temp_server = (server_name or '').strip() or 'server'
    c_priv, c_pub = load_or_create_client_key(temp_user, temp_server)

    req_payload = {"action": "claim_invite", "code": code, "client_type": "MCP", "public_key": c_pub}
    if username and str(username).strip():
        req_payload["name"] = str(username).strip()

    req_data = json.dumps(req_payload).encode('utf-8')
    req = urllib.request.Request(
        f"{server_url}/api/public",
        data=req_data,
        headers={'Content-Type': 'application/json'}
    )
    with urllib.request.urlopen(req, timeout=10) as response:
        res_data = json.loads(response.read().decode('utf-8'))
        if 'error' in res_data:
            raise RuntimeError(res_data['error'])

        uuid = res_data.get('uuid') or c_pub
        token = res_data.get('token')
        final_user = (username or '').strip() or res_data.get('name') or 'MCP Agent'
        host_uuid = res_data.get('host_uuid', '')
        host_name = res_data.get('host_name', 'Primary Device')
        final_server = (server_name or '').strip() or res_data.get('server_name') or res_data.get('servername')

        if not final_server:
            try:
                with urllib.request.urlopen(f"{server_url}/api/public", timeout=4) as info_resp:
                    info_data = json.loads(info_resp.read().decode('utf-8'))
                    final_server = info_data.get('server_name') or info_data.get('servername')
            except Exception:
                pass
        final_server = final_server or 'server'

        if not uuid or not token:
            raise RuntimeError("Invalid response from server: missing uuid or token.")

        sec_fn = get_secret_filename(final_user, final_server)
        save_secret_key(sec_fn, c_priv)

        chosen_db = custom_db or format_client_db_name(final_user, final_server)
        save_client_credentials(chosen_db, {
            'username': final_user,
            'name': final_user,
            'server_name': final_server,
            'server_url': server_url,
            'uuid': uuid,
            'token': token,
            'host_uuid': host_uuid,
            'host_name': host_name,
            'created_at': int(time.time() * 1000)
        })

        return {
            "status": "PAIRED",
            "username": final_user,
            "server_name": final_server,
            "server_url": server_url,
            "database_file": chosen_db,
            "client_uuid": uuid,
            "host_name": host_name,
            "host_uuid": host_uuid,
            "secret_file": sec_fn
        }


def handle_info(args: dict) -> dict:
    target = resolve_client_db(
        username=args.get('username'),
        server_name=args.get('server_name'),
        custom_db=args.get('db_file')
    )

    server_url = target['server_url']
    req = urllib.request.Request(f"{server_url}/api/public")
    server_info = {}
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            server_info = json.loads(response.read().decode('utf-8'))
    except Exception as e:
        server_info = {"error": f"Failed to contact server: {e}", "online": False}

    return {
        "client": {
            "username": target['username'],
            "server_name": target['server_name'],
            "server_url": server_url,
            "client_uuid": target['uuid'],
            "host_name": target.get('host_name', 'Primary Device'),
            "host_uuid": target.get('host_uuid', ''),
            "database_file": target.get('file', '')
        },
        "home_server": server_info
    }


def handle_send_message(args: dict) -> dict:
    msg = args.get('message', '').strip()
    if not msg:
        raise ValueError("message is required")

    target = resolve_client_db(
        username=args.get('username'),
        server_name=args.get('server_name'),
        custom_db=args.get('db_file')
    )

    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    client_display_name = target['username']
    host_name = target.get('host_name') or 'Host'
    host_uuid = args.get('contact') or target.get('host_uuid') or ''

    # E2EE: Encrypt outbound message if host_uuid is present
    out_text = msg
    is_enc = False
    if host_uuid:
        try:
            c_priv, _ = load_or_create_client_key(client_display_name, target['server_name'])
            shared = derive_shared_secret(c_priv, host_uuid)
            out_text = encrypt_payload(shared, msg)
            is_enc = True
        except Exception:
            is_enc = False

    payload = {
        "action": "message",
        "uuid": uuid,
        "token": token,
        "text": out_text,
        "is_encrypted": is_enc,
        "name": client_display_name
    }
    if host_uuid:
        payload["contact"] = host_uuid
        payload["host_uuid"] = host_uuid

    req_data = json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(
        f"{server_url}/api/host",
        data=req_data,
        headers={'Content-Type': 'application/json'}
    )
    with urllib.request.urlopen(req, timeout=10) as response:
        res_data = json.loads(response.read().decode('utf-8'))
        if 'error' in res_data:
            raise RuntimeError(res_data['error'])

        updated_hname = res_data.get('host_name')
        if updated_hname and updated_hname != host_name:
            host_name = updated_hname
            db_p = target.get('filepath') or target.get('file')
            if db_p and os.path.exists(db_p):
                save_client_credentials(db_p, {'host_name': host_name})

        return {
            "status": "SENT",
            "message": msg,
            "is_encrypted": is_enc,
            "sender": client_display_name,
            "server": target['server_name'],
            "recipient_host": host_name,
            "response": res_data
        }


def handle_get_messages(args: dict) -> dict:
    target = resolve_client_db(
        username=args.get('username'),
        server_name=args.get('server_name'),
        custom_db=args.get('db_file')
    )

    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    limit = int(args.get('limit', 50))

    query_params = {
        'action': 'messages',
        'uuid': uuid,
        'token': token,
        'limit': str(limit)
    }
    if args.get('contact'):
        query_params['contact'] = args['contact']

    url = f"{server_url}/api/host"
    req_data = json.dumps(query_params).encode('utf-8')
    req = urllib.request.Request(url, data=req_data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=8) as response:
        res_data = json.loads(response.read().decode('utf-8'))
        if isinstance(res_data, list):
            messages = res_data
        elif isinstance(res_data, dict):
            messages = res_data.get('messages', [])
        else:
            messages = []

        # Decrypt on demand
        try:
            c_priv, _ = load_or_create_client_key(target['username'], target['server_name'])
        except Exception:
            c_priv = None

        decrypted_messages = []
        for m in messages:
            m_copy = dict(m)
            raw_t = m_copy.get('text', '')
            is_enc_msg = m_copy.get('is_encrypted') or m_copy.get('isEncrypted')
            if is_enc_msg and raw_t and c_priv:
                try:
                    peer_k = m_copy.get('uuid') or m_copy.get('host_uuid') or target.get('host_uuid')
                    if peer_k:
                        shared = derive_shared_secret(c_priv, peer_k)
                        m_copy['text'] = decrypt_payload(shared, raw_t)
                except Exception as err:
                    m_copy['text'] = f"[Encrypted message could not be decrypted: {err}]"
            decrypted_messages.append(m_copy)

        return {
            "count": len(decrypted_messages),
            "messages": decrypted_messages,
            "client_uuid": uuid,
            "server_name": target['server_name']
        }


def handle_wait_new_messages(args: dict) -> dict:
    target = resolve_client_db(
        username=args.get('username'),
        server_name=args.get('server_name'),
        custom_db=args.get('db_file')
    )
    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    timeout_seconds = int(args.get('timeout_seconds', 60))
    contact_filter = args.get('contact')
    known_ids = set(args.get('known_message_ids') or [])

    def fetch_current_messages(limit=50):
        qp = {'action': 'messages', 'uuid': uuid, 'token': token, 'limit': str(limit)}
        if contact_filter:
            qp['contact'] = contact_filter
        u = f"{server_url}/api/host"
        req_data = json.dumps(qp).encode('utf-8')
        req = urllib.request.Request(u, data=req_data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            if isinstance(data, list):
                return data
            elif isinstance(data, dict):
                return data.get('messages', [])
            return []

    # Filter messages according to correctness rules:
    # 1. sender must be 'phone' (messages from mobile host)
    # 2. ignore agent's own messages (sender == 'guest' or 'client')
    # 3. message ID not in seen/known set
    def filter_new_host_messages(msgs, seen_set):
        new_msgs = []
        try:
            c_priv, _ = load_or_create_client_key(target['username'], target['server_name'])
        except Exception:
            c_priv = None

        for m in msgs:
            sender = (m.get('sender') or '').strip().lower()
            m_id = m.get('id')
            if sender == 'phone' and m_id not in seen_set:
                m_copy = dict(m)
                is_enc = m_copy.get('is_encrypted') or m_copy.get('isEncrypted')
                if is_enc and c_priv and m_copy.get('text'):
                    try:
                        peer_k = m_copy.get('uuid') or m_copy.get('host_uuid') or target.get('host_uuid')
                        if peer_k:
                            shared = derive_shared_secret(c_priv, peer_k)
                            m_copy['text'] = decrypt_payload(shared, m_copy['text'])
                    except Exception as err:
                        m_copy['text'] = f"[Encrypted message could not be decrypted: {err}]"
                new_msgs.append(m_copy)
        return new_msgs

    # Seed initial seen IDs from existing history if none provided
    initial_msgs = fetch_current_messages(limit=100)
    seen_ids = set(known_ids)
    if not known_ids:
        # First-run seed: mark existing message IDs as seen
        for m in initial_msgs:
            if m.get('id'):
                seen_ids.add(m['id'])
    else:
        # Catch-up check: if caller provided known_ids, check if any host messages arrived in the meantime
        pending = filter_new_host_messages(initial_msgs, seen_ids)
        if pending:
            return {
                "count": len(pending),
                "messages": pending,
                "client_uuid": uuid,
                "server_name": target['server_name'],
                "timed_out": False
            }

    # Block on SSE event stream (/api/host with action=events)
    sse_url = f"{server_url}/api/host"
    sse_body = json.dumps({'action': 'events', 'uuid': uuid, 'token': token}).encode('utf-8')
    deadline = time.time() + max(1, timeout_seconds)
    reconnect_delay = 2

    while time.time() < deadline:
        remaining_time = max(1.0, deadline - time.time())
        # Set socket timeout safely: at least 30s or remaining_time + 5s
        sock_timeout = max(30.0, remaining_time + 5.0)
        req = urllib.request.Request(sse_url, data=sse_body, headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
        try:
            with urllib.request.urlopen(req, timeout=sock_timeout) as resp:
                for raw_line in resp:
                    if time.time() >= deadline:
                        break
                    line = raw_line.decode('utf-8', 'replace').strip()
                    if not line.startswith("data:"):
                        continue
                    # Any data event: verify against /api/host messages and filter sender=='phone'
                    cur_msgs = fetch_current_messages(limit=50)
                    new_host = filter_new_host_messages(cur_msgs, seen_ids)
                    if new_host:
                        return {
                            "count": len(new_host),
                            "messages": new_host,
                            "client_uuid": uuid,
                            "server_name": target['server_name'],
                            "timed_out": False
                        }
        except (TimeoutError, socket.timeout):
            # Normal socket timeout check against overall deadline
            if time.time() >= deadline:
                break
        except Exception:
            # Reconnect with small backoff if time permits
            if time.time() + reconnect_delay >= deadline:
                break
            time.sleep(reconnect_delay)

    # Final check before timeout return
    try:
        final_msgs = fetch_current_messages(limit=50)
        final_new = filter_new_host_messages(final_msgs, seen_ids)
        if final_new:
            return {
                "count": len(final_new),
                "messages": final_new,
                "client_uuid": uuid,
                "server_name": target['server_name'],
                "timed_out": False
            }
    except Exception:
        pass

    return {
        "count": 0,
        "messages": [],
        "client_uuid": uuid,
        "server_name": target['server_name'],
        "timed_out": True
    }


def handle_poll_events(args: dict) -> dict:
    target = resolve_client_db(
        username=args.get('username'),
        server_name=args.get('server_name'),
        custom_db=args.get('db_file')
    )
    server_url = target['server_url']
    uuid = target['uuid']

    url = f"{server_url}/api/host"
    req_body = json.dumps({'action': 'sync', 'uuid': uuid, 'token': target.get('token', '')}).encode('utf-8')
    req = urllib.request.Request(url, data=req_body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as response:
        session_data = json.loads(response.read().decode('utf-8'))
        return {
            "client": target['username'],
            "server": target['server_name'],
            "session": session_data
        }


def handle_initiate_call(args: dict) -> dict:
    target = resolve_client_db(
        username=args.get('username'),
        server_name=args.get('server_name'),
        custom_db=args.get('db_file')
    )

    try:
        from whisker.webrtc_manager import WhiskerWebRTCSession, AIORTC_AVAILABLE
    except Exception:
        WhiskerWebRTCSession = None
        AIORTC_AVAILABLE = False

    if not AIORTC_AVAILABLE or WhiskerWebRTCSession is None:
        return {
            "status": "UNAVAILABLE",
            "message": "WebRTC calling requires aiortc, pillow, and av. Install them via: pip install aiortc pillow av",
            "client": target['username'],
            "server": target['server_name']
        }

    # Start call in background thread
    def call_runner():
        from whisker.cli import do_call
        try:
            do_call(
                contact=args.get('contact'),
                username=target['username'],
                server_name=target['server_name'],
                video_source=args.get('video_source'),
                audio_source=args.get('audio_source'),
                save_streams=args.get('save_streams', False),
                custom_db=target.get('filepath') or target.get('file')
            )
        except Exception as e:
            log_debug(f"Background call error: {e}")

    th = threading.Thread(target=call_runner, daemon=True)
    th.start()

    return {
        "status": "INITIATING",
        "message": f"WebRTC call initiated to {target.get('host_name', 'Host')} via {target['server_name']}.",
        "client": target['username'],
        "server": target['server_name'],
        "host_name": target.get('host_name', 'Host')
    }


def handle_remove_client(args: dict) -> dict:
    target = resolve_client_db(
        username=args.get('username'),
        server_name=args.get('server_name'),
        custom_db=args.get('db_file')
    )
    db_file = target.get('filepath') or target.get('file')
    if os.path.exists(db_file):
        os.remove(db_file)
        return {
            "status": "REMOVED",
            "database_file": db_file,
            "username": target['username'],
            "server_name": target['server_name']
        }
    else:
        return {
            "status": "NOT_FOUND",
            "database_file": db_file
        }


def handle_server_status(args: dict) -> dict:
    server_url = args.get('server_url', '').rstrip('/')
    if not server_url:
        raise ValueError("server_url is required")
    if not (server_url.startswith('http://') or server_url.startswith('https://')):
        server_url = f"http://{server_url}"

    req = urllib.request.Request(f"{server_url}/api/public")
    with urllib.request.urlopen(req, timeout=5) as response:
        data = json.loads(response.read().decode('utf-8'))
        return {
            "status": "ONLINE",
            "server_url": server_url,
            "info": data
        }


TOOL_HANDLERS = {
    "whisker_list_clients": handle_list_clients,
    "whisker_init": handle_init,
    "whisker_info": handle_info,
    "whisker_send_message": handle_send_message,
    "whisker_get_messages": handle_get_messages,
    "whisker_wait_new_messages": handle_wait_new_messages,
    "whisker_poll_events": handle_poll_events,
    "whisker_initiate_call": handle_initiate_call,
    "whisker_remove_client": handle_remove_client,
    "whisker_server_status": handle_server_status
}


# --- JSON-RPC Dispatcher ---

class MCPServer:
    def __init__(self, directory='.'):
        self.directory = directory
        if directory and os.path.exists(directory):
            try:
                os.chdir(directory)
            except Exception:
                pass

    def handle_request(self, req: dict) -> dict:
        req_id = req.get("id")
        method = req.get("method")
        params = req.get("params") or {}

        if method == "initialize":
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "protocolVersion": MCP_PROTOCOL_VERSION,
                    "capabilities": {
                        "tools": {"listChanged": False},
                        "resources": {"subscribe": False, "listChanged": False},
                        "prompts": {"listChanged": False}
                    },
                    "serverInfo": {
                        "name": SERVER_NAME,
                        "version": SERVER_VERSION
                    }
                }
            }

        elif method == "notifications/initialized":
            # Client notification acknowledging initialization
            return None

        elif method == "ping":
            return {"jsonrpc": "2.0", "id": req_id, "result": {}}

        elif method == "tools/list":
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "tools": TOOLS
                }
            }

        elif method == "tools/call":
            tool_name = params.get("name")
            tool_args = params.get("arguments") or {}
            handler = TOOL_HANDLERS.get(tool_name)

            if not handler:
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32601,
                        "message": f"Tool '{tool_name}' not found"
                    }
                }

            try:
                result_data = handler(tool_args)
                text_content = json.dumps(result_data, indent=2)
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [
                            {
                                "type": "text",
                                "text": text_content
                            }
                        ],
                        "isError": False
                    }
                }
            except Exception as e:
                log_debug(f"Error in tool '{tool_name}': {traceback.format_exc()}")
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [
                            {
                                "type": "text",
                                "text": f"Error executing tool '{tool_name}': {str(e)}"
                            }
                        ],
                        "isError": True
                    }
                }

        elif method == "resources/list":
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "resources": RESOURCES
                }
            }

        elif method == "resources/read":
            uri = params.get("uri", "")
            if uri == "whisker://clients":
                dbs = get_all_client_dbs('.')
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "contents": [
                            {
                                "uri": uri,
                                "mimeType": "application/json",
                                "text": json.dumps(dbs, indent=2)
                            }
                        ]
                    }
                }
            elif uri == "whisker://status":
                try:
                    status_info = handle_info({})
                    text = json.dumps(status_info, indent=2)
                except Exception as e:
                    text = json.dumps({"error": str(e)}, indent=2)
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "contents": [
                            {
                                "uri": uri,
                                "mimeType": "application/json",
                                "text": text
                            }
                        ]
                    }
                }
            elif uri == "whisker://messages":
                try:
                    msg_info = handle_get_messages({"limit": 50})
                    text = json.dumps(msg_info, indent=2)
                except Exception as e:
                    text = json.dumps({"error": str(e)}, indent=2)
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "contents": [
                            {
                                "uri": uri,
                                "mimeType": "application/json",
                                "text": text
                            }
                        ]
                    }
                }
            else:
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32602,
                        "message": f"Resource URI '{uri}' not supported"
                    }
                }

        elif method == "prompts/list":
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "prompts": PROMPTS
                }
            }

        elif method == "prompts/get":
            prompt_name = params.get("name")
            p_args = params.get("arguments") or {}

            if prompt_name == "send_alert":
                topic = p_args.get("topic", "System Alert")
                details = p_args.get("details", "")
                prompt_text = f"You are tasked with notifying the host user via Whisker.\nTopic: {topic}\nDetails: {details}\nUse the 'whisker_send_message' tool to send this alert to their phone."
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "description": "Send high-priority alert to host device",
                        "messages": [
                            {
                                "role": "user",
                                "content": {
                                    "type": "text",
                                    "text": prompt_text
                                }
                            }
                        ]
                    }
                }
            elif prompt_name == "check_status":
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "description": "Verify Whisker home server and host reachability",
                        "messages": [
                            {
                                "role": "user",
                                "content": {
                                    "type": "text",
                                    "text": "Call 'whisker_info' to verify if the Home Server is responding and if the host mobile device is currently online and reachable."
                                }
                            }
                        ]
                    }
                }
            else:
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32601,
                        "message": f"Prompt '{prompt_name}' not found"
                    }
                }

        else:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {
                    "code": -32601,
                    "message": f"Method '{method}' not recognized"
                }
            }

    def run(self):
        log_debug(f"Whisker MCP Server starting on stdio (PID: {os.getpid()})")
        while True:
            try:
                line = sys.stdin.readline()
                if not line:
                    break
                line_str = line.strip()
                if not line_str:
                    continue

                req = json.loads(line_str)
                resp = self.handle_request(req)
                if resp is not None:
                    out = json.dumps(resp) + "\n"
                    sys.stdout.write(out)
                    sys.stdout.flush()
            except json.JSONDecodeError as e:
                err_resp = {
                    "jsonrpc": "2.0",
                    "id": None,
                    "error": {
                        "code": -32700,
                        "message": f"Parse error: {str(e)}"
                    }
                }
                sys.stdout.write(json.dumps(err_resp) + "\n")
                sys.stdout.flush()
            except Exception as e:
                log_debug(f"Unhandled server error: {traceback.format_exc()}")


def run_mcp_server(directory='.'):
    server = MCPServer(directory=directory)
    server.run()


if __name__ == '__main__':
    run_mcp_server()
