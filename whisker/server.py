import os
import sys
import json
import time
import random
import string
import urllib.parse
import threading
import re
import hashlib
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

import uuid
from whisker.db import DatabaseManager
from whisker.fcm import FCMManager, get_service_account_file

def sanitize_name(name: str) -> str:
    if not name:
        return 'default'
    clean = "".join(c if c.isalnum() else '_' for c in str(name).strip().lower())
    clean = re.sub(r'_+', '_', clean).strip('_')
    return clean or 'default'

def sanitize_filename(filename: str) -> str:
    if not filename:
        return f"file_{int(time.time())}.bin"
    base = os.path.basename(str(filename).replace('\\', '/')).strip()
    if not base:
        return f"file_{int(time.time())}.bin"
    name, ext = os.path.splitext(base)
    clean_name = re.sub(r'[^\w\-\.]', '_', name)
    clean_name = re.sub(r'_+', '_', clean_name).strip('_')
    if not clean_name:
        clean_name = "file"
    clean_ext = re.sub(r'[^\w\-\.]', '', ext)
    return f"{clean_name}{clean_ext}"

def save_uploaded_file(db, receiver_username, sender_username, original_filename, file_bytes):
    storage_root = (db.get_setting('files_storage_path') if db else None) or getattr(WhiskerServerHandler, 'files_storage_path', None) or '/data/files'
    if not os.path.isabs(storage_root) or not os.path.exists(storage_root):
        try:
            os.makedirs(storage_root, exist_ok=True)
        except Exception:
            storage_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'files'))
            os.makedirs(storage_root, exist_ok=True)

    rec_user = sanitize_name(receiver_username)
    snd_user = sanitize_name(sender_username)
    now_folder = time.strftime('%Y%m%d.%H%M%S')
    safe_filename = sanitize_filename(original_filename)

    target_dir = os.path.join(storage_root, rec_user, snd_user, now_folder)
    os.makedirs(target_dir, exist_ok=True)

    full_path = os.path.join(target_dir, safe_filename)
    with open(full_path, 'wb') as f:
        f.write(file_bytes)

    rel_path = f"{rec_user}/{snd_user}/{now_folder}/{safe_filename}"
    return full_path, rel_path, safe_filename, len(file_bytes)

def get_waiting_html():
    return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🐯 Whisker Setup</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <style>
        body {
            background-color: #0b0f19;
            color: #f3f4f6;
            font-family: system-ui, -apple-system, sans-serif;
        }
        .glow-cyan {
            box-shadow: 0 0 25px rgba(6, 182, 212, 0.4);
        }
        .pulse-ring {
            animation: pulse-glow 2s infinite;
        }
        @keyframes pulse-glow {
            0%, 100% { transform: scale(1); opacity: 0.6; }
            50% { transform: scale(1.1); opacity: 1; }
        }
    </style>
</head>
<body class="flex flex-col min-h-screen items-center justify-center p-4">
    <div class="max-w-md w-full bg-gray-900 border border-gray-800 p-8 rounded-3xl glow-cyan text-center space-y-6">
        <div class="relative w-20 h-20 mx-auto flex items-center justify-center">
            <div class="absolute inset-0 bg-cyan-500 rounded-full blur-xl opacity-30 pulse-ring"></div>
            <div class="relative w-16 h-16 rounded-2xl bg-cyan-500 flex items-center justify-center font-bold text-black text-3xl">
                🐯
            </div>
        </div>
        
        <div class="space-y-2">
            <h1 class="text-2xl font-black text-white tracking-tight">Whisker Home Server</h1>
            <p class="text-sm text-cyan-400 font-medium">Device Link Pending Setup</p>
        </div>

        <div class="p-4 rounded-2xl bg-gray-950 border border-gray-850 text-left space-y-3">
            <p class="text-xs text-gray-400 leading-relaxed">
                As long as the local database is not initialized, the web client is waiting for the mobile app to make its first connection.
            </p>
            <div class="border-t border-gray-800 pt-3 space-y-2">
                <span class="text-[10px] font-bold text-gray-400 uppercase tracking-wider">Quick Instructions:</span>
                <ol class="list-decimal list-inside text-xs text-gray-300 space-y-1.5 leading-normal">
                    <li>Launch the <b class="text-white">Whisker App</b> on your phone.</li>
                    <li>Go to the <span class="bg-gray-800 px-1 py-0.5 rounded">Setup / Cloud Configuration</span> panel.</li>
                    <li>Set the Home Server URL to:<br>
                        <code id="serverAddress" class="text-cyan-400 font-mono font-bold select-all break-all">http://localhost:3000</code>
                    </li>
                    <li>Choose a custom URL or use the detected Home Server URL.</li>
                    <li>Your private access key is automatically transmitted to pair!</li>
                </ol>
            </div>
        </div>

        <!-- Service Account Upload Card -->
        <div class="p-4 rounded-2xl bg-gray-950 border border-gray-800 text-left space-y-3">
            <div class="flex items-center justify-between">
                <div class="flex items-center space-x-2">
                    <span class="text-base">🔑</span>
                    <span class="text-xs font-bold text-gray-200 uppercase tracking-wider">Push Service Account</span>
                </div>
                <span id="saBadge" class="text-[10px] font-bold px-2 py-0.5 rounded-full bg-gray-800 text-gray-400">Checking...</span>
            </div>
            <p class="text-xs text-gray-400 leading-relaxed">
                Upload your Firebase Service Account JSON (<code class="text-cyan-400 font-mono">whisker-push-sa.json</code>) so this home server can wake sleeping mobile apps via FCM.
            </p>
            <div id="uploadDropzone" onclick="document.getElementById('saFileInput').click()" class="border border-dashed border-gray-700 hover:border-cyan-500 bg-gray-900/50 rounded-xl p-4 text-center cursor-pointer transition-colors space-y-1">
                <input type="file" id="saFileInput" accept=".json,application/json" class="hidden" />
                <div class="text-xl">📁</div>
                <div class="text-xs text-gray-300 font-medium" id="uploadLabel">Click or drop <span class="text-cyan-400 font-mono">whisker-push-sa.json</span> here</div>
                <div class="text-[10px] text-gray-500">JSON credentials will be stored alongside the home server</div>
            </div>
            <div id="uploadStatus" class="hidden text-xs py-2 px-3 rounded-lg font-mono"></div>
        </div>

        <div class="flex items-center justify-center space-x-2 text-xs text-gray-400 font-mono bg-gray-950/50 py-2 rounded-xl">
            <span class="w-2 h-2 rounded-full bg-yellow-500 animate-pulse"></span>
            <span>Waiting for first contact...</span>
        </div>
    </div>

    <script>
        document.getElementById('serverAddress').innerText = window.location.origin;

        async function checkStatus() {
            try {
                const res = await fetch('/api/public');
                const data = await res.json();
                if (data && data.initialized) {
                    window.location.reload();
                    return;
                }
                const badge = document.getElementById('saBadge');
                if (data && data.push_configured) {
                    badge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-950 text-emerald-400 border border-emerald-800';
                    badge.innerText = data.project_id ? `Active (${data.project_id})` : 'Active';
                } else {
                    badge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-amber-950/60 text-amber-400 border border-amber-800/50';
                    badge.innerText = 'Not Configured';
                }
            } catch (e) {}
        }

        const dropzone = document.getElementById('uploadDropzone');
        const fileInput = document.getElementById('saFileInput');
        const statusDiv = document.getElementById('uploadStatus');

        ['dragenter', 'dragover'].forEach(name => {
            dropzone.addEventListener(name, (e) => {
                e.preventDefault();
                dropzone.classList.add('border-cyan-400', 'bg-cyan-950/20');
            }, false);
        });

        ['dragleave', 'drop'].forEach(name => {
            dropzone.addEventListener(name, (e) => {
                e.preventDefault();
                dropzone.classList.remove('border-cyan-400', 'bg-cyan-950/20');
            }, false);
        });

        dropzone.addEventListener('drop', (e) => {
            const dt = e.dataTransfer;
            if (dt && dt.files && dt.files.length > 0) {
                handleFile(dt.files[0]);
            }
        });

        fileInput.addEventListener('change', (e) => {
            if (fileInput.files && fileInput.files.length > 0) {
                handleFile(fileInput.files[0]);
            }
        });

        async function handleFile(file) {
            statusDiv.className = 'text-xs py-2 px-3 rounded-lg font-mono bg-gray-800 text-gray-300 block';
            statusDiv.innerText = `Uploading ${file.name}...`;
            try {
                const text = await file.text();
                let parsed;
                try {
                    parsed = JSON.parse(text);
                } catch (pe) {
                    throw new Error("Selected file is not valid JSON.");
                }
                if (!parsed.project_id) {
                    throw new Error("Service account JSON must contain 'project_id'.");
                }
                const res = await fetch('/api/host', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ action: 'upload_sa', ...parsed })
                });
                const result = await res.json();
                if (res.ok && result.success) {
                    statusDiv.className = 'text-xs py-2 px-3 rounded-lg font-mono bg-emerald-950/80 text-emerald-300 border border-emerald-800 block';
                    statusDiv.innerText = `✅ Service account configured (${result.project_id || 'saved'})`;
                    checkStatus();
                } else {
                    throw new Error(result.error || 'Failed to upload service account');
                }
            } catch (err) {
                statusDiv.className = 'text-xs py-2 px-3 rounded-lg font-mono bg-rose-950/80 text-rose-300 border border-rose-800 block';
                statusDiv.innerText = `❌ ${err.message}`;
            }
        }

        checkStatus();
        setInterval(checkStatus, 3000);
    </script>
