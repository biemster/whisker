import os
import sys
import json
import time
import tempfile
import urllib.request
import urllib.error
import subprocess

def test_embed_visitor():
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "test_embed.db")
    port = 39282

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
        print(f"Testing Embed Visitor flow against {base_url}...")

        # 0. Test OPTIONS /api/host (CORS preflight)
        opt_req = urllib.request.Request(f"{base_url}/api/host", method="OPTIONS")
        with urllib.request.urlopen(opt_req, timeout=5) as resp:
            assert resp.status == 200
            assert resp.headers.get("Access-Control-Allow-Origin") == "*"
            print("OPTIONS /api/host CORS preflight succeeded (200)")

        # 1. Register host 'b100'
        reg_host_payload = json.dumps({
            "action": "register_mobile",
            "username": "b100",
            "pin_token": "pin_b100_secret"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=reg_host_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            host_data = json.loads(resp.read().decode())
            host_uuid = host_data["host_uuid"]
            print(f"Host b100 registered with UUID {host_uuid}")

        # 2. Embed client registers visitor via POST /api/public
        visitor_uuid = "emb_mwax5b7aqdrpfm"
        visitor_reg = json.dumps({
            "action": "register_visitor",
            "uuid": visitor_uuid,
            "name": "Website Visitor",
            "reason": "Website visitor on test.local",
            "client_type": "EMBED",
            "target_host": host_uuid
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/public",
            data=visitor_reg,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            reg_res = json.loads(resp.read().decode())
            assert reg_res.get("status") == "queued"
            print("Visitor registered via POST /api/public (200)")

        # 3. Embed client polls sync BEFORE approval -> Must return 200 with status: PENDING (NOT 401!)
        sync_payload = json.dumps({
            "action": "sync",
            "uuid": visitor_uuid,
            "token": ""
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=sync_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            sync_res = json.loads(resp.read().decode())
            assert sync_res.get("status") == "PENDING"
            print("Visitor sync before approval returned 200 with status: PENDING")

        # 4. Host b100 approves the visitor
        visitor_token = "tok_emb_test_token_123"
        approval_payload = json.dumps({
            "action": "response",
            "type": "registration_response",
            "event": "approved",
            "clientUuid": visitor_uuid,
            "token": visitor_token
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=approval_payload,
            headers={
                "Authorization": "Bearer pin_b100_secret",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            appr_res = json.loads(resp.read().decode())
            print("Host approved visitor (200)")

        # 5. Embed client polls sync AFTER approval -> Must return 200 with status: APPROVED and token!
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=sync_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            sync_res = json.loads(resp.read().decode())
            assert sync_res.get("status") == "APPROVED"
            assert sync_res.get("token") == visitor_token
            print(f"Visitor sync after approval returned 200 with token {visitor_token}")

        # 6. Embed client sends chat message to host
        chat_payload = json.dumps({
            "action": "message",
            "uuid": visitor_uuid,
            "token": visitor_token,
            "name": "Website Visitor",
            "contact": host_uuid,
            "text": "Hello host, this is from the embed widget!"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=chat_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            chat_res = json.loads(resp.read().decode())
            assert "Message routed to host" in chat_res.get("message", "")
            print("Embed visitor chat message successfully routed to host (200)")

        # 7. Host verifies message is in events
        host_sync = json.dumps({
            "action": "sync"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=host_sync,
            headers={
                "Authorization": "Bearer pin_b100_secret",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            h_data = json.loads(resp.read().decode())
            events = h_data.get("events", [])
            msg_evt = next((e for e in events if (e.get("type") == "message" or e.get("event") == "message" or (isinstance(e.get("payload"), dict) and e.get("payload", {}).get("event") == "message")) and "Hello host, this is from the embed widget!" in str(e.get("payload", ""))), None)
            assert msg_evt is not None
            print("Host successfully received message in host sync events!")

        # 8. Host calls visitor
        call_id = f"call_{int(time.time()*1000)}"
        call_payload = json.dumps({
            "action": "call",
            "type": "webrtc_call_initiate",
            "clientUuid": visitor_uuid,
            "call_id": call_id,
            "sdp": "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=call_payload,
            headers={
                "Authorization": "Bearer pin_b100_secret",
                "Content-Type": "application/json"
            }
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            print("Host initiated call to visitor (200)")

        # 9. Embed visitor picks up call (answers with SDP) -> must NOT 401!
        answer_payload = json.dumps({
            "action": "signal",
            "event": "signal",
            "type": "webrtc_call_answer",
            "uuid": visitor_uuid,
            "token": visitor_token,
            "call_id": call_id,
            "sdp": "v=0\r\no=- 67890 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=answer_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            ans_res = json.loads(resp.read().decode())
            assert "Signal routed to host" in ans_res.get("message", "")
            print("Embed visitor answer successfully routed to host (200)")

        # 10. Embed visitor sends ICE candidate -> must NOT 401!
        ice_payload = json.dumps({
            "action": "signal",
            "event": "signal",
            "type": "webrtc_ice_candidate",
            "uuid": visitor_uuid,
            "token": visitor_token,
            "call_id": call_id,
            "candidate": {"candidate": "candidate:12345 1 udp 2122260223 127.0.0.1 54321 typ host"}
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=ice_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            ice_res = json.loads(resp.read().decode())
            assert "Signal routed to host" in ice_res.get("message", "")
            print("Embed visitor ICE candidate successfully routed to host (200)")

        # 11. Embed visitor file upload
        file_payload = json.dumps({
            "action": "file_upload",
            "uuid": visitor_uuid,
            "token": visitor_token,
            "contact": host_uuid,
            "filename": "screenshot.png",
            "text": "screenshot.png",
            "content": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        }).encode()
        req = urllib.request.Request(
            f"{base_url}/api/host",
            data=file_payload,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            assert resp.status == 200
            file_res = json.loads(resp.read().decode())
            assert file_res.get("status") == "ok"
            assert "filePath" in file_res
            print(f"Embed visitor file upload succeeded (200): {file_res['filePath']}")

        print("\n========================================================")
        print("ALL EMBED VISITOR TESTS PASSED SUCCESSFULLY!")
        print("========================================================")

    finally:
        proc.terminate()
        proc.wait()

if __name__ == "__main__":
    test_embed_visitor()
