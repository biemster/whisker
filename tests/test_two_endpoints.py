import os
import sys
import json
import time
import tempfile
import urllib.request
import urllib.error
import subprocess

def run_tests():
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "test_server.db")
    port = 39281

    cmd = [
        sys.executable, "-m", "whisker", "server",
        "--name", "Test Whisker Server",
        "--port", str(port),
        "--db", db_path,
        "--admin-password", "testpassword123"
    ]
    env = os.environ.copy()
    env["PYTHONPATH"] = "."

    proc = subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(2)

    try:
        base_url = f"http://127.0.0.1:{port}"
        print(f"Testing against {base_url}...")

        # 1. Test GET /api/public
        req = urllib.request.Request(f"{base_url}/api/public")
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            data = json.loads(resp.read().decode())
            print("GET /api/public response:", data)
            assert data["status"] == "ok"
            assert data["server_name"] == "Test Whisker Server"
            assert "initialized" in data
            assert data["initialized"] is False
            assert "available_hosts" in data

        # 2. Test GET /api/host -> 405 Method Not Allowed (all host interactions must be POST)
        try:
            urllib.request.urlopen(f"{base_url}/api/host", timeout=5)
            assert False, "Should have returned 405 Method Not Allowed"
        except urllib.error.HTTPError as e:
            assert e.code == 405
            print("GET /api/host correctly returned 405 Method Not Allowed")

        # 2b. Test URL query parameters on /api/public -> 400 Bad Request
        try:
            urllib.request.urlopen(f"{base_url}/api/public?action=test", timeout=5)
            assert False, "Should have returned 400 Bad Request"
        except urllib.error.HTTPError as e:
            assert e.code == 400
            print("GET /api/public?action=test correctly returned 400 Bad Request")

        # 2c. Test URL query parameters on /api/host -> 400 Bad Request
        try:
            urllib.request.urlopen(f"{base_url}/api/host?action=events&uuid=test&token=test", timeout=5)
            assert False, "Should have returned 400 Bad Request"
        except urllib.error.HTTPError as e:
            assert e.code == 400
            print("GET /api/host with query parameters correctly returned 400 Bad Request")

        # 3. Register mobile host via POST /api/host with action: register_mobile
        reg_payload = json.dumps({
            "action": "register_mobile",
            "username": "TestHost",
            "pin_token": "pin_123456",
            "push_token": "fcm_mock_token_123"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=reg_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            reg_data = json.loads(resp.read().decode())
            print("POST /api/host (register_mobile) response:", reg_data)
            access_token = reg_data.get("token") or "pin_123456"

        # 4. Test POST /api/host with query parameters -> 400 Bad Request
        try:
            bad_req = urllib.request.Request(
                f"{base_url}/api/host?fcm_hash=123",
                data=json.dumps({"action": "sync"}).encode(),
                headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"}
            )
            urllib.request.urlopen(bad_req, timeout=5)
            assert False, "Should have returned 400 Bad Request"
        except urllib.error.HTTPError as e:
            assert e.code == 400
            print("POST /api/host with query parameters correctly returned 400 Bad Request")

        # 5. Test JSON-body Host Unified Sync: POST /api/host with { "action": "sync" }
        sync_payload = json.dumps({
            "action": "sync"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=sync_payload,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            host_sync = json.loads(resp.read().decode())
            print("POST /api/host (sync) response keys:", list(host_sync.keys()))
            assert host_sync["status"] == "ok"
            assert "me" in host_sync
            assert host_sync["me"]["username"] == "TestHost" or host_sync["me"].get("name") == "TestHost"
            assert "events" in host_sync
            assert "clients" in host_sync
            assert "invites" in host_sync
            assert "fcm" in host_sync
            fcm_hash = host_sync["fcm"]["hash"]
            assert fcm_hash is not None

        # 6. Test POST /api/host with fcm_hash caching in JSON body (no query string)
        cached_sync_payload = json.dumps({
            "action": "sync",
            "fcm_hash": fcm_hash
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=cached_sync_payload,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            cached_sync = json.loads(resp.read().decode())
            print("POST /api/host (sync with fcm_hash in body) response fcm:", cached_sync["fcm"])
            assert cached_sync["fcm"].get("unchanged") is True
            assert cached_sync["fcm"].get("hash") == fcm_hash

        # 7. Test POST /api/host dispatcher: action: "direct_calls"
        action_payload = json.dumps({
            "action": "direct_calls",
            "allow_direct_calls": True
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=action_payload,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            act_res = json.loads(resp.read().decode())
            print("POST /api/host (direct_calls):", act_res)
            assert act_res.get("allow_direct_calls") is True or "message" in act_res

        # 8. Test POST /api/host dispatcher: action: "create_invite"
        invite_payload = json.dumps({
            "action": "create_invite",
            "name": "TestGuest"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=invite_payload,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            inv_res = json.loads(resp.read().decode())
            print("POST /api/host (create_invite):", inv_res)
            invite_code = inv_res.get("code")

        # 8b. Test POST /api/public (claim_invite) and POST /api/host (claim_invite)
        claim_payload = json.dumps({
            "action": "claim_invite",
            "code": invite_code,
            "name": "ClaimedGuest"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/public",
            data=claim_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            claim_res = json.loads(resp.read().decode())
            print("POST /api/public (claim_invite):", claim_res)
            assert claim_res.get("success") is True
            assert "uuid" in claim_res
            assert "token" in claim_res
            guest_uuid = claim_res["uuid"]

        # 8b. Test push token registration and deduplication
        push_payload = json.dumps({
            "action": "register_push",
            "pushToken": "fcm_test_token_123"
        }).encode()
        for attempt in [1, 2]:
            req = urllib.request.Request(
                f"{base_url}/api/host",
                data=push_payload,
                headers={
                    "Authorization": f"Bearer {access_token}",
                    "Content-Type": "application/json"
                }
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                assert resp.status == 200
                push_res = json.loads(resp.read().decode())
                assert push_res.get("status") == "ok"
        print("POST /api/host (register_push deduped): verified")

        # 8c. Test host response chat message routing (_handle_host_response)
        msg_payload = json.dumps({
            "action": "response",
            "type": "chat_message",
            "clientUuid": guest_uuid,
            "text": "Hello test guest",
            "sender": "phone"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=msg_payload,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            msg_res = json.loads(resp.read().decode())
            print("POST /api/host (response/chat_message):", msg_res)
            assert "Response routed" in msg_res.get("message", "")

        # 9. Test POST /api/host dispatcher: action: "ack"
        ack_payload = json.dumps({
            "action": "ack",
            "eventId": "evt_test_123"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=ack_payload,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            ack_res = json.loads(resp.read().decode())
            print("POST /api/host (ack):", ack_res)
            assert "message" in ack_res

        # 10. Verify GET /api/public now reflects initialized = True
        req = urllib.request.Request(f"{base_url}/api/public")
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            data = json.loads(resp.read().decode())
            print("GET /api/public after pairing:", data["initialized"], data["admin_host"])
            assert data["initialized"] is True
            assert data["admin_host"] == "TestHost"

        # 11. CRITICAL: Verify ALL legacy endpoints are completely ABOLISHED (Return 404 or 400 for query params)
        legacy_get_endpoints = [
            "/api/info",
            "/api/init-status",
            "/api/host/me",
            "/api/host/events",
            "/api/host/fcm_config",
            "/api/fcm_config",
            "/api/host/clients",
            "/api/host/invites",
            "/api/host/message",
            "/api/host/stream",
            "/api/messages",
            "/api/events",
            "/api/guest/events",
            "/api/call/status",
            "/api/client/hosts",
            "/api/widget/hosts",
            "/api/admin/overview"
        ]
        for ep in legacy_get_endpoints:
            req = urllib.request.Request(
                f"{base_url}{ep}",
                headers={"Authorization": f"Bearer {access_token}"}
            )
            try:
                urllib.request.urlopen(req, timeout=5)
                assert False, f"Legacy endpoint {ep} should have been abolished (404), but returned 200!"
            except urllib.error.HTTPError as e:
                assert e.code == 404, f"Legacy endpoint {ep} returned {e.code} instead of 404"
                print(f"Legacy GET {ep} correctly abolished (404)")

        legacy_post_endpoints = [
            "/api/register",
            "/api/register_mobile",
            "/api/claim_invite",
            "/api/file/upload",
            "/api/message",
            "/api/call",
            "/api/hangup",
            "/api/signal",
            "/api/host/register_push",
            "/api/host/fcm_config",
            "/api/host/direct_calls",
            "/api/host/widget_access",
            "/api/host/ack",
            "/api/host/create_invite",
            "/api/host/delete_invite",
            "/api/host/transfer_admin",
            "/api/host/delete_client",
            "/api/host/claim_remote_invite",
            "/api/host/response",
            "/api/admin/login",
            "/api/admin/config",
            "/api/admin/hosts/switch-admin",
            "/api/admin/hosts/delete",
            "/api/admin/clients/delete",
            "/api/admin/invites/create",
            "/api/admin/invites/delete",
            "/api/upload_service_account"
        ]
        dummy_body = json.dumps({"test": True}).encode()
        for ep in legacy_post_endpoints:
            req = urllib.request.Request(
                f"{base_url}{ep}",
                data=dummy_body,
                headers={
                    "Authorization": f"Bearer {access_token}",
                    "Content-Type": "application/json"
                }
            )
            try:
                urllib.request.urlopen(req, timeout=5)
                assert False, f"Legacy POST endpoint {ep} should have been abolished (404), but returned 200!"
            except urllib.error.HTTPError as e:
                assert e.code == 404, f"Legacy POST endpoint {ep} returned {e.code} instead of 404"
                print(f"Legacy POST {ep} correctly abolished (404)")

        print("\n========================================================")
        print("ALL TESTS PASSED: LEGACY ENDPOINTS COMPLETELY ABOLISHED!")
        print("========================================================")

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except Exception:
            proc.kill()

if __name__ == "__main__":
    run_tests()