</body>
</html>"""

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

class WhiskerServerHandler(BaseHTTPRequestHandler):
    db: DatabaseManager = None
    fcm: FCMManager = None
    host_streams = {}
    guest_streams = {}  # uuid -> set of wfile objects
    host_last_seen = {}
    guest_last_seen = {}
    active_calls = {}  # call_id -> {call_id, host_uuid, client_uuid, caller_name, status, created_at, updated_at, is_direct, video, initiator}
    streams_lock = threading.RLock()
    calls_lock = threading.RLock()
    admin_tokens = {}  # token -> expiration timestamp
    admin_password = None

    @classmethod
    def _prune_stale_calls(cls):
        now = time.time()
        with cls.calls_lock:
            expired_ids = []
            for cid, c in list(cls.active_calls.items()):
                st = c.get('status', 'RINGING')
                created = c.get('created_at', now)
                updated = c.get('updated_at', now)
                # Ringing timeout: 45 seconds max
                if st == 'RINGING' and (now - created > 45):
                    c['status'] = 'ENDED'
                    c['reason'] = 'timeout'
                    expired_ids.append((cid, c))
                # Active timeout without update: 120 seconds max
                elif st == 'ACTIVE' and (now - updated > 120):
                    c['status'] = 'ENDED'
                    c['reason'] = 'stale'
                    expired_ids.append((cid, c))
                elif st == 'ENDED' and (now - updated > 15):
                    expired_ids.append((cid, None))

            for cid, call_data in expired_ids:
                if call_data is None:
                    cls.active_calls.pop(cid, None)
                else:
                    # Notify endpoints that call expired
                    cls._notify_call_ended(call_data)

    @classmethod
    def _notify_call_ended(cls, call_data):
        cid = call_data.get('call_id')
        host_uuid = call_data.get('host_uuid')
        client_uuid = call_data.get('client_uuid')
        reason = call_data.get('reason', 'ended')
        hangup_payload = {
            "event": "hangup",
            "type": "webrtc_call_hangup",
            "call_id": cid,
            "uuid": client_uuid,
            "status": "ended",
            "reason": reason
        }
        if host_uuid:
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            hangup_payload["eventId"] = event_id
            if cls.db:
                cls.db.add_host_event(host_uuid, event_id, "hangup", hangup_payload)
            msg = json.dumps({"type": "hangup", "payload": hangup_payload})
            msg_line = f"data: {msg}\n\n".encode('utf-8')
            with cls.streams_lock:
                for s in list(cls.host_streams.get(host_uuid, [])):
                    try:
                        s.write(msg_line)
                        s.flush()
                    except Exception:
                        pass
        if client_uuid:
            guest_msg = f"data: {json.dumps(hangup_payload)}\n\n".encode('utf-8')
            with cls.streams_lock:
                for s in list(cls.guest_streams.get(client_uuid, set())):
                    try:
                        s.write(guest_msg)
                        s.flush()
                    except Exception:
                        pass

    @classmethod
    def _register_call(cls, call_id, host_uuid, client_uuid, caller_name="Guest", initiator="guest", is_direct=False, video=True):
        cls._prune_stale_calls()
        now = time.time()
        with cls.calls_lock:
            # End any existing ringing/active call for this client or host
            for cid, c in list(cls.active_calls.items()):
                if (c.get('client_uuid') == client_uuid or (c.get('host_uuid') == host_uuid and c.get('client_uuid') == client_uuid)) and c.get('status') in ['RINGING', 'ACTIVE']:
                    c['status'] = 'ENDED'
                    c['updated_at'] = now
                    c['reason'] = 'replaced'

            cls.active_calls[call_id] = {
                "call_id": call_id,
                "host_uuid": host_uuid,
                "client_uuid": client_uuid,
                "caller_name": caller_name,
                "status": "RINGING",
                "initiator": initiator,
                "is_direct": is_direct,
                "video": video,
                "created_at": now,
                "updated_at": now
            }
        return cls.active_calls[call_id]

    @classmethod
    def _update_call_status(cls, call_id, new_status, client_uuid=None, host_uuid=None):
        now = time.time()
        with cls.calls_lock:
            target = None
            if call_id and call_id in cls.active_calls:
                target = cls.active_calls[call_id]
            else:
                for cid, c in cls.active_calls.items():
                    if (client_uuid and c.get('client_uuid') == client_uuid) or (host_uuid and c.get('host_uuid') == host_uuid):
                        target = c
                        break
            if target:
                target['status'] = new_status
                target['updated_at'] = now
                return target
        return None

    @classmethod
    def _get_call_session(cls, call_id=None, uuid=None):
        cls._prune_stale_calls()
        with cls.calls_lock:
            if call_id and call_id in cls.active_calls:
                return dict(cls.active_calls[call_id])
            if uuid:
                for cid, c in cls.active_calls.items():
                    if (c.get('client_uuid') == uuid or c.get('host_uuid') == uuid) and c.get('status') in ['RINGING', 'ACTIVE']:
                        return dict(c)
        return None

    def _send_cors_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Push-Token, X-Admin-Token, X-Filename, X-Receiver, X-Client-Uuid, X-Client-Token')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')

    def do_OPTIONS(self):
        self.send_response(200)
        self._send_cors_headers()
        self.end_headers()

    def _read_raw_body(self):
        if hasattr(self, '_cached_raw_body') and self._cached_raw_body is not None:
            return self._cached_raw_body
        content_len = int(self.headers.get('Content-Length', 0))
        if content_len > 0:
            self._cached_raw_body = self.rfile.read(content_len)
        else:
            self._cached_raw_body = b''
        return self._cached_raw_body

    def _read_json_body(self):
        body_bytes = self._read_raw_body()
        if body_bytes:
            try:
                return json.loads(body_bytes.decode('utf-8'))
            except Exception:
                return {}
        return {}

    def _send_json(self, data, status=200):
        body = json.dumps(data).encode('utf-8')
        self.send_response(status)
        self._send_cors_headers()
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, html_str, status=200):
        body = html_str.encode('utf-8')
        self.send_response(status)
        self._send_cors_headers()
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _get_auth_token(self, body=None):
        auth = self.headers.get('Authorization', '')
        if auth.startswith('Bearer '):
            return auth[7:].strip()
        if body and isinstance(body, dict):
            b_token = body.get('token') or body.get('client_token') or body.get('pin_token')
            if b_token and str(b_token).strip():
                return str(b_token).strip()
        return auth.strip()

    def _is_initialized(self):
        hosts = self.db.get_hosts()
        return len(hosts) > 0 or bool(self.db.get_setting('host_pin_token'))

    def _get_server_url(self):
        proto = 'https' if self.headers.get('X-Forwarded-Proto') == 'https' else 'http'
        host_hdr = self.headers.get('Host') or f"localhost:{self.server.server_port}"
        return f"{proto}://{host_hdr}"

    def _extract_clean_push_token(self, body):
        token = body.get('push_token') or body.get('pushToken') or body.get('token') or body.get('fcmToken') or self.headers.get('X-Push-Token', '')
        if token and str(token).strip():
            t = str(token).strip()
            if not t.startswith('Fetching') and not t.startswith('device-push-token-') and not t.startswith('mock-') and t not in ('undefined', 'null'):
                return t
        return None

    def _send_fcm_tickle(self, push_token, event_id=None, event_type="event", extra_status=None, username=None, server_address=None, server_name=None):
        if not username and push_token and self.db:
            hosts = self.db.get_hosts()
            for h in hosts:
                if h.get('push_token') == push_token:
                    username = h.get('username') or f"Mobile Device ({h['uuid'][:6]})"
                    break
        if not server_address:
            try:
                server_address = self._get_server_url()
            except Exception:
                server_address = None
        if not server_name and self.db:
            server_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
        if push_token and self.fcm:
            try:
                self.fcm.send_tickle_notification(push_token, event_id=event_id, event_type=event_type, extra_status=extra_status, username=username, server_address=server_address, server_name=server_name)
            except Exception as e:
                print(f"[Whisker Server] FCM tickle notification error: {e}")

    def _get_fcm_config_and_hash(self):
        sa = get_service_account_file()
        fcm_key = self.db.get_setting('fcm_api_key') or "" if self.db else ""
        s_name = (self.db.get_setting('server_name') if self.db else None) or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
        if sa:
            cfg = {
                "configured": True,
                "project_id": sa.get("project_id", ""),
                "sender_id": sa.get("project_number") or sa.get("project_id", ""),
                "app_id": sa.get("app_id", ""),
                "api_key": sa.get("api_key") or fcm_key,
                "server_name": s_name
            }
        else:
            cfg = {
                "configured": False,
                "error": "whisker-push-sa.json not found on Home Server",
                "server_name": s_name
            }
        cfg_str = json.dumps(cfg, sort_keys=True)
        h = hashlib.sha256(cfg_str.encode('utf-8')).hexdigest()[:16]
        cfg["hash"] = h
        return cfg, h

    def _authorize_host(self):
        token = self._get_auth_token()
        if not token:
            self._send_json({"error": "Access Key required", "status": "UNAUTHORIZED"}, 401)
            return None
        hosts = self.db.get_hosts()
        for h in hosts:
            if h['pin_token'] == token:
                if h['status'] == 'APPROVED':
                    WhiskerServerHandler.host_last_seen[h['uuid']] = time.time()
                    return h
                elif h['status'] == 'PENDING':
                    WhiskerServerHandler.host_last_seen[h['uuid']] = time.time()
                    self._send_json({"error": "Pairing request pending approval", "status": "PENDING"}, 401)
                    return None
                elif h['status'] == 'REJECTED':
                    self._send_json({"error": "Pairing request was rejected", "status": "REJECTED"}, 403)
                    return None
        
        self._send_json({"error": "Unauthorized access key", "status": "UNREGISTERED"}, 401)
        return None

    def _authorize_guest(self, uuid, token):
        if not uuid:
            self._send_json({"error": "Missing uuid"}, 403)
            return None

        WhiskerServerHandler.guest_last_seen[uuid] = time.time()

        req = self.db.get_request(uuid)
        if not req:
            self._send_json({"error": "No visitor registration request found"}, 403)
            return None

        if req.get('status') != 'APPROVED':
            self._send_json({"error": "Visitor request is still pending approval"}, 403)
            return None

        if req.get('token') != token:
            self._send_json({"error": "Invalid registration token"}, 403)
            return None

        return req

    def _resolve_target_host(self, client_uuid, contact_uuid=None):
        """
        Resolves the target host for a client message/call.
        1. Checks client_host_approvals for client_uuid.
        2. If contact_uuid is given, tries to find that host in approved_hosts or all hosts.
        3. If no approved_hosts mapped yet, auto-links with admin / first available approved host.
        4. Returns the host dict or None.
        """
        approved_hosts = self.db.get_approved_hosts_for_client(client_uuid)
        req = self.db.get_request(client_uuid)
        target_uuid = (contact_uuid or (req.get('target_host_uuid') if req else None))

        if contact_uuid and approved_hosts:
            cid_str = str(contact_uuid).strip()
            target = next((h for h in approved_hosts if h['uuid'] == cid_str or (h.get('username') and h.get('username').lower() == cid_str.lower()) or (h.get('device_name') and h.get('device_name').lower() == cid_str.lower())), None)
            if target:
                return target

        if req and req.get('target_host_uuid') and approved_hosts:
            target = next((h for h in approved_hosts if h['uuid'] == req['target_host_uuid']), None)
            if target:
                return target

        if approved_hosts:
            return approved_hosts[0]

        if target_uuid:
            th = self.db.get_host(str(target_uuid).strip())
            if th and th.get('status') == 'APPROVED':
                return th

        return None

    def _get_index_html(self):
        possible_paths = [
            os.path.join(os.path.dirname(__file__), 'index.html'),
            os.path.join(os.path.dirname(__file__), '..', 'index.html'),
            'index.html'
        ]
        for p in possible_paths:
            if os.path.isfile(p):
                try:
                    with open(p, 'r', encoding='utf-8') as f:
                        return f.read()
                except Exception:
                    pass
        return "<h1>Whisker Home Server</h1><p>index.html not found.</p>"

    def _get_admin_html(self):
        possible_paths = [
            os.path.join(os.path.dirname(__file__), 'admin.html'),
            os.path.join(os.path.dirname(__file__), '..', 'admin.html'),
            'admin.html'
        ]
        for p in possible_paths:
            if os.path.isfile(p):
                try:
                    with open(p, 'r', encoding='utf-8') as f:
                        return f.read()
                except Exception:
                    pass
        return "<h1>Whisker Home Server Admin</h1><p>admin.html not found.</p>"

    def _is_admin_authenticated(self, qs=None):
        auth_hdr = self.headers.get('Authorization', '')
        token = None
        if auth_hdr.startswith('Bearer '):
            token = auth_hdr[7:].strip()
        if not token:
            token = self.headers.get('X-Admin-Token', '').strip()
        if not token and qs and 'token' in qs:
            token = qs['token'][0].strip()
        if not token:
            cookies = self.headers.get('Cookie', '')
            for part in cookies.split(';'):
                if '=' in part:
                    k, v = part.strip().split('=', 1)
                    if k == 'whisker_admin_token':
                        token = v.strip()
                        break
        if not token:
            return False

        now = time.time()
        with WhiskerServerHandler.streams_lock:
            for t, exp in list(WhiskerServerHandler.admin_tokens.items()):
                if exp < now:
                    WhiskerServerHandler.admin_tokens.pop(t, None)
            return token in WhiskerServerHandler.admin_tokens

    def _get_public_payload(self):
        s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
        all_hosts = self.db.get_hosts()
        admin_host = self.db.get_admin_host()
        sa = get_service_account_file()
        push_configured = any(
            h.get('push_token') and not h['push_token'].startswith('device-push-token-') and not h['push_token'].startswith('mock-')
            for h in all_hosts
        ) or bool(sa)
        with WhiskerServerHandler.streams_lock:
            host_conn = any(len(stms) > 0 for stms in WhiskerServerHandler.host_streams.values())
        direct_hosts = [
            {
                "uuid": h['uuid'],
                "username": h.get('username') or 'Mobile App',
                "is_admin": h.get('is_admin', 0)
            }
            for h in all_hosts
            if h.get('allow_direct_calls', 0) == 1 and h.get('status') == 'APPROVED'
        ]
        available_hosts = [
            {
                "uuid": h['uuid'],
                "username": h.get('username') or 'Mobile App',
                "allow_widget": h.get('allow_widget', 1),
                "is_admin": h.get('is_admin', 0),
                "role": "ADMIN" if (h.get("is_admin", 0) == 1) else "COMPANION"
            }
            for h in all_hosts
            if h.get('status') == 'APPROVED' and h.get('allow_widget', 1) == 1
        ]
        return {
            "status": "ok",
            "initialized": self._is_initialized(),
            "server_name": s_name,
            "servername": s_name,
            "admin_host": admin_host.get("username") if admin_host else None,
            "hosts_count": len(all_hosts),
            "push_configured": push_configured,
            "project_id": sa.get("project_id") if sa else None,
            "host_connected": host_conn,
            "host_reachable": host_conn or push_configured,
            "allow_direct_calls": len(direct_hosts) > 0,
            "direct_call_hosts": direct_hosts,
            "available_hosts": available_hosts,
            "server_uuid": getattr(WhiskerServerHandler, 'server_uuid', None) or (self.db.get_setting('server_pub_key') if self.db else None),
            "public_key": getattr(WhiskerServerHandler, 'server_pub_key', None) or (self.db.get_setting('server_pub_key') if self.db else None),
            "server_time": int(time.time() * 1000),
            "version": "0.1.0"
        }

    def _handle_host_sync_payload(self, host, caller_client, req_event_id=None, client_fcm_hash=None):
        s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
        server_addr = self._get_server_url()
        now = time.time()

        # 1. Identity ('me')
        if host:
            host_uuid = host["uuid"]
            WhiskerServerHandler.host_last_seen[host_uuid] = now
            is_adm = (host.get("is_admin", 0) == 1)
            me_data = {
                "uuid": host["uuid"],
                "username": host.get("username", ""),
                "is_admin": 1 if is_adm else 0,
                "role": "ADMIN" if is_adm else "COMPANION",
                "status": host.get("status", "APPROVED"),
                "allow_direct_calls": bool(host.get("allow_direct_calls", 0)),
                "allow_widget": bool(host.get("allow_widget", 1)),
                "server_name": s_name,
                "servername": s_name
            }
        else:
            host_uuid = caller_client["uuid"]
            WhiskerServerHandler.guest_last_seen[host_uuid] = now
            me_data = {
                "uuid": caller_client["uuid"],
                "username": caller_client.get("name", "Member"),
                "is_admin": 0,
                "role": "MEMBER",
                "status": "APPROVED",
                "server_name": s_name,
                "servername": s_name
            }

        # 2. Events ('events')
        if host:
            events = self.db.get_host_events(host["uuid"])
        else:
            events = self.db.get_guest_events(caller_client["uuid"]) or []

        if req_event_id and not any(e.get('id') == req_event_id for e in events):
            label = me_data.get('username') or host_uuid
            print(f"⚠️ [Whisker Server] FCM tickle sync: Event ID '{req_event_id}' not found in queue for '{label}'")

        # 3. Clients ('clients')
        with WhiskerServerHandler.streams_lock:
            active_uuids = {u for u, stms in WhiskerServerHandler.guest_streams.items() if stms}
            active_hosts = {u for u, stms in WhiskerServerHandler.host_streams.items() if stms}
        for u, ts in WhiskerServerHandler.guest_last_seen.items():
            if now - ts < 35:
                active_uuids.add(u)
        for u, ts in WhiskerServerHandler.host_last_seen.items():
            if now - ts < 35:
                active_hosts.add(u)

        clients = []
        if host:
            approved_clients = self.db.get_approved_clients_for_host(host_uuid)
            seen_uuids = set()
            for c in approved_clients:
                u = c.get('uuid')
                seen_uuids.add(u)
                c['is_online'] = 1 if u in active_uuids else 0
                c['server_name'] = s_name
                c['server_address'] = server_addr
                clients.append(c)

            # Include companion mobile apps
            is_admin = (host.get("is_admin", 0) == 1)
            all_hosts = self.db.get_hosts()
            for h in all_hosts:
                if h['uuid'] != host_uuid and h['uuid'] not in seen_uuids:
                    h_status = h.get('status', 'PENDING')
                    if is_admin or h_status == 'APPROVED':
                        h_online = 1 if (h['uuid'] in active_hosts or (now - WhiskerServerHandler.host_last_seen.get(h['uuid'], 0) < 35)) else 0
                        clients.append({
                            "uuid": h['uuid'],
                            "name": h.get('username') or f"Mobile Device ({h['uuid'][:6]})",
                            "reason": f"Companion Mobile App ({h.get('username', 'Mobile')})",
                            "req_status": f"MOBILE_{h_status}",
                            "token": h.get('pin_token', ''),
                            "created_at": h.get('created_at', int(time.time() * 1000)),
                            "is_online": h_online,
                            "is_mobile_host": True,
                            "client_type": "MOBILE",
                            "server_name": s_name,
                            "server_address": server_addr
                        })
                        seen_uuids.add(h['uuid'])

            if is_admin:
                pending_requests = [r for r in self.db.get_requests() if r.get('status') == 'PENDING']
            else:
                pending_requests = [r for r in self.db.get_requests() if r.get('status') == 'PENDING' and r.get('target_host_uuid') == host_uuid]

            for pr in pending_requests:
                if pr['uuid'] not in seen_uuids:
                    pr_copy = dict(pr)
                    pr_copy['is_online'] = 1 if pr['uuid'] in active_uuids else 0
                    pr_copy['server_name'] = s_name
                    pr_copy['server_address'] = server_addr
                    clients.append(pr_copy)
                    seen_uuids.add(pr['uuid'])
        else:
            hosts_for_member = self.db.get_approved_hosts_for_client(caller_client['uuid'])
            for h in hosts_for_member:
                h_online = 1 if (h['uuid'] in active_hosts or (now - WhiskerServerHandler.host_last_seen.get(h['uuid'], 0) < 35)) else 0
                clients.append({
                    "uuid": h['uuid'],
                    "name": h.get('username') or f"Mobile Device ({h['uuid'][:6]})",
                    "reason": f"Host ({h.get('username', 'Host')})",
                    "req_status": "APPROVED",
                    "token": "",
                    "created_at": h.get('created_at', int(time.time() * 1000)),
                    "is_online": h_online,
                    "is_mobile_host": True,
                    "client_type": "MOBILE",
                    "server_name": s_name,
                    "server_address": server_addr
                })

        # 4. ETag-cached FCM Configuration ('fcm')
        clean_fcm_hash = (client_fcm_hash or self.headers.get('If-None-Match', '')).strip('"')
        fcm_config, current_fcm_hash = self._get_fcm_config_and_hash()

        if clean_fcm_hash and clean_fcm_hash == current_fcm_hash:
            fcm_payload = {
                "hash": current_fcm_hash,
                "unchanged": True
            }
        else:
            fcm_payload = fcm_config

        invites = self.db.get_invites(host["uuid"]) if host else []
        return {
            "status": "ok",
            "me": me_data,
            "events": events,
            "clients": clients,
            "invites": invites,
            "fcm": fcm_payload,
            "server_time": int(now * 1000)
        }


    def _serve_file_download(self, rel_file, name=""):
        if not rel_file:
            return self._send_json({"error": "Missing file path"}, 400)
        storage_root = (self.db.get_setting('files_storage_path') if self.db else None) or getattr(WhiskerServerHandler, 'files_storage_path', None) or '/data/files'
        if not os.path.isabs(storage_root) or not os.path.exists(storage_root):
            storage_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'files'))
        clean_rel = str(rel_file).lstrip('/')
        full_path = os.path.abspath(os.path.join(storage_root, clean_rel))
        if not full_path.startswith(os.path.abspath(storage_root)) or not os.path.isfile(full_path):
            return self._send_json({"error": "File not found"}, 404)
        file_name = name or os.path.basename(full_path)
        try:
            with open(full_path, 'rb') as f:
                content = f.read()
            self.send_response(200)
            self._send_cors_headers()
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Disposition', f'attachment; filename="{file_name}"')
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except Exception as e:
            self._send_json({"error": f"Failed to read file: {e}"}, 500)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        clean_parts = [p for p in parsed.path.split('/') if p]
        path = '/' + '/'.join(clean_parts)

        # Hard error on ANY URL query parameters on /api/*
        if path.startswith('/api/'):
            if parsed.query:
                return self._send_json({
                    "error": "URL query parameters are strictly forbidden. All parameters must be sent in the JSON request body via POST."
                }, 400)

            # Public unauthenticated status endpoint: GET /api/public (with NO query string)
            if path == '/api/public':
                return self._send_json(self._get_public_payload())

            if path == '/api/host':
                return self._send_json({
                    "error": "Method Not Allowed. All host interactions must use POST with JSON request body."
                }, 405)

            # All other legacy /api/* routes are completely abolished (404 Not Found)
            return self._send_json({"error": "Endpoint not found"}, 404)

        # Admin Webpage
        if path in ['/admin', '/admin.html']:
            return self._send_html(self._get_admin_html())

        # Serve Embeddable Web Client Widget
        if path in ['/widget.js', '/embed.js']:
            js_path = os.path.join(os.path.dirname(__file__), 'widget.js')
            if os.path.exists(js_path):
                with open(js_path, 'r', encoding='utf-8') as f:
                    js_content = f.read()
            else:
                js_content = "// Whisker Widget\n"
            body = js_content.encode('utf-8')
            self.send_response(200)
            self._send_cors_headers()
            self.send_header('Content-Type', 'application/javascript; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-cache')
            self.end_headers()
            self.wfile.write(body)
            return

        if path in ['/widget-demo.html', '/embed-demo.html', '/widget-demo', '/embed-demo']:
            srv_url = self._get_server_url()
            demo_html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Whisker Widget Demo Site</title>
  <style>
    body {{
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      margin: 0;
      padding: 40px 20px;
      background: #0f172a;
      color: #f8fafc;
      min-height: 100vh;
      box-sizing: border-box;
      line-height: 1.6;
    }}
    .container {{
      max-width: 760px;
      margin: 0 auto;
      background: #1e293b;
      padding: 36px;
      border-radius: 16px;
      box-shadow: 0 10px 30px rgba(0,0,0,0.5);
      border: 1px solid #334155;
    }}
    h1 {{ color: #38bdf8; margin-top: 0; }}
    pre {{ background: #0f172a; padding: 14px; border-radius: 8px; overflow-x: auto; color: #a78bfa; font-size: 13px; }}
    .badge {{ display: inline-block; padding: 4px 10px; background: rgba(56, 189, 248, 0.15); color: #38bdf8; border-radius: 9999px; font-size: 12px; font-weight: bold; margin-bottom: 12px; }}
  </style>
</head>
<body>
  <div class="container">
    <span class="badge">🌐 Embed Widget Preview</span>
    <h1>Personal Webpage Example</h1>
    <p>This demo page illustrates how the <strong>Whisker Web Client Widget</strong> appears when embedded on any personal HTML page.</p>
    <p>Look at the bottom right corner (or bottom sheet on mobile) to interact with the widget.</p>
    <h3>One-line Integration Snippet</h3>
    <pre>&lt;script src="{srv_url}/widget.js"&gt;&lt;/script&gt;</pre>
  </div>
  <script src="/widget.js"></script>
</body>
</html>"""
            return self._send_html(demo_html)

        # Static Portal Router: / or /index.html or /invite/<code>
        if path in ['/', '/index.html'] or path.startswith('/invite/'):
            if not self._is_initialized():
                return self._send_html(get_waiting_html())

            html_str = self._get_index_html()
            if path.startswith('/invite/'):
                code = path.split('/invite/')[-1].strip()
                if code:
                    inject_script = f'<script>window.WHISKER_INVITE_CODE = "{code}";</script>\n'
                    html_str = html_str.replace('</head>', f'{inject_script}</head>')

            return self._send_html(html_str)

        return self._send_json({"error": "Endpoint not found"}, 404)

    def _handle_claim_invite(self, body):
        code = body.get('code')
        if not code:
            return self._send_json({"error": "Missing invite code"}, 400)

        invite, err = self.db.claim_invite(code)
        if err:
            return self._send_json({"error": err}, 400 if "already" in err else 404)

        existing_uuid = body.get('existing_uuid') or body.get('client_uuid') or body.get('uuid')
        existing_token = body.get('existing_token') or body.get('client_token') or body.get('token')
        push_token = body.get('push_token') or body.get('pushToken')

        existing_req = None
        if existing_uuid and existing_token:
            req = self.db.get_request(existing_uuid)
            if req and req.get('token') == existing_token and req.get('status') == 'APPROVED':
                existing_req = req

        if existing_req:
            client_uuid = existing_req['uuid']
            client_token = existing_req['token']
            client_name = (body.get('name') or '').strip() or existing_req.get('name') or invite.get('name', 'Invited Guest')
            client_type = body.get('client_type') or existing_req.get('client_type', 'MOBILE')
            if push_token:
                self.db.update_request_push_token(client_uuid, push_token)
        else:
            pub_key = (body.get('public_key') or body.get('pub_key') or '').strip()
            client_uuid = pub_key if pub_key else f"cli_{''.join(random.choices(string.ascii_lowercase + string.digits, k=9))}"
            client_token = f"tok_{''.join(random.choices(string.ascii_lowercase + string.digits, k=16))}"
            client_name = (body.get('name') or '').strip() or invite.get('name', 'Invited Guest')
            client_type = body.get('client_type')
            if not client_type:
                low_name = client_name.lower()
                if 'cli' in low_name:
                    client_type = 'CLI'
                elif 'mobile' in low_name or 'phone' in low_name or 'android' in low_name:
                    client_type = 'MOBILE'
                else:
                    client_type = 'WEB'

            self.db.add_request(client_uuid, client_name, f"Claimed invite code '{code}'", status='APPROVED', token=client_token, client_type=client_type)
            self.db.approve_request(client_uuid, client_token)
            if push_token:
                self.db.update_request_push_token(client_uuid, push_token)

        invite_host_uuid = invite.get('host_uuid')
        host = None
        if invite_host_uuid:
            host = self.db.get_host(invite_host_uuid)

        if not host:
            admin_host = self.db.get_admin_host()
            if admin_host:
                host = admin_host
                invite_host_uuid = host['uuid']
            elif self.db.get_hosts():
                host = self.db.get_hosts()[0]
                invite_host_uuid = host['uuid']

        host_name = (host.get('username') or 'Primary Device') if host else 'Primary Device'
        s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
        if invite_host_uuid:
            self.db.add_client_host_approval(client_uuid, invite_host_uuid)

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "invite_claimed",
                "eventId": event_id,
                "uuid": client_uuid,
                "name": client_name,
                "code": code,
                "token": client_token,
                "host_name": host_name,
                "host_uuid": invite_host_uuid,
                "client_type": client_type,
                "server_name": s_name,
                "server_address": self._get_server_url()
            }
            self.db.add_host_event(invite_host_uuid, event_id, "invite_claimed", payload)
            self._notify_host_streams(invite_host_uuid, "invite_claimed", payload)
            if host and host.get('push_token'):
                self._send_fcm_tickle(host['push_token'], event_id=event_id, event_type="invite_claimed", username=host.get('username'))

        print(f"🎟️ [Whisker Invite Claimed] Code: '{code}' | Guest: '{client_name}' ({client_uuid}, {client_type}) | Host: '{host_name}' ({invite_host_uuid})")

        return self._send_json({
            "success": True,
            "uuid": client_uuid,
            "token": client_token,
            "name": client_name,
            "host_uuid": invite_host_uuid,
            "host_name": host_name,
            "server_uuid": getattr(WhiskerServerHandler, 'server_uuid', None) or (self.db.get_setting('server_pub_key') if self.db else None),
            "server_public_key": getattr(WhiskerServerHandler, 'server_pub_key', None) or (self.db.get_setting('server_pub_key') if self.db else None),
            "status": "APPROVED",
            "client_type": client_type,
            "server_name": s_name,
            "servername": s_name
        })

    def _handle_register_visitor(self, body):
        uuid = body.get('uuid')
        name = body.get('name')
        reason = body.get('reason')
        client_type = body.get('client_type', 'WEB')
        target_host_uuid = body.get('target_host') or body.get('target_host_uuid')
        if not uuid or not name or not reason:
            return self._send_json({"error": "Missing required fields"}, 400)

        hosts = self.db.get_hosts()
        if client_type == 'EMBED':
            available_hosts = [h for h in hosts if h.get('status') == 'APPROVED' and h.get('allow_widget', 1) == 1]
        else:
            available_hosts = [h for h in hosts if h.get('status') == 'APPROVED']

        if not available_hosts:
            return self._send_json({"error": "No mobile apps are currently available to accept connections on this home server."}, 503)

        target_hosts = []
        if target_host_uuid:
            target_hosts = [h for h in available_hosts if h['uuid'] == target_host_uuid]
            if not target_hosts:
                return self._send_json({"error": "Selected mobile app is not available on this home server."}, 404)
        if not target_hosts:
            target_hosts = available_hosts

        self.db.add_request(uuid, name, reason, client_type=client_type, target_host_uuid=target_host_uuid)
        event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
        payload = {
            "event": "visitor",
            "eventId": event_id,
            "uuid": uuid,
            "name": name,
            "reason": reason,
            "client_type": client_type,
            "target_host_uuid": target_host_uuid or "",
            "server_address": self._get_server_url()
        }

        for host in target_hosts:
            self.db.add_host_event(host['uuid'], event_id, "visitor", payload)
            self._notify_host_streams(host['uuid'], "visitor", payload)
            if host.get('push_token'):
                self._send_fcm_tickle(host['push_token'], event_id=event_id, event_type="visitor", username=host.get('username'))

        return self._send_json({"status": "queued", "eventId": event_id})

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        clean_parts = [p for p in parsed.path.split('/') if p]
        path = '/' + '/'.join(clean_parts)

        # Hard error on ANY URL query parameters on /api/*
        if path.startswith('/api/') and parsed.query:
            return self._send_json({
                "error": "URL query parameters are strictly forbidden. All parameters must be sent in the JSON request body via POST."
            }, 400)

        body = self._read_json_body()

        # Public unauthenticated discovery: POST /api/public
        if path == '/api/public':
            action = body.get('action') or body.get('type') or ''
            if action in ['claim_invite', 'claim'] or ('code' in body and 'token' not in body and 'pin_token' not in body):
                return self._handle_claim_invite(body)
            elif action in ['register', 'register_visitor'] or ('reason' in body and 'code' not in body and 'token' not in body and 'pin_token' not in body):
                return self._handle_register_visitor(body)
            return self._send_json(self._get_public_payload())

        # Host & Member endpoints: POST /api/host
        if path == '/api/host':
            action = body.get('action') or body.get('type') or ''
            if action in ['claim_invite', 'claim']:
                return self._handle_claim_invite(body)
            elif action == 'register_visitor':
                return self._handle_register_visitor(body)

            # Admin Authentication & Config via POST /api/host
            if action in ['admin_login', 'login_admin']:
                pwd = body.get('password') or body.get('admin_password')
                configured_pwd = (self.db.get_setting('admin_password') if self.db else None) or getattr(WhiskerServerHandler, 'admin_password', None) or 'testpassword123'
                if not pwd or pwd != configured_pwd:
                    return self._send_json({"error": "Invalid administrator password"}, 401)
                token = f"adm_tok_{''.join(random.choices(string.ascii_lowercase + string.digits, k=24))}"
                exp = time.time() + 86400
                with WhiskerServerHandler.streams_lock:
                    WhiskerServerHandler.admin_tokens[token] = exp
                return self._send_json({"status": "ok", "token": token, "expires_at": int(exp * 1000)})

            if action in ['admin_config', 'save_config']:
                if not self._is_admin_authenticated():
                    return self._send_json({"error": "Admin authentication required"}, 401)
                s_name = body.get('server_name')
                if s_name:
                    self.db.set_setting('server_name', s_name)
                    WhiskerServerHandler.server_name = s_name
                f_path = body.get('files_storage_path')
                if f_path:
                    self.db.set_setting('files_storage_path', f_path)
                    WhiskerServerHandler.files_storage_path = f_path
                new_pwd = body.get('new_admin_password')
                if new_pwd:
                    self.db.set_setting('admin_password', new_pwd)
                    WhiskerServerHandler.admin_password = new_pwd
                return self._send_json({"status": "ok", "success": True})

            # File Download via POST /api/host
            if action in ['file_download', 'download', 'file']:
                rel_file = body.get('path') or body.get('file') or body.get('rel_path') or body.get('file_path')
                name = body.get('name') or body.get('filename') or ''
                return self._serve_file_download(rel_file, name=name)

            # SSE Stream via POST /api/host
            if action in ['events', 'stream'] or self.headers.get('Accept') == 'text/event-stream':
                token = self._get_auth_token() or body.get('token') or body.get('pin_token')
                uuid_param = body.get('uuid') or body.get('client_uuid')
                token_param = body.get('token') or body.get('client_token') or token
                if token:
                    host = next((h for h in self.db.get_hosts() if h.get('pin_token') == token), None)
                    if host:
                        return self._handle_host_sse(host['uuid'])
                if uuid_param:
                    req = self.db.get_request(uuid_param)
                    if req:
                        return self._handle_guest_sse(uuid_param)
                return self._send_json({"error": "Unauthorized event stream request"}, 401)

            # Message List via POST /api/host
            if action in ['messages', 'get_messages']:
                token = self._get_auth_token() or body.get('token') or body.get('client_token')
                uuid_param = body.get('uuid') or body.get('client_uuid')
                token_param = body.get('token') or body.get('client_token') or token
                if not uuid_param:
                    if token:
                        host = next((h for h in self.db.get_hosts() if h.get('pin_token') == token), None)
                        if host:
                            msgs = self.db.get_messages(host['uuid'])
                            return self._send_json(msgs)
                    return self._send_json({"error": "Missing uuid parameter"}, 400)
                if token:
                    host = next((h for h in self.db.get_hosts() if h.get('pin_token') == token), None)
                    if host:
                        msgs = self.db.get_messages(uuid_param)
                        return self._send_json(msgs)
                if not self._authorize_guest(uuid_param, token_param):
                    return
                msgs = self.db.get_messages(uuid_param)
                return self._send_json(msgs)

            # Call Status via POST /api/host
            if action in ['call_status', 'call_state']:
                call_id = body.get('call_id') or body.get('id')
                uuid_param = body.get('uuid') or body.get('client_uuid')
                session = self._get_call_session(call_id=call_id, uuid=uuid_param)
                if session:
                    duration = int(time.time() - session.get('created_at', time.time()))
                    return self._send_json({
                        "call_id": session.get('call_id'),
                        "status": session.get('status'),
                        "active": session.get('status') == 'ACTIVE',
                        "is_direct": session.get('is_direct', False),
                        "video": session.get('video', True),
                        "duration_seconds": duration,
                        "created_at": session.get('created_at')
                    })
                return self._send_json({"status": "IDLE", "active": False, "call_id": None})

            if not action:
                # If body has 'username' and 'pin_token', treat as mobile host registration
                if 'username' in body and 'pin_token' in body:
                    action = 'register_mobile'
                # If body has 'message_id' or 'msg_id', treat as get_message
                elif 'message_id' in body or 'msg_id' in body:
                    action = 'get_message'
                # If neither action nor type, but body has 'eventId' or 'id', treat as ack
                elif 'eventId' in body or 'id' in body:
                    action = 'ack'
                # If body has pushToken/fcmToken, treat as register_push
                elif 'pushToken' in body or 'fcmToken' in body:
                    action = 'register_push'
                # If body has sdp or candidate or text, treat as response
                elif 'sdp' in body or 'candidate' in body or 'text' in body or 'filePath' in body:
                    action = 'response'
                elif 'fcm_hash' in body:
                    action = 'sync'
                else:
                    return self._send_json({"error": "Missing action in request body"}, 400)

            # Mobile app host registration / pairing (incorporating legacy /api/register_mobile into /api/host)
            if action in ['register_mobile', 'register']:
                username = body.get('username')
                pin_token = body.get('pin_token') or self._get_auth_token()
                push_token = body.get('push_token') or body.get('pushToken') or body.get('fcmToken')
                if not username or not pin_token:
                    return self._send_json({"error": "username and pin_token required"}, 400)

                import uuid as uuid_lib
                hosts = self.db.get_hosts()
                existing = next((h for h in hosts if h['pin_token'] == pin_token), None)

                clean_push_token = self._extract_clean_push_token(body)

                if existing:
                    updates = {"username": username}
                    if clean_push_token:
                        updates["push_token"] = clean_push_token
                        print(f"[Whisker Server] 📲 Host FCM Push Token registered during pairing: {clean_push_token[:16]}... for host '{username}'")
                    self.db.update_host(existing['uuid'], **updates)
                    s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
                    srv_pub = getattr(WhiskerServerHandler, 'server_pub_key', None) or (self.db.get_setting('server_pub_key') if self.db else None)
                    return self._send_json({
                        "status": existing['status'],
                        "host_uuid": existing['uuid'],
                        "username": username,
                        "is_admin": existing.get('is_admin', 0),
                        "server_uuid": srv_pub,
                        "server_public_key": srv_pub,
                        "server_name": s_name,
                        "servername": s_name
                    })

                pub_key = (body.get('public_key') or body.get('pub_key') or body.get('host_uuid') or body.get('uuid') or '').strip()
                host_uuid = pub_key if pub_key else str(uuid_lib.uuid4())
                admin_host = self.db.get_admin_host()
                # ONLY the mobile app that did the first initial pairing is granted admin rights
                is_initial_pairing = (len(hosts) == 0 or admin_host is None)
                is_admin = 1 if is_initial_pairing else 0
                status = 'APPROVED' if is_admin else 'PENDING'
                self.db.add_host(host_uuid, username, pin_token, clean_push_token, is_admin, status)

                if status == 'PENDING':
                    admin = self.db.get_admin_host()
                    if admin:
                        event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                        payload = {
                            "event": "mobile_pairing_request",
                            "type": "mobile_pairing_request",
                            "eventId": event_id,
                            "uuid": host_uuid,
                            "host_uuid": host_uuid,
                            "username": username,
                            "push_token": clean_push_token
                        }
                        self.db.add_host_event(admin['uuid'], event_id, "mobile_pairing_request", payload)
                        self._notify_host_streams(admin["uuid"], "mobile_pairing_request", payload)

                        admin_push = admin.get('push_token')
                        if admin_push:
                            self._send_fcm_tickle(admin_push, event_id=event_id, event_type="mobile_pairing", username=admin.get('username'))

                s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
                srv_pub = getattr(WhiskerServerHandler, 'server_pub_key', None) or (self.db.get_setting('server_pub_key') if self.db else None)
                return self._send_json({
                    "status": status,
                    "host_uuid": host_uuid,
                    "username": username,
                    "is_admin": is_admin,
                    "server_uuid": srv_pub,
                    "server_public_key": srv_pub,
                    "server_name": s_name,
                    "servername": s_name
                })

            # Check server initialization for all other host actions
            if not self._is_initialized():
                return self._send_json(
                    {"error": "Server is not initialized. Please connect your Whisker mobile app first."},
                    503
                )

            # JSON-body Host Unified Sync: POST /api/host with { "action": "sync", "fcm_hash": "...", "eventId": "..." }
            if action in ['sync', 'status', 'ping']:
                uuid_param = body.get('uuid') or body.get('client_uuid')
                token = self._get_auth_token(body)

                # Check if this is a visitor/embed client checking their registration/approval status or sync
                if uuid_param:
                    visitor_req = self.db.get_request(uuid_param)
                    if visitor_req:
                        target_host = self._resolve_target_host(uuid_param, visitor_req.get('target_host_uuid'))
                        is_host_online = bool(target_host and WhiskerServerHandler.host_streams.get(target_host['uuid']))
                        target_host_name = target_host.get('username') or target_host.get('device_name') or 'Primary Device' if target_host else 'Mobile App'

                        resp = {
                            "status": visitor_req.get('status', 'PENDING'),
                            "uuid": uuid_param,
                            "server_name": self.server_name,
                            "target_host_name": target_host_name,
                            "host_online": is_host_online
                        }
                        if visitor_req.get('status') == 'APPROVED' and visitor_req.get('token'):
                            resp["token"] = visitor_req["token"]
                        return self._send_json(resp)

                if not token:
                    return self._send_json({"error": "Access Key required", "status": "UNAUTHORIZED"}, 401)

                host = None
                for h in self.db.get_hosts():
                    if h.get('pin_token') == token:
                        host = h
                        break

                caller_client = None
                if not host:
                    caller_client = next((r for r in self.db.get_requests() if r.get('token') == token), None)

                if host:
                    if host.get('status') == 'PENDING':
                        return self._send_json({"error": "Pairing request pending approval", "status": "PENDING", "role": "COMPANION"}, 401)
                    elif host.get('status') != 'APPROVED':
                        return self._send_json({"error": "Pairing request rejected", "status": "REJECTED"}, 403)
                elif caller_client:
                    if caller_client.get('status') != 'APPROVED' and caller_client.get('req_status') != 'APPROVED':
                        return self._send_json({"error": "Member client pairing pending or revoked", "status": "UNAUTHORIZED"}, 401)
                else:
                    return self._send_json({"error": "Unauthorized access key", "status": "UNREGISTERED"}, 401)

                clean_push_token = self._extract_clean_push_token(body)
                if clean_push_token:
                    if host and host.get('push_token') != clean_push_token:
                        self.db.update_host(host["uuid"], push_token=clean_push_token)
                        print(f"[Whisker Server] 📲 Host FCM Push Token synced from request: {clean_push_token[:16]}... for host '{host.get('username') or host['uuid']}'")
                    elif caller_client and caller_client.get('push_token') != clean_push_token:
                        self.db.update_request_push_token(caller_client['uuid'], clean_push_token)
                        print(f"[Whisker Server] 📲 Member FCM Push Token synced from request: {clean_push_token[:16]}... for member '{caller_client.get('name') or caller_client['uuid']}'")

                req_event_id = body.get('eventId') or body.get('id')
                client_fcm_hash = body.get('fcm_hash') or self.headers.get('If-None-Match', '')
                payload = self._handle_host_sync_payload(host, caller_client, req_event_id=req_event_id, client_fcm_hash=client_fcm_hash)
                return self._send_json(payload)

            # Fetch single message via JSON body: POST /api/host with { "action": "get_message", "message_id": "..." }
            elif action in ['get_message', 'message_lookup']:
                token = self._get_auth_token(body)
                if not token:
                    return self._send_json({"error": "Access Key required", "status": "UNAUTHORIZED"}, 401)

                host = None
                for h in self.db.get_hosts():
                    if h.get('pin_token') == token:
                        host = h
                        break
                caller_client = None
                if not host:
                    caller_client = next((r for r in self.db.get_requests() if r.get('token') == token), None)

                if not host and not caller_client:
                    return self._send_json({"error": "Unauthorized access key", "status": "UNREGISTERED"}, 401)

                msg_id_str = body.get('message_id') or body.get('msg_id') or body.get('id')
                if not msg_id_str:
                    return self._send_json({"error": "Missing message_id in body"}, 400)

                msg = self.db.get_message_by_id(str(msg_id_str))
                if not msg:
                    return self._send_json({"error": "Message not found"}, 404)
                return self._send_json({
                    "status": "ok",
                    "id": msg['id'],
                    "clientUuid": msg['client_uuid'],
                    "sender": msg['sender'],
                    "text": msg['text'],
                    "filePath": msg['file_path'],
                    "createdAt": msg['created_at'],
                    "previousMessageId": msg['previous_message_id'],
                    "is_encrypted": bool(msg.get('is_encrypted', 0)),
                    "isEncrypted": bool(msg.get('is_encrypted', 0))
                })

            elif action in ['register_push', 'update_push']:
                clean_token = self._extract_clean_push_token(body)

                token = self._get_auth_token(body)
                host = None
                if token:
                    for h in self.db.get_hosts():
                        if h.get('pin_token') == token:
                            host = h
                            break

                if host and host.get('status') == 'APPROVED':
                    if clean_token:
                        if host.get('push_token') != clean_token:
                            self.db.update_host(host["uuid"], push_token=clean_token)
                            print(f"[Whisker Server] 📲 Host FCM Push Token registered: {clean_token[:16]}... for host '{host.get('username') or host['uuid']}'")
                        return self._send_json({"status": "ok", "message": "Push token registered successfully"})
                    return self._send_json({"error": "No valid push token provided"}, 400)

                # Check if token belongs to an approved member client from an invite
                client_req = next((r for r in self.db.get_requests() if r.get('token') == token), None) if token else None
                if client_req and (client_req.get('status') == 'APPROVED' or client_req.get('req_status') == 'APPROVED'):
                    if clean_token:
                        if client_req.get('push_token') != clean_token:
                            self.db.update_request_push_token(client_req['uuid'], clean_token)
                            print(f"[Whisker Server] 📲 Member FCM Push Token registered: {clean_token[:16]}... for member '{client_req.get('name') or client_req['uuid']}'")
                        return self._send_json({"status": "ok", "message": "Push token registered successfully"})
                    return self._send_json({"error": "No valid push token provided"}, 400)

                return self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)

            elif action in ['fcm_config']:
                host = self._authorize_host()
                if not host: return
                host_uuid = host["uuid"]
                api_key = body.get('api_key')
                if api_key:
                    self.db.set_setting('fcm_api_key', api_key)
                    return self._send_json({"status": "ok", "message": "FCM API Key updated successfully"})
                return self._send_json({"error": "Missing api_key"}, 400)

            elif action in ['direct_calls', 'allow_direct_calls']:
                host = self._authorize_host()
                if not host: return
                host_uuid = host["uuid"]
                allow = 1 if body.get('allow_direct_calls') or body.get('enabled') else 0
                self.db.update_host(host_uuid, allow_direct_calls=allow)
                print(f"⚡ [Direct Calls] Host '{host.get('username') or host_uuid}' updated allow_direct_calls to {bool(allow)}")
                return self._send_json({"status": "ok", "message": "Direct calls setting updated", "allow_direct_calls": bool(allow)})

            elif action in ['widget_access', 'allow_widget']:
                host = self._authorize_host()
                if not host: return
                host_uuid = host["uuid"]
                allow = 1 if (body.get('allow_widget') is True or body.get('allow_widget_access') is True or body.get('enabled') is True or body.get('allow') is True) else 0
                if 'allow_widget' in body and not body['allow_widget']:
                    allow = 0
                elif 'allow_widget_access' in body and not body['allow_widget_access']:
                    allow = 0
                elif 'enabled' in body and not body['enabled']:
                    allow = 0
                self.db.update_host(host_uuid, allow_widget=allow)
                print(f"⚡ [Widget Access] Host '{host.get('username') or host_uuid}' updated allow_widget to {bool(allow)}")
                return self._send_json({"status": "ok", "message": "Widget access setting updated", "allow_widget": bool(allow)})

            elif action in ['ack', 'clear_event']:
                token = self._get_auth_token(body)
                host = None
                if token:
                    for h in self.db.get_hosts():
                        if h.get('pin_token') == token:
                            host = h
                            break

                if host and host.get('status') == 'APPROVED':
                    host_uuid = host["uuid"]
                    event_id = body.get('eventId') or body.get('id')
                    host_label = host.get('username') or host_uuid
                    if event_id:
                        deleted = self.db.delete_host_event(event_id)
                        if not deleted:
                            print(f"⚠️ [Whisker Server] ACK warning: Event ID '{event_id}' requested to be cleared by host '{host_label}', but it was NOT present in the queue on this home server.")
                        else:
                            print(f"🧹 [Whisker Server] Host '{host_label}' acknowledged and cleared event '{event_id}'")
                    else:
                        print(f"⚠️ [Whisker Server] ACK warning: Empty eventId received from host '{host_label}'")
                    return self._send_json({"status": "ok", "message": "Event cleared"})

                client_req = next((r for r in self.db.get_requests() if r.get('token') == token), None) if token else None
                if client_req and (client_req.get('status') == 'APPROVED' or client_req.get('req_status') == 'APPROVED'):
                    event_id = body.get('eventId') or body.get('id')
                    member_label = client_req.get('name') or client_req['uuid']
                    if event_id:
                        deleted = self.db.delete_guest_event(event_id)
                        if deleted:
                            print(f"🧹 [Whisker Server] Member '{member_label}' acknowledged and cleared event '{event_id}'")
                        else:
                            print(f"🧹 [Whisker Server] Member '{member_label}' acknowledged event '{event_id}' (not in queue)")
                    else:
                        print(f"⚠️ [Whisker Server] ACK warning: Empty eventId received from member '{member_label}'")
                    return self._send_json({"status": "ok", "message": "Event cleared"})

                return self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)

            elif action in ['create_invite', 'invite']:
                host = self._authorize_host()
                if not host: return
                if host.get('status') != 'APPROVED':
                    return self._send_json({"error": "Approved host privileges required to create invites"}, 403)
                host_uuid = host["uuid"]
                name = body.get('name', 'Invited Guest')
                code = body.get('code')
                if not code:
                    code = ''.join(random.choices(string.ascii_letters + string.digits, k=5))

                clean_push_token = self._extract_clean_push_token(body)
                if clean_push_token and host.get('push_token') != clean_push_token:
                    self.db.update_host(host_uuid, push_token=clean_push_token)
                    print(f"[Whisker Server] 📲 Host FCM Push Token synced during create_invite: {clean_push_token[:16]}... for host '{host.get('username') or host_uuid}'")

                self.db.add_invite(code, name, host_uuid)
                proto = 'https' if self.headers.get('X-Forwarded-Proto') == 'https' else 'http'
                host_hdr = self.headers.get('Host') or f"localhost:{self.server.server_port}"
                invite_url = f"{proto}://{host_hdr}/invite/{code}"
                host_label = host.get('username') or host_uuid
                print(f"🎟️ [Whisker Invite Created] Code: '{code}' for '{name}' by host '{host_label}'")
                return self._send_json({
                    "status": "ok",
                    "success": True,
                    "code": code,
                    "name": name,
                    "url": invite_url,
                    "created_at": int(time.time() * 1000)
                })

            elif action in ['delete_invite', 'revoke_invite']:
                host = self._authorize_host()
                if not host: return
                host_uuid = host["uuid"]
                code = body.get('code')
                if code:
                    invites = self.db.get_invites()
                    target_inv = next((inv for inv in invites if inv.get('code') == code), None)
                    is_admin = (host.get('is_admin', 0) == 1)
                    if target_inv and (is_admin or target_inv.get('host_uuid') == host_uuid):
                        self.db.delete_invite(code)
                        print(f"🎟️ [Whisker Server] Deleted invite code '{code}' by host '{host.get('username') or host_uuid}'")
                    elif not target_inv:
                        self.db.delete_invite(code)
                return self._send_json({"status": "ok", "success": True})

            elif action in ['invites', 'get_invites']:
                host = self._authorize_host()
                if not host: return
                return self._send_json({"status": "ok", "invites": self.db.get_invites(host["uuid"])})

            elif action in ['transfer_admin']:
                host = self._authorize_host()
                if not host: return
                if host.get('is_admin', 0) != 1:
                    return self._send_json({"error": "Only current admin can transfer admin rights"}, 403)
                new_admin_uuid = body.get('target_uuid') or body.get('uuid')
                if not new_admin_uuid:
                    return self._send_json({"error": "target_uuid required"}, 400)
                target = self.db.get_host(new_admin_uuid)
                if not target:
                    return self._send_json({"error": "Target mobile host not found"}, 404)
                self.db.transfer_admin(new_admin_uuid)
                print(f"👑 [Whisker Admin] Admin rights transferred from '{host.get('username')}' to '{target.get('username')}'")
                return self._send_json({"status": "ok", "success": True, "new_admin_uuid": new_admin_uuid})

            elif action in ['delete_client', 'remove_client', 'remove_contact', 'delete_host']:
                host = self._authorize_host()
                if not host: return
                host_uuid = host["uuid"]
                target_uuid = body.get('uuid') or body.get('clientUuid') or body.get('target_uuid') or body.get('host_uuid')
                if not target_uuid:
                    return self._send_json({"error": "Missing target uuid"}, 400)

                is_admin = (host.get('is_admin', 0) == 1)
                host_name = host.get('username') or f"Host ({host_uuid[:6]})"

                target_host = self.db.get_host(target_uuid)
                if target_host:
                    target_name = target_host.get('username') or f"Mobile Device ({target_uuid[:6]})"
                    if is_admin or target_uuid == host_uuid:
                        self.db.delete_host(target_uuid)
                        with WhiskerServerHandler.streams_lock:
                            WhiskerServerHandler.host_streams.pop(target_uuid, None)
                        WhiskerServerHandler.host_last_seen.pop(target_uuid, None)
                        print(f"🗑️ [Whisker Server] Removed mobile contact '{target_name}' (UUID: {target_uuid}) by host '{host_name}' (UUID: {host_uuid})")
                        return self._send_json({"status": "ok", "success": True, "message": f"Mobile contact '{target_name}' removed"})
                    else:
                        return self._send_json({"error": "Unauthorized to remove mobile host"}, 403)

                target_req = self.db.get_request(target_uuid)
                target_name = target_req.get('name') if target_req else f"Caller ({target_uuid[:6]})"

                if is_admin:
                    self.db.delete_client_completely(target_uuid)
                else:
                    self.db.remove_client_approval(target_uuid, host_uuid)

                with WhiskerServerHandler.streams_lock:
                    WhiskerServerHandler.guest_streams.pop(target_uuid, None)
                WhiskerServerHandler.guest_last_seen.pop(target_uuid, None)

                print(f"🗑️ [Whisker Server] Removed approved caller '{target_name}' (UUID: {target_uuid}) by host '{host_name}' (UUID: {host_uuid})")
                return self._send_json({"status": "ok", "success": True, "message": f"Approved caller '{target_name}' removed"})

            elif action in ['claim_remote_invite']:
                host = self._authorize_host()
                if not host: return
                host_uuid = host["uuid"]
                invite_url = (body.get('invite_url') or body.get('url') or '').strip()
                my_name = body.get('my_name') or host.get('username') or 'Mobile App'

                if not invite_url:
                    return self._send_json({"error": "Missing invite_url"}, 400)

                code = ""
                remote_base = ""
                if '/invite/' in invite_url:
                    parts = invite_url.split('/invite/')
                    remote_base = parts[0].rstrip('/')
                    code = parts[1].split('?')[0].split('#')[0].strip()
                else:
                    code = invite_url
                    remote_base = (body.get('server_url') or '').rstrip('/')

                if not remote_base or not code:
                    return self._send_json({"error": "Invalid invite URL or missing server_url"}, 400)

                try:
                    req_data = json.dumps({"action": "claim_invite", "code": code, "name": my_name}).encode('utf-8')
                    req = urllib.request.Request(
                        f"{remote_base}/api/public",
                        data=req_data,
                        headers={"Content-Type": "application/json"}
                    )
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        resp_body = json.loads(resp.read().decode('utf-8'))
                        if not resp_body.get('success'):
                            return self._send_json({"error": resp_body.get('error', 'Remote claim failed')}, 400)

                        client_uuid = resp_body['uuid']
                        token = resp_body['token']
                        peer_name = resp_body.get('host_name') or resp_body.get('name') or 'Remote Mobile App'

                        self.db.add_request(
                            client_uuid,
                            peer_name,
                            f"Connected from remote home server ({remote_base})",
                            status='APPROVED',
                            token=token,
                            client_type='MOBILE',
                            server_address=remote_base
                        )
                        self.db.add_client_host_approval(client_uuid, host_uuid)

                        return self._send_json({
                            "status": "ok",
                            "success": True,
                            "uuid": client_uuid,
                            "token": token,
                            "name": peer_name,
                            "client_type": "MOBILE",
                            "server_address": remote_base,
                            "server_name": resp_body.get('server_name') or resp_body.get('servername') or '',
                            "status": "APPROVED"
                        })
                except Exception as e:
                    return self._send_json({"error": f"Failed to claim remote invite: {str(e)}"}, 500)

            elif action in ['file_upload', 'upload']:
                token = self._get_auth_token(body)
                client_id = body.get('uuid') or body.get('clientUuid')
                content_b64 = body.get('content') or body.get('file') or body.get('data')
                filename = body.get('filename') or body.get('fileName') or f"upload_{int(time.time())}.bin"
                text = body.get('text') or filename
                contact_param = body.get('contact') or body.get('target_host') or body.get('target_host_uuid')

                host = None
                caller_client = None
                if token:
                    for h in self.db.get_hosts():
                        if h.get('pin_token') == token:
                            host = h
                            break
                if not host and client_id:
                    req = self.db.get_request(client_id)
                    if req and (req.get('status') == 'APPROVED' or req.get('req_status') == 'APPROVED'):
                        if not token or req.get('token') == token or not req.get('token'):
                            caller_client = req
                if not caller_client and token:
                    caller_client = next((r for r in self.db.get_requests() if r.get('token') == token and (r.get('status') == 'APPROVED' or r.get('req_status') == 'APPROVED')), None)

                if not host and not caller_client:
                    return self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)

                if content_b64:
                    import base64
                    if ',' in content_b64:
                        content_b64 = content_b64.split(',', 1)[1]
                    f_bytes = base64.b64decode(content_b64)
                    if caller_client:
                        target_host = self._resolve_target_host(caller_client['uuid'], contact_uuid=contact_param)
                        target_rec = target_host.get('username') if target_host else 'Host'
                        sender_snd = caller_client.get('name') or 'Visitor'
                        _, rel_p, safe_fn, _ = save_uploaded_file(self.db, target_rec, sender_snd, filename, f_bytes)
                        if target_host:
                            is_enc = 1 if (body.get('is_encrypted') or body.get('isEncrypted') or body.get('encrypted')) else 0
                            msg_id, prev_id = self.db.add_message(caller_client['uuid'], target_host['uuid'], 'guest', text, rel_p, is_encrypted=is_enc)
                            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                            payload = {
                                "event": "message",
                                "eventId": event_id,
                                "messageId": msg_id,
                                "previousMessageId": prev_id,
                                "uuid": caller_client['uuid'],
                                "clientUuid": caller_client['uuid'],
                                "name": sender_snd,
                                "text": text,
                                "is_encrypted": bool(is_enc),
                                "isEncrypted": bool(is_enc),
                                "filePath": rel_p,
                                "file": rel_p,
                                "timestamp": int(time.time() * 1000),
                                "client_type": caller_client.get('client_type', 'WEB'),
                                "server_address": self._get_server_url()
                            }
                            self.db.add_host_event(target_host['uuid'], event_id, "message", payload)
                            self._notify_host_streams(target_host['uuid'], "message", payload)
                            if target_host.get('push_token'):
                                self._send_fcm_tickle(target_host['push_token'], event_id=event_id, event_type="message", username=target_host.get('username'))
                        return self._send_json({"status": "ok", "url": rel_p, "filePath": rel_p})
                    elif host:
                        target_rec = client_id or 'Guest'
                        sender_snd = host.get('username') or 'Host'
                        _, rel_p, safe_fn, _ = save_uploaded_file(self.db, target_rec, sender_snd, filename, f_bytes)
                        return self._send_json({"status": "ok", "url": rel_p, "filePath": rel_p})
                return self._send_json({"error": "No file content provided"}, 400)

            elif action in ['response', 'chat_message', 'message', 'call', 'signal', 'hangup', 'registration_response', 'mobile_pairing_response']:
                return self._handle_host_response(body)
            else:
                return self._send_json({"error": f"Unknown action: {action}"}, 400)

        return self._send_json({"error": "Endpoint not found"}, 404)

    def _handle_host_response(self, body):
        token = self._get_auth_token(body)
        host = None
        if token:
            for h in self.db.get_hosts():
                if h.get('pin_token') == token:
                    host = h
                    break

        caller_client = None
        client_uuid = body.get('clientUuid') or body.get('uuid')
        if not host:
            if client_uuid:
                req = self.db.get_request(client_uuid)
                if req:
                    if req.get('status') == 'APPROVED' or req.get('req_status') == 'APPROVED':
                        if not token or req.get('token') == token or not req.get('token'):
                            caller_client = req
                    elif req.get('status') == 'PENDING':
                        action_type = body.get('action') or body.get('type') or body.get('event') or ''
                        if action_type in ['chat_message', 'message', 'response'] or 'text' in body or 'filePath' in body or 'file' in body:
                            caller_client = req
            if not caller_client and token:
                caller_client = next((r for r in self.db.get_requests() if r.get('token') == token and (r.get('status') == 'APPROVED' or r.get('req_status') == 'APPROVED')), None)

        if host and host.get('status') != 'APPROVED':
            return self._send_json({"error": "Host not approved", "status": "UNAUTHORIZED"}, 401)

        if not host and not caller_client:
            return self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)

        resp_type = body.get('type')
        resp_event = body.get('event')

        if caller_client:
            member_uuid = caller_client["uuid"]
            contact_param = body.get('contact') or body.get('target_host') or body.get('target_host_uuid') or body.get('targetHostUuid') or body.get('host_uuid')
            if client_uuid and client_uuid != member_uuid and not contact_param:
                contact_param = client_uuid

            target_host = self._resolve_target_host(member_uuid, contact_uuid=contact_param)
            if not target_host:
                approved_hosts = [h for h in self.db.get_hosts() if h.get('status') == 'APPROVED']
                target_host = approved_hosts[0] if approved_hosts else None

            if not target_host:
                return self._send_json({"error": "Host not paired with this member client"}, 403)

            if resp_type in ['chat_message', 'message'] or (body.get('sender') == 'phone' or 'text' in body):
                text = body.get('text', '')
                file_path = body.get('filePath') or body.get('file')
                if text or file_path:
                    is_enc = 1 if (body.get('is_encrypted') or body.get('isEncrypted') or body.get('encrypted')) else 0
                    msg_id, prev_id = self.db.add_message(member_uuid, target_host['uuid'], 'guest', text, file_path, is_encrypted=is_enc)
                    event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                    payload = {
                        "event": "message",
                        "eventId": event_id,
                        "messageId": msg_id,
                        "previousMessageId": prev_id,
                        "uuid": member_uuid,
                        "clientUuid": member_uuid,
                        "name": caller_client.get('name') or 'Member Mobile App',
                        "text": text,
                        "is_encrypted": bool(is_enc),
                        "isEncrypted": bool(is_enc),
                        "filePath": file_path,
                        "file": file_path,
                        "timestamp": int(time.time() * 1000),
                        "client_type": "MOBILE",
                        "server_address": self._get_server_url()
                    }
                    self.db.add_host_event(target_host['uuid'], event_id, "message", payload)
                    self._notify_host_streams(target_host['uuid'], "message", payload)
                    if target_host.get('push_token'):
                        self._send_fcm_tickle(target_host['push_token'], event_id=event_id, event_type="message", username=target_host.get('username'))
                    return self._send_json({"message": "Message routed to host", "messageId": msg_id})

            if resp_type in ['webrtc_call_initiate', 'call'] or resp_event in ['call_initiate', 'call']:
                call_id = body.get('call_id') or f"call_{int(time.time()*1000)}_{random.randint(1000,9999)}"
                self._register_call(call_id, target_host['uuid'], member_uuid, caller_name=caller_client.get('name') or 'Member', initiator="guest", is_direct=False, video=body.get('video', True))
                event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                payload = {
                    "event": "call",
                    "eventId": event_id,
                    "call_id": call_id,
                    "uuid": member_uuid,
                    "name": caller_client.get('name') or 'Member',
                    "sdp": body.get('sdp', ''),
                    "video": body.get('video', True),
                    "client_type": "MOBILE",
                    "server_address": self._get_server_url()
                }
                self.db.add_host_event(target_host['uuid'], event_id, "call", payload)
                self._notify_host_streams(target_host['uuid'], "call", payload)
                if target_host.get('push_token'):
                    self._send_fcm_tickle(target_host['push_token'], event_id=event_id, event_type="call", username=target_host.get('username'))
                return self._send_json({"message": "Call routed to host", "call_id": call_id})

            if resp_type in ['webrtc_call_answer', 'webrtc_ice_candidate'] or resp_event in ['call_answered', 'ice_candidate']:
                call_id = body.get('call_id')
                if resp_type == 'webrtc_call_answer' or resp_event == 'call_answered':
                    self._update_call_status(call_id, "ACTIVE", client_uuid=member_uuid, host_uuid=target_host['uuid'])
                event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                payload = {
                    "event": "signal",
                    "eventId": event_id,
                    "call_id": call_id,
                    "uuid": member_uuid,
                    "type": resp_type or resp_event,
                    "sdp": body.get('sdp', ''),
                    "candidate": body.get('candidate'),
                    "client_type": "MOBILE",
                    "server_address": self._get_server_url()
                }
                self.db.add_host_event(target_host['uuid'], event_id, "signal", payload)
                self._notify_host_streams(target_host['uuid'], "signal", payload)
                return self._send_json({"message": "Signal routed to host"})

            if resp_type in ['webrtc_call_hangup', 'call_declined', 'call_ended'] or resp_event in ['call_ended', 'hangup']:
                call_id = body.get('call_id')
                self._update_call_status(call_id, "ENDED", client_uuid=member_uuid, host_uuid=target_host['uuid'])
                event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                payload = {
                    "event": "hangup",
                    "eventId": event_id,
                    "call_id": call_id,
                    "uuid": member_uuid,
                    "status": body.get("status", "ended"),
                    "reason": body.get("reason", "ended"),
                    "server_address": self._get_server_url()
                }
                self.db.add_host_event(target_host['uuid'], event_id, "hangup", payload)
                self._notify_host_streams(target_host['uuid'], "hangup", payload)
                return self._send_json({"message": "Hangup routed to host"})

            return self._send_json({"message": "Member response processed"})

        host_uuid = host["uuid"]
        target_host = self.db.get_host(client_uuid)
        approved_clients = self.db.get_approved_clients_for_host(host_uuid)
        client_req = self.db.get_request(client_uuid)
        is_req_approved = (client_req is not None and (client_req.get('status') == 'APPROVED' or client_req.get('req_status') == 'APPROVED'))
        is_approved = (target_host is not None) or any(c['uuid'] == client_uuid for c in approved_clients) or is_req_approved

        if not is_approved and resp_type not in ['registration_response', 'mobile_pairing_response', 'delete_client', 'remove_contact']:
            return self._send_json({"error": "Host not paired with this client"}, 403)

        if resp_type in ['delete_client', 'remove_contact']:
            target_uuid = client_uuid
            is_admin = (host.get('is_admin', 0) == 1)
            host_name = host.get('username') or f"Host ({host_uuid[:6]})"
            target_h = self.db.get_host(target_uuid)
            if target_h:
                target_name = target_h.get('username') or f"Mobile Device ({target_uuid[:6]})"
                if is_admin or target_uuid == host_uuid:
                    self.db.delete_host(target_uuid)
                    with WhiskerServerHandler.streams_lock:
                        WhiskerServerHandler.host_streams.pop(target_uuid, None)
                    WhiskerServerHandler.host_last_seen.pop(target_uuid, None)
                    print(f"🗑️ [Whisker Server] Removed mobile contact '{target_name}' (UUID: {target_uuid}) by host '{host_name}' (UUID: {host_uuid})")
                    return self._send_json({"success": True, "message": f"Mobile contact '{target_name}' removed"})
            else:
                target_r = self.db.get_request(target_uuid)
                target_name = target_r.get('name') if target_r else f"Caller ({target_uuid[:6]})"
                if is_admin:
                    self.db.delete_client_completely(target_uuid)
                else:
                    self.db.remove_client_approval(target_uuid, host_uuid)
                with WhiskerServerHandler.streams_lock:
                    WhiskerServerHandler.guest_streams.pop(target_uuid, None)
                WhiskerServerHandler.guest_last_seen.pop(target_uuid, None)
                print(f"🗑️ [Whisker Server] Removed approved caller '{target_name}' (UUID: {target_uuid}) by host '{host_name}' (UUID: {host_uuid})")
                return self._send_json({"success": True, "message": f"Approved caller '{target_name}' removed"})

        elif resp_type == 'registration_response' and resp_event == 'approved':
            token = body.get('token')
            if client_uuid and token:
                self.db.approve_request(client_uuid, token)
                self.db.add_client_host_approval(client_uuid, host_uuid)
                body['host_uuid'] = host_uuid
                body['host_name'] = host.get('username') or body.get('hostName') or body.get('host_name') or 'Primary Device'
                body['token'] = token
                body['status'] = 'APPROVED'
                
        elif resp_type == 'mobile_pairing_response' and resp_event == 'approved':
            target_host_uuid = body.get('uuid') or body.get('host_uuid')
            if target_host_uuid:
                self.db.update_host(target_host_uuid, status='APPROVED')
                target_h = self.db.get_host(target_host_uuid)
                if target_h and target_h.get('push_token'):
                    self._send_fcm_tickle(target_h['push_token'], event_id=target_host_uuid, event_type="mobile_pairing", extra_status="approved", username=target_h.get('username'))
                print(f"✅ [Whisker Server] Approved companion mobile app '{target_h.get('username') if target_h else target_host_uuid}' (UUID: {target_host_uuid}) by host '{host.get('username') or host_uuid}'")
                    
        elif resp_type == 'mobile_pairing_response' and resp_event in ['rejected', 'delete', 'deleted', 'remove']:
            target_host_uuid = body.get('uuid') or body.get('host_uuid')
            if target_host_uuid:
                target_h = self.db.get_host(target_host_uuid)
                target_name = target_h.get('username') if target_h else target_host_uuid
                if resp_event in ['delete', 'deleted', 'remove']:
                    self.db.delete_host(target_host_uuid)
                    with WhiskerServerHandler.streams_lock:
                        WhiskerServerHandler.host_streams.pop(target_host_uuid, None)
                    WhiskerServerHandler.host_last_seen.pop(target_host_uuid, None)
                    print(f"🗑️ [Whisker Server] Deleted companion mobile app '{target_name}' (UUID: {target_host_uuid}) by host '{host.get('username') or host_uuid}'")
                else:
                    self.db.update_host(target_host_uuid, status='REJECTED')
                    if target_h and target_h.get('push_token'):
                        self._send_fcm_tickle(target_h['push_token'], event_id=target_host_uuid, event_type="mobile_pairing", extra_status="rejected", username=target_h.get('username'))
                    print(f"❌ [Whisker Server] Rejected companion mobile app '{target_name}' (UUID: {target_host_uuid}) by host '{host.get('username') or host_uuid}'")
                    
        elif resp_type in ['chat_message', 'message'] and (body.get('sender') == 'phone' or 'text' in body):
            text = body.get('text', '')
            file_path = body.get('filePath') or body.get('file')
            if file_path and (file_path.startswith('data:') or (isinstance(file_path, str) and len(file_path) > 100 and not file_path.startswith('/api/host'))):
                try:
                    import base64
                    b64_content = file_path
                    if ',' in b64_content:
                        b64_content = b64_content.split(',', 1)[1]
                    f_bytes = base64.b64decode(b64_content)
                    orig_fname = body.get('filename') or body.get('fileName') or text or f"file_{int(time.time())}.bin"
                    target_rec = target_host.get('username') if target_host else (client_req.get('name') if client_req else client_uuid)
                    sender_snd = host.get('username') or 'Mobile App'
                    _, rel_p, safe_fn, _ = save_uploaded_file(self.db, target_rec, sender_snd, orig_fname, f_bytes)
                    file_path = rel_p
                    body['filePath'] = file_path
                    body['file'] = file_path
                    body['fileName'] = safe_fn
                    body['filename'] = safe_fn
                    if not text or text == orig_fname:
                        text = safe_fn
                except Exception as e:
                    print(f"Base64 auto-save error in /api/host response action: {e}")
            if client_uuid and (text or file_path):
                is_enc = 1 if (body.get('is_encrypted') or body.get('isEncrypted') or body.get('encrypted')) else 0
                msg_id, prev_id = self.db.add_message(client_uuid, host_uuid, 'phone', text, file_path, is_encrypted=is_enc)
                body['messageId'] = msg_id
                body['id'] = msg_id
                body['previousMessageId'] = prev_id
                body['event'] = 'message'
                body['is_encrypted'] = bool(is_enc)
                body['isEncrypted'] = bool(is_enc)
                body['filePath'] = file_path
                body['file'] = file_path
                body['uuid'] = host_uuid
                body['clientUuid'] = host_uuid
                body['name'] = host.get('username') or 'Mobile App'
                body['server_address'] = self._get_server_url()
                body['client_type'] = "MOBILE"

                # If target is a companion mobile app on the same server, route as host event
                if target_host:
                    event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                    payload = {
                        "event": "message",
                        "eventId": event_id,
                        "messageId": msg_id,
                        "previousMessageId": prev_id,
                        "uuid": host_uuid,
                        "name": host.get('username') or 'Mobile App',
                        "text": text,
                        "is_encrypted": bool(is_enc),
                        "isEncrypted": bool(is_enc),
                        "filePath": file_path,
                        "file": file_path,
                        "timestamp": int(time.time() * 1000),
                        "client_type": "MOBILE",
                        "server_address": self._get_server_url()
                    }
                    self.db.add_host_event(target_host['uuid'], event_id, "message", payload)
                    self._notify_host_streams(target_host['uuid'], "message", payload)
                    if target_host.get('push_token'):
                        self._send_fcm_tickle(target_host['push_token'], event_id=event_id, event_type="message", username=target_host.get('username'))
                    return self._send_json({"message": "Message routed to companion mobile app", "messageId": msg_id})

        if target_host and (resp_type in ['webrtc_call_initiate', 'call'] or resp_event in ['call_initiate', 'call']):
            call_id = body.get('call_id') or f"call_{int(time.time()*1000)}_{random.randint(1000,9999)}"
            self._register_call(call_id, target_host['uuid'], host_uuid, caller_name=host.get('username') or 'Mobile App', initiator="host", is_direct=False, video=body.get('video', True))
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "call",
                "eventId": event_id,
                "call_id": call_id,
                "uuid": host_uuid,
                "name": host.get('username') or 'Mobile App',
                "sdp": body.get('sdp', ''),
                "video": body.get('video', True),
                "client_type": "MOBILE",
                "server_address": self._get_server_url()
            }
            self.db.add_host_event(target_host['uuid'], event_id, "call", payload)
            self._notify_host_streams(target_host['uuid'], "call", payload)
            if target_host.get('push_token'):
                self._send_fcm_tickle(target_host['push_token'], event_id=event_id, event_type="call", username=target_host.get('username'))
            return self._send_json({"message": "Call routed to companion mobile app", "call_id": call_id})

        if target_host and (resp_type in ['webrtc_call_answer', 'webrtc_ice_candidate'] or resp_event in ['call_answered', 'ice_candidate']):
            call_id = body.get('call_id')
            if resp_type == 'webrtc_call_answer' or resp_event == 'call_answered':
                self._update_call_status(call_id, "ACTIVE", client_uuid=host_uuid, host_uuid=target_host['uuid'])
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "signal",
                "eventId": event_id,
                "call_id": call_id,
                "uuid": host_uuid,
                "type": resp_type or resp_event,
                "sdp": body.get('sdp', ''),
                "candidate": body.get('candidate'),
                "client_type": "MOBILE",
                "server_address": self._get_server_url()
            }
            self.db.add_host_event(target_host['uuid'], event_id, "signal", payload)
            self._notify_host_streams(target_host['uuid'], "signal", payload)
            return self._send_json({"message": "Signal routed to companion mobile app"})

        if target_host and (resp_type in ['webrtc_call_hangup', 'call_declined', 'call_ended'] or resp_event in ['call_ended', 'hangup']):
            call_id = body.get('call_id')
            self._update_call_status(call_id, "ENDED", client_uuid=host_uuid, host_uuid=target_host['uuid'])
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "hangup",
                "eventId": event_id,
                "call_id": call_id,
                "uuid": host_uuid,
                "status": body.get("status", "ended"),
                "reason": body.get("reason", "ended"),
                "server_address": self._get_server_url()
            }
            self.db.add_host_event(target_host['uuid'], event_id, "hangup", payload)
            self._notify_host_streams(target_host['uuid'], "hangup", payload)
            return self._send_json({"message": "Hangup routed to companion mobile app"})

        if resp_type in ['webrtc_call_answer', 'webrtc_call_initiate', 'webrtc_ice_candidate'] or resp_event in ['call_answered', 'call_initiate', 'ice_candidate']:
            sdp_str = body.get('sdp', '')
            sdp_len = len(sdp_str)
            has_audio = "m=audio" in sdp_str
            has_video = "m=video" in sdp_str
            cand_obj = body.get('candidate')
            cand_type = ""
            if isinstance(cand_obj, dict):
                cand_type = f" | Candidate: {cand_obj.get('candidate', '')[:40]}..."
            elif cand_obj:
                cand_type = f" | Candidate: {str(cand_obj)[:40]}..."
            
            track_info = f"Audio: {has_audio}, Video: {has_video}" if sdp_len > 0 else "No SDP"
            print(f"📱 [WebRTC Host Reply] Host -> Guest '{client_uuid}' | Type: {resp_type or resp_event} | SDP Len: {sdp_len} ({track_info}){cand_type}")

            call_id = body.get('call_id')
            if resp_type == 'webrtc_call_answer' or resp_event == 'call_answered':
                self._update_call_status(call_id, "ACTIVE", client_uuid=client_uuid, host_uuid=host_uuid)
            elif resp_type == 'webrtc_call_initiate' or resp_event == 'call_initiate':
                if not call_id:
                    call_id = f"call_{int(time.time()*1000)}_{random.randint(1000,9999)}"
                    body['call_id'] = call_id
                self._register_call(call_id, host_uuid, client_uuid, caller_name=host.get('username') or 'Host', initiator="host", is_direct=False, video=body.get('video', True))

        elif resp_type in ['webrtc_call_hangup', 'call_declined', 'call_ended'] or resp_event in ['call_ended', 'hangup', 'call_declined', 'call_rejected']:
            call_id = body.get('call_id')
            self._update_call_status(call_id, "ENDED", client_uuid=client_uuid, host_uuid=host_uuid)

        event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
        body['eventId'] = event_id
        body['uuid'] = host_uuid
        body['clientUuid'] = host_uuid
        body['name'] = host.get('username') or 'Mobile App'
        body['server_address'] = self._get_server_url()
        body['client_type'] = "MOBILE"
        guest_event_type = "message" if resp_type in ['chat_message', 'message'] or resp_event in ['chat_message', 'message'] else (resp_type or resp_event or 'response')
        self.db.add_guest_event(host_uuid, event_id, client_uuid, guest_event_type, body)

        # Broadcast to active guest stream if connected
        self._notify_guest_streams(client_uuid, body)

        # FCM tickle guest if guest has a registered push token
        client_req = self.db.get_request(client_uuid)
        if client_req and client_req.get('push_token'):
            self._send_fcm_tickle(client_req['push_token'], event_id=event_id, event_type=guest_event_type, username=client_req.get('name'))

        return self._send_json({"message": "Response routed", "messageId": body.get('messageId')})


    def _handle_host_sse(self, host_uuid):
        self.send_response(200)
        self._send_cors_headers()
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.end_headers()

        wfile = self.wfile
        with WhiskerServerHandler.streams_lock:
            WhiskerServerHandler.host_streams.setdefault(host_uuid, set()).add(wfile)

        try:
            wfile.write(b"retry: 5000\n\n: keep-alive\n\n")
            wfile.flush()
            while True:
                time.sleep(15)
                wfile.write(b": keep-alive\n\n")
                wfile.flush()
        except Exception:
            pass
        finally:
            with WhiskerServerHandler.streams_lock:
                if host_uuid in WhiskerServerHandler.host_streams:
                    WhiskerServerHandler.host_streams[host_uuid].discard(wfile)

    def _handle_guest_sse(self, uuid):
        self.send_response(200)
        self._send_cors_headers()
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.end_headers()

        wfile = self.wfile
        with WhiskerServerHandler.streams_lock:
            if uuid not in WhiskerServerHandler.guest_streams:
                WhiskerServerHandler.guest_streams[uuid] = set()
            WhiskerServerHandler.guest_streams[uuid].add(wfile)

        self.db.update_presence(uuid, is_online=True)

        try:
            # Deliver any pending stored events
            pending_events = self.db.get_guest_events(uuid)
            now_ms = int(time.time() * 1000)
            for ev in pending_events:
                payload = ev.get('payload', '')
                if isinstance(payload, dict):
                    ev_ts = payload.get('timestamp') or ev.get('created_at', 0)
                    if ev_ts and (now_ms - ev_ts > 60000):
                        continue
                    payload = json.dumps(payload)
                msg_line = f"data: {payload}\n\n".encode('utf-8')
                wfile.write(msg_line)
                wfile.flush()

            if pending_events:
                self.db.delete_guest_events(uuid)

            wfile.write(b": keep-alive\n\n")
            wfile.flush()

            while True:
                time.sleep(15)
                wfile.write(b": keep-alive\n\n")
                wfile.flush()
        except Exception:
            pass
        finally:
            with WhiskerServerHandler.streams_lock:
                if uuid in WhiskerServerHandler.guest_streams:
                    WhiskerServerHandler.guest_streams[uuid].discard(wfile)
                    if not WhiskerServerHandler.guest_streams[uuid]:
                        del WhiskerServerHandler.guest_streams[uuid]

            self.db.update_presence(uuid, is_online=False)


    def _notify_host_streams(self, host_uuid, event_type, payload):
        msg = json.dumps({"type": event_type, "payload": payload})
        msg_line = f"data: {msg}\n\n".encode('utf-8')

        with WhiskerServerHandler.streams_lock:
            dead_streams = []
            for s in WhiskerServerHandler.host_streams.get(host_uuid, []):
                try:
                    s.write(msg_line)
                    s.flush()
                except Exception:
                    dead_streams.append(s)
            for s in dead_streams:
                WhiskerServerHandler.host_streams[host_uuid].discard(s)

    def _notify_guest_streams(self, uuid, event_type_or_payload, payload=None):
        if payload is None:
            actual_payload = event_type_or_payload
        else:
            actual_payload = payload
            if isinstance(actual_payload, dict) and "event" not in actual_payload:
                actual_payload["event"] = event_type_or_payload

        msg = f"data: {json.dumps(actual_payload)}\n\n".encode('utf-8')
        with WhiskerServerHandler.streams_lock:
            streams = WhiskerServerHandler.guest_streams.get(uuid, set())
            if streams:
                dead_streams = set()
                for wfile in list(streams):
                    try:
                        wfile.write(msg)
                        wfile.flush()
                    except Exception:
                        dead_streams.add(wfile)
                for d in dead_streams:
                    streams.discard(d)

