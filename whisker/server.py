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
    host_streams = set()
    guest_streams = {}  # uuid -> set of wfile objects
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
        token = self.db.get_setting('host_pin_token')
        return bool(token)

    def _authorize_host(self):
        token = self._get_auth_token()
        saved_pin = self.db.get_setting('host_pin_token')

        if not saved_pin:
            if token:
                self.db.set_setting('host_pin_token', token)
                return True
            else:
                self._send_json({"error": "Access Key required in Authorization header or query token"}, 401)
                return False

        if token == saved_pin:
            return True

        self._send_json({"error": "Unauthorized access key"}, 401)
        return False

    def _authorize_guest(self, uuid, token):
        if not uuid:
            self._send_json({"error": "Missing uuid"}, 403)
            return None

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
            if not self._authorize_host():
                return
            return self._handle_host_sse()

        if path == '/api/host/events':
            if not self._authorize_host():
                return
            events = self.db.get_host_events()
            return self._send_json(events)

        if path == '/api/host/fcm_config':
            if not self._authorize_host():
                return
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
            if not self._authorize_host():
                return
            invites = self.db.get_invites()
            return self._send_json(invites)

        if path == '/api/host/clients':
            if not self._authorize_host():
                return
            clients = self.db.get_clients()
            with WhiskerServerHandler.streams_lock:
                active_uuids = {u for u, stms in WhiskerServerHandler.guest_streams.items() if stms}
            for c in clients:
                u = c.get('uuid')
                c['is_online'] = 1 if u in active_uuids else 0
            return self._send_json(clients)

        if path == '/api/host/message':
            if not self._authorize_host():
                return
            msg_id_str = qs.get('id', [''])[0]
            if not msg_id_str or not msg_id_str.isdigit():
                return self._send_json({"error": "Invalid or missing message id"}, 400)
            msg = self.db.get_message_by_id(int(msg_id_str))
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
            if not msg_id_str or not msg_id_str.isdigit():
                return self._send_json({"error": "Invalid or missing message id"}, 400)
            msg = self.db.get_message_by_id(int(msg_id_str))
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

        if path == '/api/events':
            uuid = qs.get('uuid', [''])[0]
            token = qs.get('token', [''])[0]

            req = self.db.get_request(uuid)
            if not req:
                return self._send_json({"error": "No registration found"}, 403)

            return self._handle_guest_sse(uuid)

        if path == '/api/info':
            push_token = self.db.get_setting('push_token')
            push_configured = bool(push_token and not push_token.startswith('device-push-token-') and not push_token.startswith('mock-'))
            with WhiskerServerHandler.streams_lock:
                host_conn = len(WhiskerServerHandler.host_streams) > 0

            return self._send_json({
                "host_connected": host_conn,
                "push_configured": push_configured,
                "host_reachable": host_conn or push_configured,
                "server_time": int(time.time() * 1000)
            })

        return self._send_json({"error": "Endpoint not found"}, 404)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        clean_parts = [p for p in parsed.path.split('/') if p]
        path = '/' + '/'.join(clean_parts)
        body = self._read_json_body()

        # Host endpoints
        if path == '/api/host/register_push':
            if not self._authorize_host():
                return
            push_token = body.get('pushToken') or body.get('token') or body.get('fcmToken') or self.headers.get('X-Push-Token', '')
            if push_token:
                self.db.set_setting('push_token', push_token)
                return self._send_json({"message": "Push token registered successfully"})
            return self._send_json({"error": "No push token provided"}, 400)

        if path == '/api/host/fcm_config':
            if not self._authorize_host():
                return
            api_key = body.get('api_key')
            if api_key:
                self.db.set_setting('fcm_api_key', api_key)
                return self._send_json({"message": "FCM API Key updated successfully"})
            return self._send_json({"error": "Missing api_key"}, 400)

        if path == '/api/host/ack':
            if not self._authorize_host():
                return
            event_id = body.get('eventId')
            if event_id:
                self.db.delete_host_event(event_id)
            return self._send_json({"message": "Event cleared"})

        if path == '/api/host/create_invite':
            if not self._authorize_host():
                return
            name = body.get('name', 'Invited Guest')
            code = body.get('code')
            if not code:
                code = ''.join(random.choices(string.ascii_letters + string.digits, k=5))

            self.db.add_invite(code, name)
            return self._send_json({
                "success": True,
                "code": code,
                "name": name,
                "created_at": int(time.time() * 1000)
            })

        if path == '/api/host/delete_invite':
            if not self._authorize_host():
                return
            code = body.get('code')
            if code:
                self.db.delete_invite(code)
            return self._send_json({"success": True})

        if path == '/api/host/response':
            if not self._authorize_host():
                return
            client_uuid = body.get('clientUuid') or body.get('uuid')
            resp_type = body.get('type')
            resp_event = body.get('event')

            if resp_type == 'registration_response' and resp_event == 'approved':
                token = body.get('token')
                if client_uuid and token:
                    self.db.approve_request(client_uuid, token)

            elif resp_type == 'chat_message' and body.get('sender') == 'phone':
                text = body.get('text', '')
                file_path = body.get('filePath')
                if client_uuid and text:
                    msg_id, prev_id = self.db.add_message(client_uuid, 'phone', text, file_path)
                    body['messageId'] = msg_id
                    body['id'] = msg_id
                    body['previousMessageId'] = prev_id

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
            self.db.add_guest_event(event_id, client_uuid, resp_type or resp_event or 'response', body)

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
            self.db.add_host_event(event_id, "visitor", payload)

            self._notify_host_streams("visitor", payload)
            push_token = self.db.get_setting('push_token')
            self.fcm.send_tickle_notification(push_token, event_id, "visitor")

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

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {"event": "invite_claimed", "eventId": event_id, "uuid": client_uuid, "name": client_name, "code": code, "token": client_token}
            self.db.add_host_event(event_id, "invite_claimed", payload)

            self._notify_host_streams("invite_claimed", payload)
            push_token = self.db.get_setting('push_token')
            self.fcm.send_tickle_notification(push_token, event_id, "invite_claimed")

            return self._send_json({
                "success": True,
                "uuid": client_uuid,
                "token": client_token,
                "name": client_name
            })

        if path == '/api/message':
            uuid = body.get('uuid')
            token = body.get('token')
            text = body.get('text')
            file_data = body.get('file')

            if not self._authorize_guest(uuid, token):
                return

            if not text:
                return self._send_json({"error": "Missing message text"}, 400)

            msg_id, prev_id = self.db.add_message(uuid, 'guest', text, file_data)
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "message",
                "eventId": event_id,
                "messageId": msg_id,
                "previousMessageId": prev_id,
                "uuid": uuid,
                "token": token,
                "text": text,
                "file": file_data,
                "filePath": file_data,
                "name": body.get('name', 'Guest'),
                "timestamp": int(time.time() * 1000)
            }
            self.db.add_host_event(event_id, "message", payload)

            self._notify_host_streams("message", payload)
            push_token = self.db.get_setting('push_token')
            self.fcm.send_tickle_notification(push_token, event_id, "message")

            return self._send_json({"status": "delivered", "id": msg_id, "messageId": msg_id, "previousMessageId": prev_id, "eventId": event_id})

        if path == '/api/call':
            uuid = body.get('uuid')
            token = body.get('token')
            if not self._authorize_guest(uuid, token):
                return

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            sdp_str = body.get('sdp', '')
            is_vid = body.get('video', False)
            sdp_len = len(sdp_str)
            has_audio = "m=audio" in sdp_str
            has_video = "m=video" in sdp_str
            print(f"📞 [WebRTC Call] Guest '{uuid}' ({body.get('name')}) initiated call | Video: {is_vid} | SDP Len: {sdp_len} (Audio: {has_audio}, Video: {has_video})")

            payload = {
                "event": "call",
                "eventId": event_id,
                "uuid": uuid,
                "token": token,
                "name": body.get('name', 'Guest'),
                "video": is_vid,
                "sdp": sdp_str
            }
            self.db.add_host_event(event_id, "call", payload)

            self._notify_host_streams("call", payload)
            push_token = self.db.get_setting('push_token')
            self.fcm.send_tickle_notification(push_token, event_id, "call")

            return self._send_json({"status": "dialing", "eventId": event_id})

        if path == '/api/hangup':
            uuid = body.get('uuid')
            token = body.get('token')
            if not self._authorize_guest(uuid, token):
                return

            print(f"🛑 [WebRTC Hangup] Guest '{uuid}' requested call hangup")
            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "hangup",
                "eventId": event_id,
                "uuid": uuid,
                "token": token
            }
            self.db.add_host_event(event_id, "hangup", payload)

            self._notify_host_streams("hangup", payload)
            push_token = self.db.get_setting('push_token')
            self.fcm.send_tickle_notification(push_token, event_id, "hangup")

            return self._send_json({"status": "hungup"})

        if path == '/api/signal':
            uuid = body.get('uuid')
            token = body.get('token')
            if not self._authorize_guest(uuid, token):
                return

            sig_type = body.get('type', 'webrtc_ice_candidate')
            has_cand = bool(body.get('candidate'))
            has_sdp = bool(body.get('sdp'))
            print(f"📡 [WebRTC Signal] Guest '{uuid}' sent signal '{sig_type}' | Candidate: {has_cand} | SDP: {has_sdp}")

            event_id = f"evt_{int(time.time()*1000)}_{random.randint(100,999)}"
            payload = {
                "event": "signal",
                "eventId": event_id,
                "uuid": uuid,
                "token": token,
                "type": sig_type,
                "candidate": body.get('candidate'),
                "sdp": body.get('sdp')
            }
            self.db.add_host_event(event_id, "signal", payload)
            self._notify_host_streams("signal", payload)
            return self._send_json({"status": "signaled", "eventId": event_id})

        return self._send_json({"error": "Endpoint not found"}, 404)

    def _handle_host_sse(self):
        self.send_response(200)
        self._send_cors_headers()
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.end_headers()

        wfile = self.wfile
        with WhiskerServerHandler.streams_lock:
            WhiskerServerHandler.host_streams.add(wfile)

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
                WhiskerServerHandler.host_streams.discard(wfile)

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

    def _notify_host_streams(self, event_type, payload):
        msg = f"data: {json.dumps(payload)}\n\n".encode('utf-8')
        with WhiskerServerHandler.streams_lock:
            dead_streams = set()
            for wfile in WhiskerServerHandler.host_streams:
                try:
                    wfile.write(msg)
                    wfile.flush()
                except Exception:
                    dead_streams.add(wfile)
            for d in dead_streams:
                WhiskerServerHandler.host_streams.discard(d)

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
