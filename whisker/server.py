import os
import sys
import json
import time
import random
import string
import urllib.parse
import threading
import re
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

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
                const res = await fetch('/api/init-status');
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
                const res = await fetch('/api/upload_service_account', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(parsed)
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

    def _get_auth_token(self):
        auth = self.headers.get('Authorization', '')
        if auth.startswith('Bearer '):
            return auth[7:].strip()
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)
        if 'token' in qs:
            return qs['token'][0]
        return auth.strip()

    def _is_initialized(self):
        hosts = self.db.get_hosts()
        return len(hosts) > 0 or bool(self.db.get_setting('host_pin_token'))

    def _get_server_url(self):
        proto = 'https' if self.headers.get('X-Forwarded-Proto') == 'https' else 'http'
        host_hdr = self.headers.get('Host') or f"localhost:{self.server.server_port}"
        return f"{proto}://{host_hdr}"

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

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        clean_parts = [p for p in parsed.path.split('/') if p]
        path = '/' + '/'.join(clean_parts)
        qs = urllib.parse.parse_qs(parsed.query)

        # Admin Webpage
        if path in ['/admin', '/admin.html']:
            return self._send_html(self._get_admin_html())

        # Admin Overview API
        if path == '/api/admin/overview':
            if not self._is_admin_authenticated(qs):
                return self._send_json({"error": "Unauthorized"}, 401)

            s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
            files_path = self.db.get_setting('files_storage_path') or '/data/files'

            hosts = self.db.get_hosts()
            with WhiskerServerHandler.streams_lock:
                for h in hosts:
                    h_streams = WhiskerServerHandler.host_streams.get(h['uuid'], set())
                    h['is_online'] = len(h_streams) > 0

            clients = self.db.get_clients()
            invites = self.db.get_invites()
            sa = get_service_account_file()
            port = self.server.server_port if hasattr(self, 'server') and hasattr(self.server, 'server_port') else 3000

            return self._send_json({
                "server": {
                    "name": s_name,
                    "port": port,
                    "url": self._get_server_url(),
                    "initialized": self._is_initialized()
                },
                "settings": {
                    "server_name": s_name,
                    "files_storage_path": files_path
                },
                "hosts": hosts,
                "clients": clients,
                "invites": invites,
                "fcm_configured": bool(sa)
            })

        # GET /api/init-status
        if path == '/api/init-status':
            s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
            sa = get_service_account_file()
            return self._send_json({
                "initialized": self._is_initialized(),
                "server_name": s_name,
                "servername": s_name,
                "push_configured": bool(sa),
                "project_id": sa.get("project_id") if sa else None
            })

        # GET /api/info
        if path == '/api/info':
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
            return self._send_json({
                "initialized": self._is_initialized(),
                "server_name": s_name,
                "servername": s_name,
                "admin_host": admin_host.get("username") if admin_host else None,
                "hosts_count": len(all_hosts),
                "push_configured": push_configured,
                "host_connected": host_conn,
                "host_reachable": host_conn or push_configured,
                "allow_direct_calls": len(direct_hosts) > 0,
                "direct_call_hosts": direct_hosts,
                "server_time": int(time.time() * 1000)
            })

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

        # Public widget host discovery
        if path in ['/api/widget/hosts', '/api/embed/hosts']:
            s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
            hosts = self.db.get_hosts()
            widget_hosts = []
            for h in hosts:
                if h.get('status') == 'APPROVED' and h.get('allow_widget', 1) == 1:
                    widget_hosts.append({
                        "uuid": h["uuid"],
                        "username": h.get("username") or f"Mobile Device ({h['uuid'][:6]})",
                        "is_admin": (h.get("is_admin", 0) == 1),
                        "role": "ADMIN" if (h.get("is_admin", 0) == 1) else "COMPANION"
                    })
            return self._send_json({
                "server_name": s_name,
                "hosts": widget_hosts
            })

        # Enforce server initialization requirement on non-host endpoints
        if not self._is_initialized() and not path.startswith('/api/host/'):
            return self._send_json(
                {"error": "Server is not initialized. Please connect your Whisker mobile app first."},
                503
            )

        # Host endpoints
        if path == '/api/host/stream':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            return self._handle_host_sse(host_uuid)

        if path == '/api/host/events':
            token = self._get_auth_token()
            host = None
            if token:
                for h in self.db.get_hosts():
                    if h.get('pin_token') == token:
                        host = h
                        break
            if host and host.get('status') == 'APPROVED':
                WhiskerServerHandler.host_last_seen[host['uuid']] = time.time()
                events = self.db.get_host_events(host["uuid"])
                
                # Check if this fetch followed an FCM tickle with a specific event ID
                req_event_id = qs.get('eventId', [''])[0] or qs.get('id', [''])[0]
                if req_event_id:
                    found = any(
                        e.get('id') == req_event_id or
                        (isinstance(e.get('payload'), dict) and (
                            e['payload'].get('eventId') == req_event_id or
                            str(e['payload'].get('messageId', '')) == req_event_id
                        ))
                        for e in events
                    )
                    if not found:
                        host_label = host.get('username') or host['uuid']
                        print(f"⚠️ [Whisker Server] FCM tickle mismatch: Requested event ID '{req_event_id}' is NOT present in queue for host '{host_label}' (queue size: {len(events)}). The tickle may be stale or intended for another home server.")

                return self._send_json(events)
            
            # Check if token belongs to an approved member client from an invite
            client_req = next((r for r in self.db.get_requests() if r.get('token') == token), None) if token else None
            if client_req and (client_req.get('status') == 'APPROVED' or client_req.get('req_status') == 'APPROVED'):
                WhiskerServerHandler.guest_last_seen[client_req['uuid']] = time.time()
                evts = self.db.get_guest_events(client_req['uuid'])
                req_event_id = qs.get('eventId', [''])[0] or qs.get('id', [''])[0]
                if req_event_id and not any(e.get('id') == req_event_id for e in (evts or [])):
                    print(f"⚠️ [Whisker Server] FCM tickle mismatch: Requested event ID '{req_event_id}' is NOT present in guest queue for '{client_req.get('name') or client_req['uuid']}'.")
                return self._send_json(evts or [])
            
            self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)
            return

        if path == '/api/host/me':
            token = self._get_auth_token()
            if not token:
                self._send_json({"error": "Access Key required", "status": "UNAUTHORIZED"}, 401)
                return
            s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
            host = None
            for h in self.db.get_hosts():
                if h.get('pin_token') == token:
                    host = h
                    break
            if host:
                if host.get('status') == 'APPROVED':
                    is_adm = (host.get("is_admin", 0) == 1)
                    return self._send_json({
                        "uuid": host["uuid"],
                        "username": host.get("username", ""),
                        "is_admin": 1 if is_adm else 0,
                        "role": "ADMIN" if is_adm else "COMPANION",
                        "status": host.get("status", "APPROVED"),
                        "allow_direct_calls": bool(host.get("allow_direct_calls", 0)),
                        "allow_widget": bool(host.get("allow_widget", 1)),
                        "server_name": s_name,
                        "servername": s_name
                    })
                elif host.get('status') == 'PENDING':
                    return self._send_json({"error": "Pairing request pending approval", "status": "PENDING", "role": "COMPANION"}, 401)
                else:
                    return self._send_json({"error": "Pairing request rejected", "status": "REJECTED"}, 403)

            # Check if token belongs to an approved member client from an invite
            client_req = next((r for r in self.db.get_requests() if r.get('token') == token), None)
            if client_req and (client_req.get('status') == 'APPROVED' or client_req.get('req_status') == 'APPROVED'):
                WhiskerServerHandler.guest_last_seen[client_req['uuid']] = time.time()
                return self._send_json({
                    "uuid": client_req["uuid"],
                    "username": client_req.get("name", "Member"),
                    "is_admin": 0,
                    "role": "MEMBER",
                    "status": "APPROVED",
                    "server_name": s_name,
                    "servername": s_name
                })

            self._send_json({"error": "Unauthorized access key", "status": "UNREGISTERED"}, 401)
            return

        if path in ['/api/host/fcm_config', '/api/fcm_config']:
            sa = get_service_account_file()
            fcm_key = self.db.get_setting('fcm_api_key') or ""
            s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
            if sa:
                return self._send_json({
                    "configured": True,
                    "project_id": sa.get("project_id", ""),
                    "sender_id": sa.get("project_number") or sa.get("project_id", ""),
                    "app_id": sa.get("app_id", ""),
                    "api_key": sa.get("api_key") or fcm_key,
                    "server_name": s_name,
                    "servername": s_name
                })
            else:
                return self._send_json({
                    "configured": False,
                    "error": "whisker-push-sa.json not found on Home Server",
                    "server_name": s_name,
                    "servername": s_name
                })

        if path == '/api/host/invites':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            invites = self.db.get_invites(host_uuid)
            return self._send_json(invites)

        if path == '/api/host/clients':
            token = self._get_auth_token()
            s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
            server_addr = self._get_server_url()
            now = time.time()
            with WhiskerServerHandler.streams_lock:
                active_uuids = {u for u, stms in WhiskerServerHandler.guest_streams.items() if stms}
                active_hosts = {u for u, stms in WhiskerServerHandler.host_streams.items() if stms}
            for u, ts in WhiskerServerHandler.guest_last_seen.items():
                if now - ts < 35:
                    active_uuids.add(u)
            for u, ts in WhiskerServerHandler.host_last_seen.items():
                if now - ts < 35:
                    active_hosts.add(u)

            # Check if caller is a host
            host = None
            if token:
                for h in self.db.get_hosts():
                    if h.get('pin_token') == token:
                        host = h
                        break

            if host and host.get('status') == 'APPROVED':
                host_uuid = host["uuid"]
                WhiskerServerHandler.host_last_seen[host_uuid] = now
                clients = self.db.get_approved_clients_for_host(host_uuid)
                seen_uuids = set()

                for c in clients:
                    u = c.get('uuid')
                    seen_uuids.add(u)
                    c['is_online'] = 1 if u in active_uuids else 0
                    c['server_name'] = s_name
                    c['server_address'] = server_addr

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

                return self._send_json(clients)

            # Check if caller is an approved Member client (joined via invite)
            client_req = next((r for r in self.db.get_requests() if r.get('token') == token), None) if token else None
            if client_req and (client_req.get('status') == 'APPROVED' or client_req.get('req_status') == 'APPROVED'):
                WhiskerServerHandler.guest_last_seen[client_req['uuid']] = now
                hosts = self.db.get_approved_hosts_for_client(client_req['uuid'])
                member_clients = []
                for h in hosts:
                    h_online = 1 if (h['uuid'] in active_hosts or (now - WhiskerServerHandler.host_last_seen.get(h['uuid'], 0) < 35)) else 0
                    member_clients.append({
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
                return self._send_json(member_clients)

            self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)
            return

        if path == '/api/host/message':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            msg_id_str = qs.get('id', [''])[0]
            if not msg_id_str:
                return self._send_json({"error": "Invalid or missing message id"}, 400)
            msg = self.db.get_message_by_id(msg_id_str)
            if not msg:
                return self._send_json({"error": "Message not found"}, 404)
            return self._send_json({
                "id": msg['id'],
                "clientUuid": msg['client_uuid'],
                "sender": msg['sender'],
                "text": msg['text'],
                "filePath": msg['file_path'],
                "createdAt": msg['created_at'],
                "previousMessageId": msg['previous_message_id']
            })

        # Guest endpoints
        if path == '/api/message':
            msg_id_str = qs.get('id', [''])[0]
            uuid = qs.get('uuid', [''])[0]
            token = qs.get('token', [''])[0]
            if not self._authorize_guest(uuid, token):
                return

            if not msg_id_str:
                return self._send_json({"error": "Invalid or missing message id"}, 400)
            msg = self.db.get_message_by_id(msg_id_str)
            if not msg or msg['client_uuid'] != uuid:
                return self._send_json({"error": "Message not found or unauthorized"}, 404)
            return self._send_json({
                "id": msg['id'],
                "clientUuid": msg['client_uuid'],
                "sender": msg['sender'],
                "text": msg['text'],
                "filePath": msg['file_path'],
                "createdAt": msg['created_at'],
                "previousMessageId": msg['previous_message_id']
            })

        if path in ['/api/file/download', '/api/file'] or path.startswith('/data/') or path.startswith('/files/') or 'sent_files' in path:
            rel_file = qs.get('path', [''])[0] or qs.get('file', [''])[0] or path
            if not rel_file:
                return self._send_json({"error": "Missing file path"}, 400)
            
            clean_rel = os.path.normpath(urllib.parse.unquote(rel_file)).lstrip('/')
            if clean_rel.startswith('..') or '..' in clean_rel.split(os.sep):
                return self._send_json({"error": "Invalid file path"}, 403)

            storage_root = self.db.get_setting('files_storage_path') or getattr(WhiskerServerHandler, 'files_storage_path', None) or '/data/files'
            candidate_roots = [
                storage_root,
                os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'files')),
                '/data/files',
                './data/files'
            ]
            full_path = None
            for root in candidate_roots:
                if root:
                    p = os.path.join(root, clean_rel)
                    if os.path.isfile(p):
                        full_path = p
                        break

            # Fallback search if exact path not found: search by basename or stripped-timestamp basename in storage_root
            if not full_path or not os.path.isfile(full_path):
                target_base = os.path.basename(clean_rel)
                target_sanitized = sanitize_filename(target_base)
                stripped_base = re.sub(r'^\d+_', '', target_base)
                stripped_sanitized = sanitize_filename(stripped_base)
                search_targets = {target_base, target_sanitized, stripped_base, stripped_sanitized}

                for root in candidate_roots:
                    if root and os.path.isdir(root):
                        for dirpath, _, filenames in os.walk(root):
                            for fn in filenames:
                                fn_clean = sanitize_filename(fn)
                                fn_stripped = sanitize_filename(re.sub(r'^\d+_', '', fn))
                                if fn in search_targets or fn_clean in search_targets or fn_stripped in search_targets:
                                    full_path = os.path.join(dirpath, fn)
                                    break
                            if full_path:
                                break
                    if full_path:
                        break

            if not full_path or not os.path.isfile(full_path):
                return self._send_json({"error": "File not found"}, 404)

            import mimetypes
            ctype, _ = mimetypes.guess_type(full_path)
            if not ctype:
                ctype = 'application/octet-stream'

            fname = qs.get('name', [''])[0] or os.path.basename(full_path)
            try:
                with open(full_path, 'rb') as f:
                    content = f.read()
                self.send_response(200)
                self._send_cors_headers()
                self.send_header('Content-Type', ctype)
                self.send_header('Content-Length', str(len(content)))
                disp = 'inline' if (ctype.startswith('image/') or ctype.startswith('text/') or ctype == 'application/pdf') else 'attachment'
                self.send_header('Content-Disposition', f'{disp}; filename="{fname}"')
                self.end_headers()
                self.wfile.write(content)
                return
            except Exception as e:
                return self._send_json({"error": f"Error reading file: {e}"}, 500)

        if path == '/api/messages':
            uuid = qs.get('uuid', [''])[0]
            token = qs.get('token', [''])[0]
            if not self._authorize_guest(uuid, token):
                return
            msgs = self.db.get_messages(uuid)
            return self._send_json(msgs)

        if path in ['/api/guest/events', '/api/guest/pending_events']:
            uuid = qs.get('uuid', [''])[0]
            token = qs.get('token', [''])[0]
            if not self._authorize_guest(uuid, token):
                return
            events = self.db.get_guest_events(uuid)
            if events:
                self.db.delete_guest_events(uuid)
            return self._send_json(events)

        if path == '/api/events':
            uuid = qs.get('uuid', [''])[0]
            token = qs.get('token', [''])[0]

            req = self.db.get_request(uuid)
            if not req:
                return self._send_json({"error": "No registration found"}, 403)

            return self._handle_guest_sse(uuid)

        if path in ['/api/embed/session', '/api/guest/session']:
            uuid = qs.get('uuid', [''])[0]
            if not uuid:
                return self._send_json({"error": "Missing uuid"}, 400)
            req = self.db.get_request(uuid)
            if not req:
                return self._send_json({"status": "NOT_FOUND"}, 404)

            s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
            is_approved = (req.get('status') == 'APPROVED')

            now = time.time()
            with WhiskerServerHandler.streams_lock:
                active_hosts = {u for u, stms in WhiskerServerHandler.host_streams.items() if stms}
            for u, ts in WhiskerServerHandler.host_last_seen.items():
                if now - ts < 35:
                    active_hosts.add(u)
            
            target_uuid = req.get('target_host_uuid')
            target_h = self.db.get_host(target_uuid) if target_uuid else None
            if target_uuid:
                host_online = (target_uuid in active_hosts)
            else:
                host_online = len(active_hosts) > 0

            return self._send_json({
                "uuid": req["uuid"],
                "name": req["name"],
                "status": req["status"],
                "token": req.get("token", "") if is_approved else "",
                "client_type": req.get("client_type", "EMBED"),
                "server_name": s_name,
                "target_host_uuid": target_uuid or "",
                "target_host_name": (target_h.get("username") if target_h else "") or "",
                "host_online": host_online if is_approved else False
            })

        if path == '/api/client/hosts':
            uuid = qs.get('uuid', [''])[0]
            token = qs.get('token', [''])[0]
            if not self._authorize_guest(uuid, token):
                return
            hosts = self.db.get_approved_hosts_for_client(uuid)
            res = []
            for h in hosts:
                res.append({
                    "uuid": h['uuid'],
                    "username": h.get('username') or 'Primary Device',
                    "is_admin": h.get('is_admin', 0)
                })
            return self._send_json(res)


        if path in ['/api/call/status', '/api/call_status']:
            call_id = qs.get('call_id', [''])[0] or qs.get('id', [''])[0]
            uuid = qs.get('uuid', [''])[0] or qs.get('client_uuid', [''])[0]
            session = self._get_call_session(call_id=call_id, uuid=uuid)
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

        return self._send_json({"error": "Endpoint not found"}, 404)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        clean_parts = [p for p in parsed.path.split('/') if p]
        path = '/' + '/'.join(clean_parts)
        body = self._read_json_body()

        # Admin POST Endpoints
        if path == '/api/admin/login':
            pwd = (body.get('password') or '').strip()
            stored_pw = self.db.get_setting('admin_password') or getattr(WhiskerServerHandler, 'admin_password', None) or 'admin'
            if pwd and pwd == stored_pw:
                import uuid as uuid_lib
                token = f"adm_{int(time.time())}_{uuid_lib.uuid4().hex[:16]}"
                with WhiskerServerHandler.streams_lock:
                    WhiskerServerHandler.admin_tokens[token] = time.time() + 86400  # 24h
                return self._send_json({"success": True, "token": token})
            return self._send_json({"error": "Invalid administrator password"}, 401)

        if path == '/api/admin/config':
            if not self._is_admin_authenticated():
                return self._send_json({"error": "Unauthorized"}, 401)
            s_name = (body.get('server_name') or '').strip()
            if s_name:
                self.db.set_setting('server_name', s_name)
                WhiskerServerHandler.server_name = s_name
            f_path = (body.get('files_storage_path') or '').strip()
            if f_path:
                self.db.set_setting('files_storage_path', f_path)
            new_pwd = (body.get('new_admin_password') or '').strip()
            if new_pwd:
                self.db.set_setting('admin_password', new_pwd)
                WhiskerServerHandler.admin_password = new_pwd
            return self._send_json({"success": True})

        if path == '/api/admin/hosts/switch-admin':
            if not self._is_admin_authenticated():
                return self._send_json({"error": "Unauthorized"}, 401)
            host_uuid = (body.get('host_uuid') or '').strip()
            if not host_uuid:
                return self._send_json({"error": "host_uuid is required"}, 400)
            success = self.db.transfer_admin(host_uuid)
            return self._send_json({"success": bool(success), "new_admin_uuid": host_uuid})

        if path == '/api/admin/hosts/delete':
            if not self._is_admin_authenticated():
                return self._send_json({"error": "Unauthorized"}, 401)
            host_uuid = (body.get('host_uuid') or '').strip()
            if not host_uuid:
                return self._send_json({"error": "host_uuid is required"}, 400)
            self.db.delete_host(host_uuid)
            return self._send_json({"success": True})

        if path == '/api/admin/clients/delete':
            if not self._is_admin_authenticated():
                return self._send_json({"error": "Unauthorized"}, 401)
            client_uuid = (body.get('client_uuid') or '').strip()
            if not client_uuid:
                return self._send_json({"error": "client_uuid is required"}, 400)
            self.db.delete_client_completely(client_uuid)
            return self._send_json({"success": True})

        if path == '/api/admin/invites/create':
            if not self._is_admin_authenticated():
                return self._send_json({"error": "Unauthorized"}, 401)
            name = (body.get('name') or 'Admin Invite').strip()
            code = ''.join(random.choices(string.ascii_uppercase + string.digits, k=8))
            admin_host = self.db.get_admin_host()
            host_uuid = admin_host['uuid'] if admin_host else None
            self.db.add_invite(code, name, host_uuid)
            return self._send_json({
                "success": True,
                "code": code,
                "url": f"{self._get_server_url()}/invite/{code}"
            })

        if path == '/api/admin/invites/delete':
            if not self._is_admin_authenticated():
                return self._send_json({"error": "Unauthorized"}, 401)
            code = (body.get('code') or '').strip()
            if code:
                self.db.delete_invite(code)
            return self._send_json({"success": True})

        # Service account JSON upload for remote home servers
        if path in ['/api/upload_service_account', '/api/upload_sa']:
            sa_data = body if isinstance(body, dict) and ('type' in body or 'project_id' in body) else body.get('service_account')
            if isinstance(sa_data, str):
                try:
                    sa_data = json.loads(sa_data)
                except Exception:
                    pass
            if not isinstance(sa_data, dict) or not sa_data.get('project_id'):
                return self._send_json({"error": "Invalid service account format. Must be a valid JSON with 'project_id'."}, 400)

            sa_filename = 'whisker-push-sa.json'
            server_dir = os.path.dirname(os.path.abspath(__file__))
            target_path = os.path.join(server_dir, sa_filename)
            try:
                with open(target_path, 'w', encoding='utf-8') as f:
                    json.dump(sa_data, f, indent=2)
                cwd_target = os.path.join(os.getcwd(), sa_filename)
                if cwd_target != target_path:
                    try:
                        with open(cwd_target, 'w', encoding='utf-8') as f:
                            json.dump(sa_data, f, indent=2)
                    except Exception:
                        pass

                if WhiskerServerHandler.fcm:
                    WhiskerServerHandler.fcm._cached_token = None
                    WhiskerServerHandler.fcm._token_expiry = 0

                return self._send_json({
                    "success": True,
                    "message": "Service account credentials saved successfully.",
                    "project_id": sa_data.get("project_id"),
                    "client_email": sa_data.get("client_email")
                })
            except Exception as e:
                return self._send_json({"error": f"Failed to save service account: {str(e)}"}, 500)

        if path == '/api/register_mobile':
            username = body.get('username')
            pin_token = body.get('pin_token')
            push_token = body.get('push_token')
            if not username or not pin_token:
                return self._send_json({"error": "username and pin_token required"}, 400)
            
            import uuid as uuid_lib
            hosts = self.db.get_hosts()
            existing = next((h for h in hosts if h['pin_token'] == pin_token), None)
            
            clean_push_token = str(push_token).strip() if (push_token and str(push_token).strip() and not str(push_token).startswith('Fetching') and not str(push_token).startswith('device-push-token-') and not str(push_token).startswith('mock-')) else None

            if existing:
                updates = {"username": username}
                if clean_push_token:
                    updates["push_token"] = clean_push_token
                self.db.update_host(existing['uuid'], **updates)
                s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
                return self._send_json({
                    "status": existing['status'],
                    "host_uuid": existing['uuid'],
                    "username": username,
                    "is_admin": existing.get('is_admin', 0),
                    "server_name": s_name,
                    "servername": s_name
                })

            host_uuid = str(uuid_lib.uuid4())
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
                    # Add host event for admin specifically
                    self.db.add_host_event(admin['uuid'], event_id, "mobile_pairing_request", payload)
                    self._notify_host_streams(admin["uuid"], "mobile_pairing_request", payload)
                    
                    admin_push = admin.get('push_token')
                    if admin_push:
                        self._send_fcm_tickle(admin_push, event_id=event_id, event_type="mobile_pairing", username=admin.get('username'))

            s_name = self.db.get_setting('server_name') or getattr(WhiskerServerHandler, 'server_name', 'Whisker Home Server')
            return self._send_json({
                "status": status,
                "host_uuid": host_uuid,
                "username": username,
                "is_admin": is_admin,
                "server_name": s_name,
                "servername": s_name
            })


        # Host & Member endpoints
        if path == '/api/host/register_push':
            push_token = body.get('pushToken') or body.get('token') or body.get('fcmToken') or self.headers.get('X-Push-Token', '')
            clean_token = str(push_token).strip() if (push_token and str(push_token).strip() and not str(push_token).startswith('Fetching') and not str(push_token).startswith('device-push-token-') and not str(push_token).startswith('mock-')) else None

            token = self._get_auth_token()
            host = None
            if token:
                for h in self.db.get_hosts():
                    if h.get('pin_token') == token:
                        host = h
                        break

            if host and host.get('status') == 'APPROVED':
                if clean_token:
                    self.db.update_host(host["uuid"], push_token=clean_token)
                    print(f"[Whisker Server] 📲 Host FCM Push Token registered: {clean_token[:16]}... for host '{host.get('username') or host['uuid']}'")
                    return self._send_json({"message": "Push token registered successfully"})
                return self._send_json({"error": "No valid push token provided"}, 400)

            # Check if token belongs to an approved member client from an invite
            client_req = next((r for r in self.db.get_requests() if r.get('token') == token), None) if token else None
            if client_req and (client_req.get('status') == 'APPROVED' or client_req.get('req_status') == 'APPROVED'):
                if clean_token:
                    self.db.update_request_push_token(client_req['uuid'], clean_token)
                    print(f"[Whisker Server] 📲 Member FCM Push Token registered: {clean_token[:16]}... for member '{client_req.get('name') or client_req['uuid']}'")
                    return self._send_json({"message": "Push token registered successfully"})
                return self._send_json({"error": "No valid push token provided"}, 400)

            return self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)

        if path == '/api/host/fcm_config':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            api_key = body.get('api_key')
            if api_key:
                self.db.set_setting('fcm_api_key', api_key)
                return self._send_json({"message": "FCM API Key updated successfully"})
            return self._send_json({"error": "Missing api_key"}, 400)

        if path == '/api/host/direct_calls':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            allow = 1 if body.get('allow_direct_calls') or body.get('enabled') else 0
            self.db.update_host(host_uuid, allow_direct_calls=allow)
            print(f"⚡ [Direct Calls] Host '{host.get('username') or host_uuid}' updated allow_direct_calls to {bool(allow)}")
            return self._send_json({"message": "Direct calls setting updated", "allow_direct_calls": bool(allow)})

        if path in ['/api/host/widget_access', '/api/host/allow_widget']:
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
            return self._send_json({"message": "Widget access setting updated", "allow_widget": bool(allow)})

        if path == '/api/host/ack':
            token = self._get_auth_token()
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
                        print(f"⚠️ [Whisker Server] ACK warning: Event ID '{event_id}' requested to be cleared by host '{host_label}', but it was NOT present in the queue on this home server (possible duplicate ACK, or tickle event belonging to another home server).")
                    else:
                        print(f"🧹 [Whisker Server] Host '{host_label}' acknowledged and cleared event '{event_id}'")
                else:
                    print(f"⚠️ [Whisker Server] ACK warning: Empty eventId received from host '{host_label}'")
                return self._send_json({"message": "Event cleared"})

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
                return self._send_json({"message": "Event cleared"})

            self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)
            return

        if path == '/api/host/create_invite':
            host = self._authorize_host()
            if not host: return
            if host.get('status') != 'APPROVED':
                return self._send_json({"error": "Approved host privileges required to create invites"}, 403)
            host_uuid = host["uuid"]
            name = body.get('name', 'Invited Guest')
            code = body.get('code')
            if not code:
                code = ''.join(random.choices(string.ascii_letters + string.digits, k=5))

            self.db.add_invite(code, name, host_uuid)
            
            proto = 'https' if self.headers.get('X-Forwarded-Proto') == 'https' else 'http'
            host_hdr = self.headers.get('Host') or f"localhost:{self.server.server_port}"
            invite_url = f"{proto}://{host_hdr}/invite/{code}"
            host_label = host.get('username') or host_uuid
            print(f"🎟️ [Whisker Invite Created] Code: '{code}' for '{name}' by host '{host_label}'")
            print(f"👉 Full Invite URL: {invite_url}")

            return self._send_json({
                "success": True,
                "code": code,
                "name": name,
                "url": invite_url,
                "created_at": int(time.time() * 1000)
            })

        if path == '/api/host/delete_invite':
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
            return self._send_json({"success": True})

        if path == '/api/host/transfer_admin':
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
            return self._send_json({"success": True, "new_admin_uuid": new_admin_uuid})

        if path in ['/api/host/delete_client', '/api/host/remove_client', '/api/host/remove_contact', '/api/host/delete_host']:
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            target_uuid = body.get('uuid') or body.get('clientUuid') or body.get('target_uuid') or body.get('host_uuid')
            if not target_uuid:
                return self._send_json({"error": "Missing target uuid"}, 400)

            is_admin = (host.get('is_admin', 0) == 1)
            host_name = host.get('username') or f"Host ({host_uuid[:6]})"

            # Check if target is a companion mobile app (hosts table)
            target_host = self.db.get_host(target_uuid)
            if target_host:
                target_name = target_host.get('username') or f"Mobile Device ({target_uuid[:6]})"
                if is_admin or target_uuid == host_uuid:
                    self.db.delete_host(target_uuid)
                    with WhiskerServerHandler.streams_lock:
                        WhiskerServerHandler.host_streams.pop(target_uuid, None)
                    WhiskerServerHandler.host_last_seen.pop(target_uuid, None)
                    print(f"🗑️ [Whisker Server] Removed mobile contact '{target_name}' (UUID: {target_uuid}) by host '{host_name}' (UUID: {host_uuid})")
                    return self._send_json({"success": True, "message": f"Mobile contact '{target_name}' removed"})
                else:
                    return self._send_json({"error": "Unauthorized to remove mobile host"}, 403)

            # Check if target is a client / approved caller (requests table)
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
            return self._send_json({"success": True, "message": f"Approved caller '{target_name}' removed"})

        if path == '/api/host/claim_remote_invite':
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
                req_data = json.dumps({"code": code, "name": my_name}).encode('utf-8')
                req = urllib.request.Request(
                    f"{remote_base}/api/claim_invite",
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

        if path == '/api/host/response':
            token = self._get_auth_token()
            host = None
            if token:
                for h in self.db.get_hosts():
                    if h.get('pin_token') == token:
                        host = h
                        break

            caller_client = None
            if not host and token:
                caller_client = next((r for r in self.db.get_requests() if r.get('token') == token), None)

            if host and host.get('status') != 'APPROVED':
                return self._send_json({"error": "Host not approved", "status": "UNAUTHORIZED"}, 401)

            if not host and (not caller_client or (caller_client.get('status') != 'APPROVED' and caller_client.get('req_status') != 'APPROVED')):
                return self._send_json({"error": "Unauthorized access key", "status": "UNAUTHORIZED"}, 401)

            client_uuid = body.get('clientUuid') or body.get('uuid')
            resp_type = body.get('type')
            resp_event = body.get('event')

            if caller_client:
                member_uuid = caller_client["uuid"]
                target_uuid = client_uuid
                approved_hosts = self.db.get_approved_hosts_for_client(member_uuid)
                target_host = next((h for h in approved_hosts if h['uuid'] == target_uuid), None)
                if not target_host:
                    t_host = self.db.get_host(target_uuid)
                    if t_host and any(h['uuid'] == t_host['uuid'] for h in approved_hosts):
                        target_host = t_host

                if not target_host:
                    return self._send_json({"error": "Host not paired with this member client"}, 403)

                if resp_type in ['chat_message', 'message'] or (body.get('sender') == 'phone' or 'text' in body):
                    text = body.get('text', '')
                    file_path = body.get('filePath') or body.get('file')
                    if text or file_path:
                        msg_id, prev_id = self.db.add_message(member_uuid, target_host['uuid'], 'guest', text, file_path)
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
            is_approved = (target_host is not None) or any(c['uuid'] == client_uuid for c in approved_clients) or ((host.get('is_admin', 0) == 1) and is_req_approved)

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
                if file_path and (file_path.startswith('data:') or (isinstance(file_path, str) and len(file_path) > 100 and not file_path.startswith('/api/file'))):
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
                        print(f"📁 [Whisker Server File Upload] Sender: '{sender_snd}' -> Receiver: '{target_rec}' | File: '{safe_fn}' ({len(f_bytes)} bytes)")
                        file_path = f"/api/file/download?path={urllib.parse.quote(rel_p)}&name={urllib.parse.quote(safe_fn)}"
                        body['filePath'] = file_path
                        body['file'] = file_path
                        body['fileName'] = safe_fn
                        body['filename'] = safe_fn
                        if not text or text == orig_fname:
                            text = safe_fn
                    except Exception as e:
                        print(f"Base64 auto-save error in /api/host/response: {e}")
                if client_uuid and (text or file_path):
                    msg_id, prev_id = self.db.add_message(client_uuid, host_uuid, 'phone', text, file_path)
                    body['messageId'] = msg_id
                    body['id'] = msg_id
                    body['previousMessageId'] = prev_id
                    body['event'] = 'message'
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

        # Guest endpoints
        if path == '/api/register':
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

        if path == '/api/claim_invite':
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
                client_uuid = f"cli_{''.join(random.choices(string.ascii_lowercase + string.digits, k=9))}"
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
                "status": "APPROVED",
                "client_type": client_type,
                "server_name": s_name,
                "servername": s_name
            })

        if path == '/api/file/upload':
            raw_body = self._read_raw_body()
            content_type = self.headers.get('Content-Type', '')
            qs = urllib.parse.parse_qs(parsed.query)

            file_bytes = b''
            filename = ''
            receiver_str = ''
            client_uuid = ''
            client_token = ''
            text_caption = ''

            if 'multipart/form-data' in content_type:
                import re
                boundary_match = re.search(r'boundary=([^;]+)', content_type)
                if boundary_match:
                    boundary = boundary_match.group(1).strip().strip('"').encode('utf-8')
                    parts = raw_body.split(b'--' + boundary)
                    for part in parts:
                        if not part or part == b'--\r\n' or part == b'--':
                            continue
                        headers_and_content = part.split(b'\r\n\r\n', 1)
                        if len(headers_and_content) == 2:
                            part_headers = headers_and_content[0].decode('utf-8', errors='ignore')
                            part_data = headers_and_content[1].rstrip(b'\r\n')
                            disp_match = re.search(r'name="([^"]+)"', part_headers)
                            name_field = disp_match.group(1) if disp_match else ''
                            fn_match = re.search(r'filename="([^"]+)"', part_headers)
                            if fn_match:
                                filename = fn_match.group(1)
                                file_bytes = part_data
                            elif name_field == 'file':
                                file_bytes = part_data
                            elif name_field in ['filename', 'fileName']:
                                filename = part_data.decode('utf-8', errors='ignore')
                            elif name_field in ['receiver', 'contact', 'receiver_uuid', 'target_host_uuid']:
                                receiver_str = part_data.decode('utf-8', errors='ignore')
                            elif name_field == 'uuid':
                                client_uuid = part_data.decode('utf-8', errors='ignore')
                            elif name_field == 'token':
                                client_token = part_data.decode('utf-8', errors='ignore')
                            elif name_field == 'text':
                                text_caption = part_data.decode('utf-8', errors='ignore')
            elif isinstance(body, dict) and body:
                b64_content = body.get('content') or body.get('file') or body.get('data') or ''
                filename = body.get('filename') or body.get('fileName') or body.get('name') or ''
                receiver_str = body.get('receiver') or body.get('contact') or body.get('receiver_uuid') or body.get('host_uuid') or ''
                client_uuid = body.get('uuid') or ''
                client_token = body.get('token') or ''
                text_caption = body.get('text') or ''

                if b64_content:
                    if ',' in b64_content:
                        b64_content = b64_content.split(',', 1)[1]
                    import base64
                    try:
                        file_bytes = base64.b64decode(b64_content)
                    except Exception as e:
                        return self._send_json({"error": f"Invalid base64 encoding: {e}"}, 400)
            else:
                file_bytes = raw_body
                filename = self.headers.get('X-Filename') or qs.get('filename', [''])[0] or qs.get('name', [''])[0]
                receiver_str = self.headers.get('X-Receiver') or qs.get('receiver', [''])[0] or qs.get('contact', [''])[0]
                client_uuid = self.headers.get('X-Client-Uuid') or qs.get('uuid', [''])[0]
                client_token = self.headers.get('X-Client-Token') or qs.get('token', [''])[0]
                text_caption = qs.get('text', [''])[0]

            if not file_bytes:
                return self._send_json({"error": "No file content received"}, 400)

            if not filename:
                filename = f"upload_{int(time.time())}.bin"

            auth_token = self._get_auth_token()
            is_host_caller = False
            host_caller = None
            if auth_token:
                for h in self.db.get_hosts():
                    if h.get('pin_token') == auth_token:
                        host_caller = h
                        is_host_caller = True
                        break

            if not is_host_caller and not client_uuid:
                client_uuid = qs.get('uuid', [''])[0]
            if not is_host_caller and not client_token:
                client_token = qs.get('token', [''])[0]

            # Case 1: Caller is Host (mobile app or server admin)
            if is_host_caller and host_caller:
                sender_name = host_caller.get('username') or 'Mobile App'
                sender_uuid = host_caller['uuid']

                target_client = None
                target_host = None
                if receiver_str:
                    target_host = self.db.get_host(receiver_str)
                    target_client = self.db.get_request(receiver_str)
                if not target_client and not target_host:
                    approved_clients = self.db.get_approved_clients_for_host(sender_uuid)
                    if approved_clients:
                        target_client = approved_clients[0]
                    else:
                        all_c = self.db.get_clients()
                        if all_c: target_client = all_c[0]

                receiver_username = ''
                receiver_uuid = ''
                if target_host:
                    receiver_username = target_host.get('username') or target_host['uuid']
                    receiver_uuid = target_host['uuid']
                elif target_client:
                    receiver_username = target_client.get('name') or target_client['uuid']
                    receiver_uuid = target_client['uuid']
                else:
                    receiver_username = receiver_str or 'receiver'
                    receiver_uuid = receiver_str or 'unknown'

                full_path, rel_path, safe_fname, f_size = save_uploaded_file(self.db, receiver_username, sender_name, filename, file_bytes)
                print(f"📁 [Whisker Server File Upload] Sender: '{sender_name}' -> Receiver: '{receiver_username}' | File: '{safe_fname}' ({f_size} bytes)")
                download_url = f"/api/file/download?path={urllib.parse.quote(rel_path)}&name={urllib.parse.quote(safe_fname)}"
                msg_text = text_caption if text_caption else safe_fname
                msg_id, prev_id = self.db.add_message(receiver_uuid, sender_uuid, 'phone', msg_text, download_url)
                event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"

                payload = {
                    "event": "message",
                    "eventId": event_id,
                    "messageId": msg_id,
                    "previousMessageId": prev_id,
                    "uuid": sender_uuid,
                    "clientUuid": sender_uuid,
                    "name": sender_name,
                    "text": msg_text,
                    "file": download_url,
                    "filePath": download_url,
                    "fileName": safe_fname,
                    "fileSize": f_size,
                    "timestamp": int(time.time() * 1000),
                    "server_address": self._get_server_url()
                }

                if target_host:
                    self.db.add_host_event(receiver_uuid, event_id, "message", payload)
                    self._notify_host_streams(receiver_uuid, "message", payload)
                    if target_host.get('push_token'):
                        self._send_fcm_tickle(target_host['push_token'], event_id=event_id, event_type="message", username=target_host.get('username'))
                else:
                    self.db.add_guest_event(sender_uuid, event_id, receiver_uuid, "message", payload)
                    self._notify_guest_streams(receiver_uuid, "message", payload)

                return self._send_json({
                    "success": True,
                    "file_path": rel_path,
                    "download_url": download_url,
                    "url": download_url,
                    "full_url": f"{self._get_server_url()}{download_url}",
                    "filename": safe_fname,
                    "size": f_size,
                    "messageId": msg_id,
                    "eventId": event_id,
                    "receiver": receiver_username
                })

            # Case 2: Caller is Client / Guest (web, embed, or CLI)
            if not self._authorize_guest(client_uuid, client_token):
                return

            req = self.db.get_request(client_uuid)
            sender_name = req.get('name') if req else 'Guest'
            target_host = self._resolve_target_host(client_uuid, receiver_str)
            if not target_host:
                return self._send_json({"error": "No reachable host found for this client"}, 403)

            host_uuid = target_host['uuid']
            receiver_username = target_host.get('username') or host_uuid
            push_token = target_host.get('push_token')

            full_path, rel_path, safe_fname, f_size = save_uploaded_file(self.db, receiver_username, sender_name, filename, file_bytes)
            print(f"📁 [Whisker Server File Upload] Sender: '{sender_name}' -> Receiver: '{receiver_username}' | File: '{safe_fname}' ({f_size} bytes)")
            download_url = f"/api/file/download?path={urllib.parse.quote(rel_path)}&name={urllib.parse.quote(safe_fname)}"
            msg_text = text_caption if text_caption else safe_fname
            msg_id, prev_id = self.db.add_message(client_uuid, host_uuid, 'guest', msg_text, download_url)
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"

            payload = {
                "event": "message",
                "eventId": event_id,
                "messageId": msg_id,
                "previousMessageId": prev_id,
                "uuid": client_uuid,
                "token": client_token,
                "text": msg_text,
                "file": download_url,
                "filePath": download_url,
                "fileName": safe_fname,
                "fileSize": f_size,
                "name": sender_name,
                "timestamp": int(time.time() * 1000),
                "server_address": self._get_server_url()
            }
            self.db.add_host_event(host_uuid, event_id, "message", payload)
            self._notify_host_streams(host_uuid, "message", payload)

            if push_token:
                self._send_fcm_tickle(push_token, event_id=event_id, event_type="message", username=target_host.get('username'))

            return self._send_json({
                "success": True,
                "file_path": rel_path,
                "download_url": download_url,
                "url": download_url,
                "full_url": f"{self._get_server_url()}{download_url}",
                "filename": safe_fname,
                "size": f_size,
                "messageId": msg_id,
                "eventId": event_id,
                "receiver": receiver_username
            })

        if path == '/api/message':
            uuid = body.get('uuid')
            token = body.get('token')
            text = body.get('text')
            file_data = body.get('file') or body.get('filePath')

            if not self._authorize_guest(uuid, token):
                return

            contact_uuid = body.get('contact') or body.get('host_uuid')
            host = self._resolve_target_host(uuid, contact_uuid)
            if not host:
                return self._send_json({"error": "No reachable host found for this client"}, 403)
            
            host_uuid = host['uuid']
            push_token = host.get('push_token')

            if not text and not file_data:
                return self._send_json({"error": "Missing message content"}, 400)

            # Auto-save base64 data to daily rotating folder on home server disk
            if file_data and (file_data.startswith('data:') or (isinstance(file_data, str) and len(file_data) > 100 and not file_data.startswith('/api/file'))):
                try:
                    import base64
                    b64_content = file_data
                    if ',' in b64_content:
                        b64_content = b64_content.split(',', 1)[1]
                    f_bytes = base64.b64decode(b64_content)
                    orig_fname = body.get('filename') or body.get('fileName') or text or f"file_{int(time.time())}.bin"
                    receiver_name = host.get('username') or host_uuid
                    req_info = self.db.get_request(uuid)
                    sender_name_info = (body.get('name') or '').strip() or (req_info.get('name') if req_info else 'Guest')
                    _, rel_path, safe_fname, f_size = save_uploaded_file(self.db, receiver_name, sender_name_info, orig_fname, f_bytes)
                    print(f"📁 [Whisker Server File Upload] Sender: '{sender_name_info}' -> Receiver: '{receiver_name}' | File: '{safe_fname}' ({f_size} bytes)")
                    file_data = f"/api/file/download?path={urllib.parse.quote(rel_path)}&name={urllib.parse.quote(safe_fname)}"
                    if not text or text == orig_fname:
                        text = safe_fname
                except Exception as e:
                    print(f"Base64 auto-storage error in /api/message: {e}")

            msg_text = text if text is not None else (file_data if file_data else "")
            msg_id, prev_id = self.db.add_message(uuid, host_uuid, 'guest', msg_text, file_data)
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            req = self.db.get_request(uuid)
            sender_name = (body.get('name') or '').strip() or (req.get('name') if req else 'Guest')
            payload = {
                "event": "message",
                "eventId": event_id,
                "messageId": msg_id,
                "previousMessageId": prev_id,
                "uuid": uuid,
                "token": token,
                "text": msg_text,
                "file": file_data,
                "filePath": file_data,
                "name": sender_name,
                "timestamp": int(time.time() * 1000),
                "server_address": self._get_server_url()
            }
            self.db.add_host_event(host_uuid, event_id, "message", payload)
            self._notify_host_streams(host_uuid, "message", payload)
            
            if push_token:
                self._send_fcm_tickle(push_token, event_id=event_id, event_type="message", username=host.get('username'))

            return self._send_json({
                "status": "delivered",
                "id": msg_id,
                "messageId": msg_id,
                "previousMessageId": prev_id,
                "eventId": event_id,
                "host_uuid": host_uuid,
                "host_name": host.get('username')
            })

        if path == '/api/call':
            uuid = body.get('uuid')
            token = body.get('token')
            is_direct = body.get('is_direct_call', False) or body.get('direct_call', False)
            
            host = None
            if is_direct:
                # Find an approved host that allows direct calls
                hosts = self.db.get_hosts()
                target_host_uuid = body.get('contact') or body.get('host_uuid')
                if target_host_uuid:
                    matched = [h for h in hosts if h['uuid'] == target_host_uuid and h.get('allow_direct_calls', 0) == 1 and h.get('status') == 'APPROVED']
                    if matched: host = matched[0]
                if not host:
                    matched = [h for h in hosts if h.get('allow_direct_calls', 0) == 1 and h.get('status') == 'APPROVED']
                    if matched: host = matched[0]
                if not host:
                    return self._send_json({"error": "No host currently allows direct calls without prior approval."}, 403)
            else:
                if not self._authorize_guest(uuid, token):
                    return
                contact_uuid = body.get('contact') or body.get('host_uuid')
                host = self._resolve_target_host(uuid, contact_uuid)
                if not host:
                    return self._send_json({"error": "No reachable host found for this client"}, 403)
            
            host_uuid = host['uuid']
            push_token = host.get('push_token')

            call_id = body.get('call_id') or f"call_{int(time.time()*1000)}_{random.randint(1000,9999)}"
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            sdp_str = body.get('sdp', '')
            is_vid = body.get('video', False)
            sdp_len = len(sdp_str)
            has_audio = "m=audio" in sdp_str
            has_video = "m=video" in sdp_str
            req = self.db.get_request(uuid)
            caller_name = (body.get('name') or '').strip() or (req.get('name') if req else 'Guest')
            reason_str = (body.get('reason') or '').strip() or (req.get('reason') if req else 'WebRTC Call')
            print(f"📞 [WebRTC Call] {'⚡ DIRECT CALL' if is_direct else 'Guest'} '{uuid}' ({caller_name}) initiated call | Call ID: {call_id} | Host: '{host.get('username') or host_uuid}' | Video: {is_vid} | Reason: '{reason_str}' | SDP Len: {sdp_len}")

            self._register_call(call_id, host_uuid, uuid, caller_name=caller_name, initiator="guest", is_direct=is_direct, video=is_vid)

            payload = {
                "event": "call",
                "eventId": event_id,
                "call_id": call_id,
                "uuid": uuid,
                "token": token or "",
                "name": caller_name,
                "video": is_vid,
                "sdp": sdp_str,
                "is_direct_call": is_direct,
                "reason": reason_str,
                "server_address": self._get_server_url()
            }
            self.db.add_host_event(host_uuid, event_id, "call", payload)
            self._notify_host_streams(host_uuid, "call", payload)
            
            if push_token:
                self._send_fcm_tickle(push_token, event_id=event_id, event_type="call", username=host.get('username'))

            return self._send_json({"status": "dialing", "eventId": event_id, "host_uuid": host_uuid, "call_id": call_id})

        if path == '/api/hangup':
            uuid = body.get('uuid')
            token = body.get('token')
            call_id = body.get('call_id')
            is_direct = body.get('is_direct_call', False) or body.get('direct_call', False)
            host = None
            if is_direct or not token:
                hosts = self.db.get_hosts()
                contact_uuid = body.get('contact') or body.get('host_uuid')
                if contact_uuid:
                    matched = [h for h in hosts if h['uuid'] == contact_uuid]
                    if matched: host = matched[0]
                if not host:
                    matched = [h for h in hosts if h.get('status') == 'APPROVED']
                    if matched: host = matched[0]
            else:
                if not self._authorize_guest(uuid, token):
                    return
                contact_uuid = body.get('contact') or body.get('host_uuid')
                host = self._resolve_target_host(uuid, contact_uuid)

            if not host:
                return self._send_json({"error": "No reachable host found for this client"}, 403)
            
            host_uuid = host['uuid']
            push_token = host.get('push_token')

            print(f"🛑 [WebRTC Hangup] Client '{uuid}' requested call hangup to host '{host.get('username') or host_uuid}' (Call ID: {call_id})")
            self._update_call_status(call_id, "ENDED", client_uuid=uuid, host_uuid=host_uuid)

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "hangup",
                "eventId": event_id,
                "call_id": call_id,
                "uuid": uuid,
                "token": token or "",
                "status": body.get("status", "ended"),
                "reason": body.get("reason", "ended")
            }
            self.db.add_host_event(host_uuid, event_id, "hangup", payload)
            self._notify_host_streams(host_uuid, "hangup", payload)
            
            if push_token:
                self._send_fcm_tickle(push_token, event_id=event_id, event_type="hangup", username=host.get('username'))

            return self._send_json({"status": "hungup", "call_id": call_id})

        if path == '/api/signal':
            uuid = body.get('uuid')
            token = body.get('token')
            call_id = body.get('call_id')
            is_direct = body.get('is_direct_call', False) or body.get('direct_call', False)
            host = None
            if is_direct or not token:
                hosts = self.db.get_hosts()
                contact_uuid = body.get('contact') or body.get('host_uuid')
                if contact_uuid:
                    matched = [h for h in hosts if h['uuid'] == contact_uuid]
                    if matched: host = matched[0]
                if not host:
                    matched = [h for h in hosts if h.get('status') == 'APPROVED']
                    if matched: host = matched[0]
            else:
                if not self._authorize_guest(uuid, token):
                    return
                contact_uuid = body.get('contact') or body.get('host_uuid')
                host = self._resolve_target_host(uuid, contact_uuid)

            if not host:
                return self._send_json({"error": "No reachable host found for this client"}, 403)
            
            host_uuid = host['uuid']

            sig_type = body.get('type', 'webrtc_ice_candidate')
            has_cand = bool(body.get('candidate'))
            has_sdp = bool(body.get('sdp'))
            print(f"📡 [WebRTC Signal] Client '{uuid}' sent signal '{sig_type}' to host '{host.get('username') or host_uuid}' | Candidate: {has_cand} | SDP: {has_sdp}")

            if sig_type in ['webrtc_call_answer', 'call_answered']:
                self._update_call_status(call_id, "ACTIVE", client_uuid=uuid, host_uuid=host_uuid)
            elif sig_type in ['webrtc_call_hangup', 'call_ended', 'call_declined', 'call_rejected']:
                self._update_call_status(call_id, "ENDED", client_uuid=uuid, host_uuid=host_uuid)

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "signal",
                "eventId": event_id,
                "call_id": call_id,
                "uuid": uuid,
                "token": token or "",
                "type": sig_type,
                "candidate": body.get('candidate'),
                "sdp": body.get('sdp'),
                "status": body.get("status", ""),
                "reason": body.get("reason", "")
            }
            self.db.add_host_event(host_uuid, event_id, "signal", payload)
            self._notify_host_streams(host_uuid, "signal", payload)
            return self._send_json({"status": "signaled", "eventId": event_id, "call_id": call_id})

        return self._send_json({"error": "Endpoint not found"}, 404)

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