def run_server(port=3000, db_path=None, server_name=None, admin_password=None):
    if not server_name or not str(server_name).strip():
        print("Error: Server name is required to run Whisker Server.")
        print("Specify via '--name <server_name>' (e.g. 'whisker server --name home') or SERVER_NAME environment variable.")
        sys.exit(1)

    effective_server_name = str(server_name).strip()
    if not db_path or db_path == "whisker_server.db":
        db_path = f"whisker_server_{sanitize_name(effective_server_name)}.db"

    db = DatabaseManager(db_path)
    fcm = FCMManager()

    db.set_setting('server_name', effective_server_name)
    WhiskerServerHandler.server_name = effective_server_name
    WhiskerServerHandler.db = db
    WhiskerServerHandler.fcm = fcm

    # Server Persistent Unique Identifier Initialization (stored in DB settings)
    existing_srv_id = db.get_setting('server_uuid') or db.get_setting('server_pub_key')
    if not existing_srv_id:
        existing_srv_id = f"srv_{uuid.uuid4().hex}"
        db.set_setting('server_uuid', existing_srv_id)
        db.set_setting('server_pub_key', existing_srv_id)
    else:
        db.set_setting('server_uuid', existing_srv_id)
        db.set_setting('server_pub_key', existing_srv_id)

    WhiskerServerHandler.server_pub_key = existing_srv_id
    WhiskerServerHandler.server_uuid = existing_srv_id

    # Admin Password Initialization
    effective_admin_pwd = (admin_password or '').strip() or os.environ.get('ADMIN_PASSWORD', '').strip()
    if effective_admin_pwd:
        db.set_setting('admin_password', effective_admin_pwd)
        WhiskerServerHandler.admin_password = effective_admin_pwd
    else:
        existing_pwd = db.get_setting('admin_password')
        if not existing_pwd:
            default_pwd = 'admin'
            db.set_setting('admin_password', default_pwd)
            WhiskerServerHandler.admin_password = default_pwd
        else:
            WhiskerServerHandler.admin_password = existing_pwd

    current_admin_pwd = db.get_setting('admin_password') or 'admin'

    server_address = ('', port)
    httpd = ThreadedHTTPServer(server_address, WhiskerServerHandler)

    sa_status = "FOUND (FCM HTTP v1 Active)" if get_service_account_file() else "NOT FOUND (Run 'whisker setup-fcm' to create)"

    print("==================================================================")
    print(" 🐱 WHISKER HOME SERVER ACTIVE (Python Edition)")
    print("==================================================================")
    print(f"   - Server Name:          {effective_server_name}")
    print(f"   - Server ID:            {existing_srv_id}")
    print(f"   - Listening Port:       {port}")
    print(f"   - Local SQLite DB:      {db_path}")
    print(f"   - FCM Service Account:  {sa_status}")
    print("------------------------------------------------------------------")
    print(f"   - Landing Web Portal:   http://localhost:{port}")
    print(f"   - Admin Console:        http://localhost:{port}/admin")
    print(f"   - Admin Password:       {current_admin_pwd}")
    print(f"   - Mobile App URL:       http://localhost:{port}")
    print("==================================================================")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Whisker Home Server...")
        httpd.server_close()
