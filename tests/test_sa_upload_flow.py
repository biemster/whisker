import os
import sys
import json
import time
import shutil
import socket
import tempfile
import urllib.request
import urllib.error
import subprocess


def get_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start_server(db_path, port, name="Test SA Server"):
    cmd = [
        sys.executable, "-m", "whisker", "server",
        "--name", name,
        "--port", str(port),
        "--db", db_path,
        "--admin-password", "testpassword123"
    ]
    env = os.environ.copy()
    env["PYTHONPATH"] = "."
    proc = subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    base_url = f"http://127.0.0.1:{port}"
    deadline = time.time() + 10.0
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"{base_url}/api/public", timeout=1) as resp:
                if resp.status == 200:
                    return proc, base_url
        except Exception:
            time.sleep(0.2)
    proc.terminate()
    raise RuntimeError("Server failed to start within timeout")


def stop_server(proc):
    proc.terminate()
    try:
        proc.wait(timeout=3)
    except Exception:
        proc.kill()


def http_get_text(url):
    with urllib.request.urlopen(url, timeout=5) as resp:
        return resp.status, resp.read().decode("utf-8")


def http_get_json(url):
    status, body = http_get_text(url)
    return status, json.loads(body)


def http_post_json(url, payload, headers=None):
    hdrs = {"Content-Type": "application/json"}
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def make_dummy_sa(project_id):
    return {
        "type": "service_account",
        "project_id": project_id,
        "private_key_id": "dummy-key-id",
        "private_key": "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCdummy\n-----END PRIVATE KEY-----\n",
        "client_email": f"firebase-adminsdk@{project_id}.iam.gserviceaccount.com",
        "client_id": "1234567890",
        "project_number": "987654321098"
    }


def test_order_a_upload_sa_before_pairing():
    print("\n--- Testing Order A: Upload SA before mobile pairing ---")
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "order_a.db")
    port = get_free_port()
    proc, base_url = start_server(db_path, port)
    try:
        # 1. Fresh server state
        status, pub = http_get_json(f"{base_url}/api/public")
        assert status == 200
        assert pub["initialized"] is False
        assert pub["sa_configured"] is False

        status, html = http_get_text(f"{base_url}/")
        assert status == 200
        assert 'id="pairingCard"' in html
        assert 'id="pairingCard" class="p-4 rounded-2xl bg-gray-950 border border-gray-850 text-left space-y-3"' in html
        assert 'id="saUploadSection" class="space-y-3"' in html

        # 2. Reject invalid SA missing required fields
        status, err = http_post_json(f"{base_url}/api/host", {
            "action": "upload_sa",
            "service_account": {"project_id": "only-project"}
        })
        assert status == 400, f"Expected 400 for incomplete SA, got {status}: {err}"

        # 3. Upload valid SA before pairing
        sa_a = make_dummy_sa("test-project-a")
        status, res = http_post_json(f"{base_url}/api/host", {
            "action": "upload_sa",
            "service_account": sa_a
        })
        assert status == 200, f"Expected 200 on pre-pairing SA upload, got {status}: {res}"
        assert res["success"] is True
        assert res["project_id"] == "test-project-a"

        # 4. Verify SA stored in db_dir & public payload updated
        assert os.path.isfile(os.path.join(temp_dir, "whisker-push-sa.json"))
        status, pub = http_get_json(f"{base_url}/api/public")
        assert pub["initialized"] is False
        assert pub["sa_configured"] is True
        assert pub["project_id"] == "test-project-a"

        # 5. Landing page still shows pairing card, hides SA upload prompt
        status, html = http_get_text(f"{base_url}/")
        assert 'id="pairingCard" class="p-4 rounded-2xl bg-gray-950 border border-gray-850 text-left space-y-3"' in html
        assert 'id="saUploadSection" class="space-y-3 hidden"' in html
        assert "test-project-a" in html

        # 6. Unauthenticated overwrite should be rejected (403)
        status, err = http_post_json(f"{base_url}/api/host", {
            "action": "upload_sa",
            "service_account": make_dummy_sa("hijack-proj")
        })
        assert status == 403, f"Expected 403 on unauthenticated overwrite, got {status}: {err}"

        # 7. Now pair mobile app
        status, reg = http_post_json(f"{base_url}/api/host", {
            "action": "register_mobile",
            "username": "Pixel9",
            "pin_token": "tok_order_a"
        })
        assert status == 200
        assert reg["status"] == "APPROVED"

        # 8. Sync includes active FCM config
        status, sync_res = http_post_json(
            f"{base_url}/api/host",
            {"action": "sync"},
            headers={"Authorization": "Bearer tok_order_a"}
        )
        assert status == 200
        assert sync_res["fcm"]["configured"] is True
        assert sync_res["fcm"]["project_id"] == "test-project-a"

        # 9. Landing page now serves visitor portal (index.html)
        status, html = http_get_text(f"{base_url}/")
        assert status == 200
        assert 'id="pairingCard"' not in html
        assert 'id="saUploadSection"' not in html

        # 10. Restart server on same DB and confirm SA & pairing persist cleanly
        stop_server(proc)
        port2 = get_free_port()
        proc, base_url = start_server(db_path, port2)
        status, pub = http_get_json(f"{base_url}/api/public")
        assert pub["initialized"] is True
        assert pub["sa_configured"] is True
        assert pub["project_id"] == "test-project-a"
        status, html = http_get_text(f"{base_url}/")
        assert 'id="pairingCard"' not in html

        print("Order A passed!")
    finally:
        stop_server(proc)
        shutil.rmtree(temp_dir, ignore_errors=True)


