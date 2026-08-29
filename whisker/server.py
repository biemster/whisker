import os
import sys
import json
import time
import random
import string
import urllib.parse
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

from whisker.db import DatabaseManager
from whisker.fcm import FCMManager, get_service_account_file

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

        <div class="flex items-center justify-center space-x-2 text-xs text-gray-400 font-mono bg-gray-950/50 py-2 rounded-xl">
            <span class="w-2 h-2 rounded-full bg-yellow-500 animate-pulse"></span>
            <span>Waiting for first contact...</span>
        </div>
    </div>

    <script>
        document.getElementById('serverAddress').innerText = window.location.origin;
        setInterval(async () => {
            try {
                const res = await fetch('/api/init-status');
                const data = await res.json();
                if (data && data.initialized) {
                    window.location.reload();
                }
            } catch (e) {}
        }, 2000);
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
    streams_lock = threading.RLock()

    def _send_cors_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Push-Token')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')

    def do_OPTIONS(self):
        self.send_response(200)
        self._send_cors_headers()
        self.end_headers()

    def _read_json_body(self):
        content_len = int(self.headers.get('Content-Length', 0))
        if content_len > 0:
            body_bytes = self.rfile.read(content_len)
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

    def _send_fcm_tickle(self, push_token, event_id=None, event_type="event", extra_status=None):
        if push_token and self.fcm:
            try:
                self.fcm.send_tickle_notification(push_token, event_id=event_id, event_type=event_type, extra_status=extra_status)
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
        all_hosts = self.db.get_hosts()
        approved_all_hosts = [h for h in all_hosts if h.get('status') == 'APPROVED']

        if contact_uuid:
            target = next((h for h in approved_hosts if h['uuid'] == contact_uuid), None)
            if not target:
                target = next((h for h in approved_all_hosts if h['uuid'] == contact_uuid or h.get('username') == contact_uuid), None)
                if target:
                    self.db.add_client_host_approval(client_uuid, target['uuid'])
            return target
        
        if approved_hosts:
            return approved_hosts[0]
        
        if approved_all_hosts:
            admin_host = self.db.get_admin_host() or approved_all_hosts[0]
            self.db.add_client_host_approval(client_uuid, admin_host['uuid'])
            return admin_host
            
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

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        clean_parts = [p for p in parsed.path.split('/') if p]
        path = '/' + '/'.join(clean_parts)
        qs = urllib.parse.parse_qs(parsed.query)

        # GET /api/init-status
        if path == '/api/init-status':
            return self._send_json({"initialized": self._is_initialized()})

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
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            events = self.db.get_host_events(host_uuid)
            return self._send_json(events)

        if path in ['/api/host/fcm_config', '/api/fcm_config']:
            sa = get_service_account_file()
            fcm_key = self.db.get_setting('fcm_api_key') or ""
            if sa:
                return self._send_json({
                    "configured": True,
                    "project_id": sa.get("project_id", ""),
                    "sender_id": sa.get("project_number") or sa.get("project_id", ""),
                    "app_id": sa.get("app_id", ""),
                    "api_key": sa.get("api_key") or fcm_key
                })
            else:
                return self._send_json({
                    "configured": False,
                    "error": "whisker-push-sa.json not found on Home Server"
                })

        if path == '/api/host/invites':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            invites = self.db.get_invites(host_uuid)
            return self._send_json(invites)

        if path == '/api/host/clients':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            clients = self.db.get_approved_clients_for_host(host_uuid)
            
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

            for c in clients:
                u = c.get('uuid')
                c['is_online'] = 1 if u in active_uuids else 0

            # If the requesting host is an admin or multiple hosts exist, include companion mobile apps
            all_hosts = self.db.get_hosts()
            for h in all_hosts:
                if h['uuid'] != host_uuid:
                    h_online = 1 if (h['uuid'] in active_hosts or (now - WhiskerServerHandler.host_last_seen.get(h['uuid'], 0) < 35)) else 0
                    h_status = h.get('status', 'PENDING')
                    clients.append({
                        "uuid": h['uuid'],
                        "name": h.get('username') or f"Mobile Device ({h['uuid'][:6]})",
                        "reason": f"Companion Mobile App ({h.get('username', 'Mobile')})",
                        "req_status": f"MOBILE_{h_status}",
                        "token": h.get('pin_token', ''),
                        "created_at": h.get('created_at', int(time.time() * 1000)),
                        "is_online": h_online,
                        "is_mobile_host": True
                    })

            return self._send_json(clients)

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

        if path == '/api/info':
            hosts = self.db.get_hosts()
            push_configured = any(
                h.get('push_token') and not h['push_token'].startswith('device-push-token-') and not h['push_token'].startswith('mock-')
                for h in hosts
            ) or bool(get_service_account_file())
            with WhiskerServerHandler.streams_lock:
                host_conn = any(len(stms) > 0 for stms in WhiskerServerHandler.host_streams.values())

            direct_hosts = [
                {
                    "uuid": h['uuid'],
                    "username": h.get('username') or 'Mobile App',
                    "is_admin": h.get('is_admin', 0)
                }
                for h in hosts
                if h.get('allow_direct_calls', 0) == 1 and h.get('status') == 'APPROVED'
            ]
            direct_calls_enabled = len(direct_hosts) > 0

            return self._send_json({
                "host_connected": host_conn,
                "push_configured": push_configured,
                "host_reachable": host_conn or push_configured,
                "allow_direct_calls": direct_calls_enabled,
                "direct_call_hosts": direct_hosts,
                "server_time": int(time.time() * 1000)
            })

        return self._send_json({"error": "Endpoint not found"}, 404)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        clean_parts = [p for p in parsed.path.split('/') if p]
        path = '/' + '/'.join(clean_parts)
        body = self._read_json_body()
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
                return self._send_json({
                    "status": existing['status'],
                    "host_uuid": existing['uuid'],
                    "username": username,
                    "is_admin": existing.get('is_admin', 0)
                })

            host_uuid = str(uuid_lib.uuid4())
            is_admin = 1 if len(hosts) == 0 else 0
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
                        self._send_fcm_tickle(admin_push, event_id=event_id, event_type="mobile_pairing")

            return self._send_json({
                "status": status,
                "host_uuid": host_uuid,
                "username": username,
                "is_admin": is_admin
            })


        # Host endpoints
        if path == '/api/host/register_push':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            push_token = body.get('pushToken') or body.get('token') or body.get('fcmToken') or self.headers.get('X-Push-Token', '')
            if push_token and str(push_token).strip() and not str(push_token).startswith('Fetching') and not str(push_token).startswith('device-push-token-') and not str(push_token).startswith('mock-'):
                clean_token = str(push_token).strip()
                self.db.update_host(host_uuid, push_token=clean_token)
                print(f"[Whisker Server] 📲 Host FCM Push Token registered: {clean_token[:16]}... (Length: {len(clean_token)}) for host '{host.get('username') or host_uuid}'")
                return self._send_json({"message": "Push token registered successfully"})
            return self._send_json({"error": "No valid push token provided"}, 400)

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

        if path == '/api/host/ack':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            event_id = body.get('eventId')
            if event_id:
                self.db.delete_host_event(event_id)
            return self._send_json({"message": "Event cleared"})

        if path == '/api/host/create_invite':
            host = self._authorize_host()
            if not host: return
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
                self.db.delete_invite(code)
            return self._send_json({"success": True})

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
                        "status": "APPROVED"
                    })
            except Exception as e:
                return self._send_json({"error": f"Failed to claim remote invite: {str(e)}"}, 500)

        if path == '/api/host/response':
            host = self._authorize_host()
            if not host: return
            host_uuid = host["uuid"]
            client_uuid = body.get('clientUuid') or body.get('uuid')
            resp_type = body.get('type')
            resp_event = body.get('event')

            target_host = self.db.get_host(client_uuid)
            approved_clients = self.db.get_approved_clients_for_host(host_uuid)
            client_req = self.db.get_request(client_uuid)
            is_req_approved = (client_req is not None and client_req.get('status') == 'APPROVED')
            if is_req_approved:
                self.db.add_client_host_approval(client_uuid, host_uuid)
            is_approved = (target_host is not None) or is_req_approved or any(c['uuid'] == client_uuid for c in approved_clients)

            if not is_approved and resp_type not in ['registration_response', 'mobile_pairing_response']:
                return self._send_json({"error": "Host not paired with this client"}, 403)

            if resp_type == 'registration_response' and resp_event == 'approved':
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
                        self._send_fcm_tickle(target_h['push_token'], event_id=target_host_uuid, event_type="mobile_pairing", extra_status="approved")
                        
            elif resp_type == 'mobile_pairing_response' and resp_event == 'rejected':
                target_host_uuid = body.get('uuid') or body.get('host_uuid')
                if target_host_uuid:
                    self.db.update_host(target_host_uuid, status='REJECTED')
                    target_h = self.db.get_host(target_host_uuid)
                    if target_h and target_h.get('push_token'):
                        self._send_fcm_tickle(target_h['push_token'], event_id=target_host_uuid, event_type="mobile_pairing", extra_status="rejected")
                        
            elif resp_type in ['chat_message', 'message'] and (body.get('sender') == 'phone' or 'text' in body):
                text = body.get('text', '')
                file_path = body.get('filePath') or body.get('file')
                if client_uuid and (text or file_path):
                    msg_id, prev_id = self.db.add_message(client_uuid, host_uuid, 'phone', text, file_path)
                    body['messageId'] = msg_id
                    body['id'] = msg_id
                    body['previousMessageId'] = prev_id

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
                            "server_address": "local"
                        }
                        self.db.add_host_event(target_host['uuid'], event_id, "message", payload)
                        self._notify_host_streams(target_host['uuid'], "message", payload)
                        if target_host.get('push_token'):
                            self._send_fcm_tickle(target_host['push_token'], event_id=msg_id, event_type="message")
                        return self._send_json({"message": "Message routed to companion mobile app", "messageId": msg_id})

            if target_host and (resp_type in ['webrtc_call_initiate', 'call'] or resp_event in ['call_initiate', 'call']):
                event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                payload = {
                    "event": "call",
                    "eventId": event_id,
                    "uuid": host_uuid,
                    "name": host.get('username') or 'Mobile App',
                    "sdp": body.get('sdp', ''),
                    "video": body.get('video', True),
                    "client_type": "MOBILE",
                    "server_address": "local"
                }
                self.db.add_host_event(target_host['uuid'], event_id, "call", payload)
                self._notify_host_streams(target_host['uuid'], "call", payload)
                if target_host.get('push_token'):
                    self._send_fcm_tickle(target_host['push_token'], event_id=event_id, event_type="call")
                return self._send_json({"message": "Call routed to companion mobile app"})

            if target_host and (resp_type in ['webrtc_call_answer', 'webrtc_ice_candidate'] or resp_event in ['call_answered', 'ice_candidate']):
                event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                payload = {
                    "event": "signal",
                    "eventId": event_id,
                    "uuid": host_uuid,
                    "type": resp_type or resp_event,
                    "sdp": body.get('sdp', ''),
                    "candidate": body.get('candidate'),
                    "client_type": "MOBILE",
                    "server_address": "local"
                }
                self.db.add_host_event(target_host['uuid'], event_id, "signal", payload)
                self._notify_host_streams(target_host['uuid'], "signal", payload)
                return self._send_json({"message": "Signal routed to companion mobile app"})

            if target_host and (resp_type in ['webrtc_call_hangup', 'call_declined', 'call_ended'] or resp_event in ['call_ended', 'hangup']):
                event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                payload = {
                    "event": "hangup",
                    "eventId": event_id,
                    "uuid": host_uuid
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

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            self.db.add_guest_event(host_uuid, event_id, client_uuid, resp_type or resp_event or 'response', body)

            # Broadcast to active guest stream if connected
            self._notify_guest_streams(client_uuid, body)
            return self._send_json({"message": "Response routed"})

        # Guest endpoints
        if path == '/api/register':
            uuid = body.get('uuid')
            name = body.get('name')
            reason = body.get('reason')
            if not uuid or not name or not reason:
                return self._send_json({"error": "Missing required fields"}, 400)

            self.db.add_request(uuid, name, reason)
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {"event": "visitor", "eventId": event_id, "uuid": uuid, "name": name, "reason": reason}
            
            hosts = self.db.get_hosts()
            for host in hosts:
                self.db.add_host_event(host['uuid'], event_id, "visitor", payload)
                self._notify_host_streams(host['uuid'], "visitor", payload)
                if host.get('push_token'):
                    self._send_fcm_tickle(host['push_token'], event_id=event_id, event_type="visitor")

            return self._send_json({"status": "queued", "eventId": event_id})

        if path == '/api/claim_invite':
            code = body.get('code')
            if not code:
                return self._send_json({"error": "Missing invite code"}, 400)

            invite, err = self.db.claim_invite(code)
            if err:
                return self._send_json({"error": err}, 400 if "already" in err else 404)

            client_uuid = f"cli_{''.join(random.choices(string.ascii_lowercase + string.digits, k=9))}"
            client_token = f"tok_{''.join(random.choices(string.ascii_lowercase + string.digits, k=16))}"
            client_name = invite.get('name', 'Invited Guest')

            self.db.add_request(client_uuid, client_name, f"Claimed invite code '{code}'")
            self.db.approve_request(client_uuid, client_token)
            
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
            if invite_host_uuid:
                self.db.add_client_host_approval(client_uuid, invite_host_uuid)

                event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
                payload = {"event": "invite_claimed", "eventId": event_id, "uuid": client_uuid, "name": client_name, "code": code, "token": client_token, "host_name": host_name, "host_uuid": invite_host_uuid}
                self.db.add_host_event(invite_host_uuid, event_id, "invite_claimed", payload)
                self._notify_host_streams(invite_host_uuid, "invite_claimed", payload)
                if host and host.get('push_token'):
                    self._send_fcm_tickle(host['push_token'], event_type="invite_claimed")

            print(f"🎟️ [Whisker Invite Claimed] Code: '{code}' | Guest: '{client_name}' ({client_uuid}) | Host: '{host_name}' ({invite_host_uuid})")

            return self._send_json({
                "success": True,
                "uuid": client_uuid,
                "token": client_token,
                "name": client_name,
                "host_uuid": invite_host_uuid,
                "host_name": host_name,
                "status": "APPROVED"
            })

        if path == '/api/message':
            uuid = body.get('uuid')
            token = body.get('token')
            text = body.get('text')
            file_data = body.get('file')

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

            msg_text = text if text is not None else (file_data if file_data else "")
            msg_id, prev_id = self.db.add_message(uuid, host_uuid, 'guest', msg_text, file_data)
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
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
                "name": body.get('name', 'Guest'),
                "timestamp": int(time.time() * 1000)
            }
            self.db.add_host_event(host_uuid, event_id, "message", payload)
            self._notify_host_streams(host_uuid, "message", payload)
            
            if push_token:
                self._send_fcm_tickle(push_token, event_id=msg_id, event_type="message")

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

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            sdp_str = body.get('sdp', '')
            is_vid = body.get('video', False)
            sdp_len = len(sdp_str)
            has_audio = "m=audio" in sdp_str
            has_video = "m=video" in sdp_str
            reason_str = body.get('reason', '')
            print(f"📞 [WebRTC Call] {'⚡ DIRECT CALL' if is_direct else 'Guest'} '{uuid}' ({body.get('name')}) initiated call | Host: '{host.get('username') or host_uuid}' | Video: {is_vid} | Reason: '{reason_str}' | SDP Len: {sdp_len}")

            payload = {
                "event": "call",
                "eventId": event_id,
                "uuid": uuid,
                "token": token or "",
                "name": body.get('name', 'Guest'),
                "video": is_vid,
                "sdp": sdp_str,
                "is_direct_call": is_direct,
                "reason": reason_str
            }
            self.db.add_host_event(host_uuid, event_id, "call", payload)
            self._notify_host_streams(host_uuid, "call", payload)
            
            if push_token:
                self._send_fcm_tickle(push_token, event_id=event_id, event_type="call")

            return self._send_json({"status": "dialing", "eventId": event_id, "host_uuid": host_uuid})

        if path == '/api/hangup':
            uuid = body.get('uuid')
            token = body.get('token')
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

            print(f"🛑 [WebRTC Hangup] Client '{uuid}' requested call hangup to host '{host.get('username') or host_uuid}'")
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "hangup",
                "eventId": event_id,
                "uuid": uuid,
                "token": token or ""
            }
            self.db.add_host_event(host_uuid, event_id, "hangup", payload)
            self._notify_host_streams(host_uuid, "hangup", payload)
            
            if push_token:
                self._send_fcm_tickle(push_token, event_id=event_id, event_type="hangup")

            return self._send_json({"status": "hungup"})

        if path == '/api/signal':
            uuid = body.get('uuid')
            token = body.get('token')
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

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "signal",
                "eventId": event_id,
                "uuid": uuid,
                "token": token or "",
                "type": sig_type,
                "candidate": body.get('candidate'),
                "sdp": body.get('sdp')
            }
            self.db.add_host_event(host_uuid, event_id, "signal", payload)
            self._notify_host_streams(host_uuid, "signal", payload)
            return self._send_json({"status": "signaled", "eventId": event_id})

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
            for ev in pending_events:
                payload = ev.get('payload', '')
                if isinstance(payload, dict):
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

    def _notify_guest_streams(self, uuid, payload):
        msg = f"data: {json.dumps(payload)}\n\n".encode('utf-8')
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

def run_server(port=3000, db_path="whisker_server.db"):
    db = DatabaseManager(db_path)
    fcm = FCMManager()

    WhiskerServerHandler.db = db
    WhiskerServerHandler.fcm = fcm

    server_address = ('', port)
    httpd = ThreadedHTTPServer(server_address, WhiskerServerHandler)

    sa_status = "FOUND (FCM HTTP v1 Active)" if get_service_account_file() else "NOT FOUND (Run 'whisker setup-fcm' to create)"

    print("==================================================================")
    print(" 🐱 WHISKER HOME SERVER ACTIVE (Python Edition)")
    print("==================================================================")
    print(f"   - Listening Port:       {port}")
    print(f"   - Local SQLite DB:      {db_path}")
    print(f"   - FCM Service Account:  {sa_status}")
    print("------------------------------------------------------------------")
    print(f"   - Landing Web Portal:   http://localhost:{port}")
    print(f"   - Mobile App URL:       http://localhost:{port}")
    print("==================================================================")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Whisker Home Server...")
        httpd.server_close()
