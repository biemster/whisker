import os
import json
import time
import base64
import tempfile
import subprocess
import urllib.request
import urllib.parse

def sign_rs256(private_key_pem: str, data: bytes) -> bytes:
    """Signs bytes with RSA-SHA256 using OpenSSL binary or subprocess."""
    with tempfile.NamedTemporaryFile('w', delete=False) as f:
        f.write(private_key_pem)
        key_path = f.name
    try:
        proc = subprocess.Popen(
            ['openssl', 'dgst', '-sha256', '-sign', key_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        out, err = proc.communicate(input=data)
        if proc.returncode != 0:
            raise RuntimeError(f"OpenSSL signature failed: {err.decode('utf-8')}")
        return out
    finally:
        if os.path.exists(key_path):
            os.remove(key_path)

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode('utf-8').rstrip('=')

def get_service_account_file():
    env_json = os.environ.get('SERVICE_ACCOUNT_JSON')
    if env_json:
        try:
            return json.loads(env_json)
        except Exception:
            pass

    possible_files = [
        os.environ.get('SERVICE_ACCOUNT_PATH'),
        os.environ.get('GOOGLE_APPLICATION_CREDENTIALS'),
        'whisker-push-sa.json',
        'service-account.json',
        'firebase-service-account.json',
        'serviceAccountKey.json'
    ]
    for p in possible_files:
        if p and os.path.isfile(p):
            try:
                with open(p, 'r', encoding='utf-8') as f:
                    return json.load(f)
            except Exception:
                pass
    return None

class FCMManager:
    def __init__(self):
        self._cached_token = None
        self._token_expiry = 0

    def get_fcm_access_token(self, sa):
        now = int(time.time())
        if self._cached_token and now < self._token_expiry - 60:
            return self._cached_token

        client_email = sa.get('client_email')
        private_key = sa.get('private_key')
        token_uri = sa.get('token_uri', 'https://oauth2.googleapis.com/token')

        if not client_email or not private_key:
            raise ValueError("Service account JSON missing 'client_email' or 'private_key'")

        header = json.dumps({"alg": "RS256", "typ": "JWT"}).encode('utf-8')
        claimset = json.dumps({
            "iss": client_email,
            "scope": "https://www.googleapis.com/auth/firebase.messaging",
            "aud": token_uri,
            "exp": now + 3600,
            "iat": now
        }).encode('utf-8')

        to_sign = f"{b64url(header)}.{b64url(claimset)}".encode('utf-8')
        sig = sign_rs256(private_key, to_sign)
        assertion = f"{to_sign.decode('utf-8')}.{b64url(sig)}"

        req_body = urllib.parse.urlencode({
            'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion': assertion
        }).encode('utf-8')

        req = urllib.request.Request(token_uri, data=req_body, headers={'Content-Type': 'application/x-www-form-urlencoded'})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            self._cached_token = data.get('access_token')
            self._token_expiry = now + data.get('expires_in', 3600)
            return self._cached_token

    def send_tickle_notification(self, push_token, event_id, event_type):
        if not push_token or push_token.startswith('device-push-token-') or push_token.startswith('mock-'):
            print(f"[Push Notification] Skipping FCM tickle for event '{event_type}' (id: {event_id}): No valid pushToken registered.")
            return False

        sa = get_service_account_file()
        if not sa:
            print(f"[Push Notification] Skipping FCM tickle for event '{event_type}' (id: {event_id}): Service account file not found.")
            return False

        project_id = sa.get('project_id')
        if not project_id:
            print(f"[Push Notification] Error: Service account JSON missing 'project_id'.")
            return False

        try:
            access_token = self.get_fcm_access_token(sa)
        except Exception as e:
            print(f"[Push Notification] Error generating OAuth access token: {e}")
            return False

        fcm_url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
        payload = {
            "message": {
                "token": push_token,
                "data": {
                    "event": "tickle",
                    "eventId": str(event_id),
                    "type": str(event_type),
                    "timestamp": str(int(time.time() * 1000))
                },
                "android": {
                    "priority": "HIGH"
                }
            }
        }

        try:
            req_data = json.dumps(payload).encode('utf-8')
            req = urllib.request.Request(
                fcm_url,
                data=req_data,
                headers={
                    'Content-Type': 'application/json',
                    'Authorization': f'Bearer {access_token}'
                }
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                print(f"[Push Notification] FCM tickle delivered for event '{event_type}' (id: {event_id}) -> HTTP {resp.status}")
                return True
        except Exception as e:
            print(f"[Push Notification] FCM send failed for event '{event_type}': {e}")
            return False