def test_order_b_pair_before_uploading_sa():
    print("\n--- Testing Order B: Pair mobile before uploading SA ---")
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "order_b.db")
    port = get_free_port()
    proc, base_url = start_server(db_path, port)
    try:
        # 1. Pair mobile app first (no SA yet)
        status, reg = http_post_json(f"{base_url}/api/host", {
            "action": "register_mobile",
            "username": "Pixel9",
            "pin_token": "tok_order_b"
        })
        assert status == 200
        assert reg["status"] == "APPROVED"

        # 2. Verify /api/public shows initialized=True, sa_configured=False
        status, pub = http_get_json(f"{base_url}/api/public")
        assert pub["initialized"] is True
        assert pub["sa_configured"] is False

        # 3. Sync initially returns fcm.configured=False with a hash
        status, sync1 = http_post_json(
            f"{base_url}/api/host",
            {"action": "sync"},
            headers={"Authorization": "Bearer tok_order_b"}
        )
        assert status == 200
        assert sync1["fcm"]["configured"] is False
        hash_before = sync1["fcm"]["hash"]
        assert hash_before

        # 4. Landing page (/, /index.html, /invite/abcde) must NOT go to visitor page;
        #    it must hide pairing instructions and ONLY ask for the SA JSON
        for route in ["/", "/index.html", "/invite/abcde"]:
            status, html = http_get_text(f"{base_url}{route}")
            assert status == 200
            assert 'id="pairingCard" class="p-4 rounded-2xl bg-gray-950 border border-gray-850 text-left space-y-3 hidden"' in html, f"Expected pairingCard hidden on {route}"
            assert 'id="saUploadSection" class="space-y-3"' in html, f"Expected saUploadSection visible on {route}"
            assert "Push Service Account Required" in html

        # 5. Upload SA JSON from the landing page (flat spread format also supported)
        sa_b = make_dummy_sa("test-project-b")
        status, res = http_post_json(f"{base_url}/api/host", {
            "action": "upload_sa",
            **sa_b
        })
        assert status == 200, f"Expected 200 uploading SA after pairing, got {status}: {res}"
        assert res["success"] is True
        assert res["project_id"] == "test-project-b"

        # 6. /api/public now shows initialized=True and sa_configured=True
        status, pub = http_get_json(f"{base_url}/api/public")
        assert pub["initialized"] is True
        assert pub["sa_configured"] is True
        assert pub["project_id"] == "test-project-b"

        # 7. Next sync with old fcm_hash invalidates cache and delivers new FCM config to mobile app
        status, sync2 = http_post_json(
            f"{base_url}/api/host",
            {"action": "sync", "fcm_hash": hash_before},
            headers={"Authorization": "Bearer tok_order_b"}
        )
        assert status == 200
        assert sync2["fcm"].get("unchanged") is not True
        assert sync2["fcm"]["configured"] is True
        assert sync2["fcm"]["project_id"] == "test-project-b"
        assert sync2["fcm"]["hash"] != hash_before

        # 8. Landing page now serves visitor portal (index.html)
        status, html = http_get_text(f"{base_url}/")
        assert status == 200
        assert 'id="pairingCard"' not in html
        assert 'id="saUploadSection"' not in html

        print("Order B passed!")
    finally:
        stop_server(proc)
        shutil.rmtree(temp_dir, ignore_errors=True)


if __name__ == "__main__":
    test_order_a_upload_sa_before_pairing()
    test_order_b_pair_before_uploading_sa()
    print("\nALL SA UPLOAD FLOW TESTS PASSED!")
