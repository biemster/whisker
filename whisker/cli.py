import sys
import os
import json
import argparse
import sqlite3
import urllib.request
import urllib.parse
import threading
import time
import asyncio

if __package__ is None or __package__ == '':
    sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from whisker.server import run_server
try:
    from whisker.webrtc_manager import WhiskerWebRTCSession
except Exception:
    WhiskerWebRTCSession = None

CLI_DB_FILE = 'whisker.db'

def init_cli_db():
    conn = sqlite3.connect(CLI_DB_FILE)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS config (
            key TEXT PRIMARY KEY,
            value TEXT
        )
    ''')
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS connections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT,
            uuid TEXT UNIQUE,
            token TEXT,
            server_url TEXT,
            host_uuid TEXT,
            host_name TEXT,
            is_active INTEGER DEFAULT 0,
            created_at INTEGER
        )
    ''')
    conn.commit()

    # Migrate legacy config if connections table is empty
    cursor.execute('SELECT COUNT(*) FROM connections')
    count = cursor.fetchone()[0]
    if count == 0:
        cursor.execute('SELECT value FROM config WHERE key = "uuid"')
        u_row = cursor.fetchone()
        cursor.execute('SELECT value FROM config WHERE key = "token"')
        t_row = cursor.fetchone()
        cursor.execute('SELECT value FROM config WHERE key = "server_url"')
        s_row = cursor.fetchone()
        cursor.execute('SELECT value FROM config WHERE key = "name"')
        n_row = cursor.fetchone()
        cursor.execute('SELECT value FROM config WHERE key = "host_uuid"')
        h_row = cursor.fetchone()
        cursor.execute('SELECT value FROM config WHERE key = "host_name"')
        hn_row = cursor.fetchone()

        if u_row and t_row and s_row:
            cursor.execute('''
                INSERT INTO connections (name, uuid, token, server_url, host_uuid, host_name, is_active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
            ''', (
                n_row[0] if n_row else 'Guest',
                u_row[0],
                t_row[0],
                s_row[0],
                h_row[0] if h_row else '',
                hn_row[0] if hn_row else 'Primary Device',
                int(time.time() * 1000)
            ))
            conn.commit()

    return conn

def set_config(conn, key, value):
    cursor = conn.cursor()
    cursor.execute('INSERT OR REPLACE INTO config (key, value) VALUES (?, ?)', (key, value))
    conn.commit()

def get_config(conn, key):
    cursor = conn.cursor()
    cursor.execute('SELECT value FROM config WHERE key = ?', (key,))
    row = cursor.fetchone()
    return row[0] if row else None

def get_all_connections(conn):
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM connections ORDER BY id ASC')
    return [dict(r) for r in cursor.fetchall()]

def get_active_connection(conn):
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM connections WHERE is_active = 1 LIMIT 1')
    row = cursor.fetchone()
    if row:
        return dict(row)
    # If none is active but connections exist, activate the first one
    cursor.execute('SELECT * FROM connections ORDER BY id ASC LIMIT 1')
    row = cursor.fetchone()
    if row:
        c = dict(row)
        cursor.execute('UPDATE connections SET is_active = 1 WHERE id = ?', (c['id'],))
        conn.commit()
        sync_legacy_config(conn, c)
        return c
    return None

def sync_legacy_config(conn, conn_dict):
    if not conn_dict:
        return
    set_config(conn, 'server_url', conn_dict.get('server_url', ''))
    set_config(conn, 'uuid', conn_dict.get('uuid', ''))
    set_config(conn, 'token', conn_dict.get('token', ''))
    set_config(conn, 'name', conn_dict.get('name', ''))
    set_config(conn, 'host_uuid', conn_dict.get('host_uuid', ''))
    set_config(conn, 'host_name', conn_dict.get('host_name', ''))

def resolve_connection(conn, identifier=None):
    connections = get_all_connections(conn)
    if not connections:
        print("Error: No connections found. Run 'whisker init <invite_url>' first to pair with a host device.")
        sys.exit(1)

    if not identifier:
        active = get_active_connection(conn)
        if not active:
            print("Error: No active connection found. Run 'whisker connections' to inspect configured connections.")
            sys.exit(1)
        return active

    ident_str = str(identifier).strip()

    # 1. Match by numeric ID
    if ident_str.isdigit():
        target_id = int(ident_str)
        for c in connections:
            if c['id'] == target_id:
                return c

    # 2. Match by exact Client UUID
    for c in connections:
        if c['uuid'].lower() == ident_str.lower():
            return c

    # 3. Match by Host UUID
    for c in connections:
        if c['host_uuid'] and c['host_uuid'].lower() == ident_str.lower():
            return c

    # 4. Match by Host Name (exact or substring)
    for c in connections:
        if c['host_name'] and ident_str.lower() == c['host_name'].lower():
            return c

    for c in connections:
        if c['host_name'] and ident_str.lower() in c['host_name'].lower():
            return c

    # 5. Match by Client Name
    for c in connections:
        if c['name'] and ident_str.lower() in c['name'].lower():
            return c

    print(f"Error: Connection '{identifier}' not found.")
    print("\nAvailable connections:")
    do_list_connections(conn)
    sys.exit(1)

def do_init(invite_url):
    try:
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
            # Maybe raw code and default server or full url
            if invite_url.startswith('http://') or invite_url.startswith('https://'):
                server_url = invite_url.rstrip('/')
                code = input("Enter invite code: ").strip()
            else:
                print("Invalid invite URL format. Expected: http://server:3000/invite/CODE or http://server:3000?code=CODE")
                sys.exit(1)

        req_data = json.dumps({"code": code}).encode('utf-8')
        req = urllib.request.Request(f"{server_url}/api/claim_invite", data=req_data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if 'error' in res_data:
                print(f"Error: {res_data['error']}")
                sys.exit(1)

            uuid = res_data.get('uuid')
            token = res_data.get('token')
            name = res_data.get('name')
            host_uuid = res_data.get('host_uuid', '')
            host_name = res_data.get('host_name', 'Primary Device')

            if not uuid or not token:
                print("Invalid response from server (missing uuid or token).")
                sys.exit(1)

            conn = init_cli_db()
            cursor = conn.cursor()

            # Set other connections as inactive
            cursor.execute('UPDATE connections SET is_active = 0')
            cursor.execute('''
                INSERT OR REPLACE INTO connections (name, uuid, token, server_url, host_uuid, host_name, is_active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
            ''', (name, uuid, token, server_url, host_uuid, host_name, int(time.time() * 1000)))
            conn.commit()

            cursor.execute('SELECT * FROM connections WHERE uuid = ?', (uuid,))
            saved = dict(cursor.fetchone())
            sync_legacy_config(conn, saved)

            print(f"\n✓ Successfully paired with '{host_name}'!")
            print(f"  Connection ID: #{saved['id']} [ACTIVE]")
            print(f"  Paired Host:   {host_name} ({host_uuid or 'global'})")
            print(f"  Client Name:   {name}")
            print(f"  Client UUID:   {uuid}")
            print(f"  Home Server:   {server_url}\n")
            print("Commands:")
            print("  whisker send <message>               Send a message to active host")
            print("  whisker send --contact <name> <msg>  Send message to a specific host")
            print("  whisker call                         Initiate audio/video call to active host")
            print("  whisker listen                       Listen for live SSE events")
            print("  whisker connections                  List all paired devices")
            print("  whisker switch <id|name>             Switch active connection")

    except Exception as e:
        print(f"Failed to initialize: {e}")
        sys.exit(1)

def do_list_connections(conn=None):
    if conn is None:
        if not os.path.exists(CLI_DB_FILE):
            print("No connections configured yet. Run 'whisker init <invite_url>' to connect to a host.")
            return
        conn = init_cli_db()

    conns = get_all_connections(conn)
    if not conns:
        print("No connections configured yet. Run 'whisker init <invite_url>' to connect to a host.")
        return

    print("\n--- Paired Whisker Connections ---")
    print(f"{'STATUS':<8} {'ID':<4} {'HOST / DEVICE':<22} {'AS CLIENT':<16} {'UUID':<18} {'SERVER URL'}")
    print("-" * 90)
    for c in conns:
        status = "[ACTIVE]" if c.get('is_active') == 1 else ""
        cid = f"#{c['id']}"
        h_name = (c.get('host_name') or 'Primary Device')[:20]
        c_name = (c.get('name') or 'Guest')[:14]
        uuid_disp = (c.get('uuid') or '')[:16]
        s_url = c.get('server_url') or ''
        print(f"{status:<8} {cid:<4} {h_name:<22} {c_name:<16} {uuid_disp:<18} {s_url}")
    print("\nTip: Use 'whisker switch <id|name>' to switch default connection.")
    print("     Use '--contact <id|name>' with send/call/listen/info to target specific devices.\n")

def do_switch_connection(identifier):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    conn = init_cli_db()
    target = resolve_connection(conn, identifier)

    cursor = conn.cursor()
    cursor.execute('UPDATE connections SET is_active = 0')
    cursor.execute('UPDATE connections SET is_active = 1 WHERE id = ?', (target['id'],))
    conn.commit()

    sync_legacy_config(conn, target)
    print(f"✓ Switched active connection to #{target['id']} '{target.get('host_name', 'Primary Device')}' ({target['server_url']}).")

def do_remove_connection(identifier):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    conn = init_cli_db()
    target = resolve_connection(conn, identifier)

    cursor = conn.cursor()
    cursor.execute('DELETE FROM connections WHERE id = ?', (target['id'],))
    conn.commit()

    print(f"Removed connection #{target['id']} '{target.get('host_name', 'Primary Device')}'.")

    # If it was active, pick a new active connection
    remaining = get_all_connections(conn)
    if remaining:
        if not any(c['is_active'] == 1 for c in remaining):
            new_active = remaining[0]
            cursor.execute('UPDATE connections SET is_active = 1 WHERE id = ?', (new_active['id'],))
            conn.commit()
            sync_legacy_config(conn, new_active)
            print(f"Active connection is now #{new_active['id']} '{new_active.get('host_name', 'Primary Device')}'.")
    else:
        cursor.execute('DELETE FROM config')
        conn.commit()
        print("No connections remaining.")

def do_send(msg, contact=None, username=None):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    target_ident = username or contact
    conn = init_cli_db()
    target = resolve_connection(conn, target_ident)

    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    host_name = target.get('host_name') or 'Host'
    host_uuid = target.get('host_uuid') or ''

    try:
        payload = {
            "uuid": uuid,
            "token": token,
            "text": msg
        }
        if host_uuid:
            payload["contact"] = host_uuid
            payload["host_uuid"] = host_uuid

        req_data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(f"{server_url}/api/message", data=req_data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if 'error' in res_data:
                print(f"Error: {res_data['error']}")
            else:
                if res_data.get('host_name') and res_data.get('host_name') != host_name:
                    cursor = conn.cursor()
                    cursor.execute('UPDATE connections SET host_name = ? WHERE id = ?', (res_data['host_name'], target['id']))
                    conn.commit()
                    host_name = res_data['host_name']
                print(f"[{host_name}] Message sent.")
    except Exception as e:
        print(f"Failed to send message to {host_name}: {e}")
        sys.exit(1)

def do_call(contact=None, username=None, video_source=None, audio_source=None, save_streams=False):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    if WhiskerWebRTCSession is None:
        print("Error: WebRTC calling requires aiortc, pillow, and av. Please install with: pip install aiortc pillow av")
        sys.exit(1)

    target_ident = username or contact
    conn = init_cli_db()
    target = resolve_connection(conn, target_ident)

    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    name = target.get('name') or 'Whisker CLI'
    host_name = target.get('host_name') or 'Host'
    host_uuid = target.get('host_uuid') or ''

    session = WhiskerWebRTCSession(
        server_url=server_url,
        client_uuid=uuid,
        client_token=token,
        client_name=name,
        host_uuid=host_uuid,
        host_name=host_name,
        save_streams=save_streams,
        on_hangup=lambda: stop_event.set()
    )

    loop = asyncio.new_event_loop()
    session.loop = loop
    stop_event = threading.Event()
    call_start_timestamp = time.time()

    def sse_worker():
        query = urllib.parse.urlencode({'uuid': uuid, 'token': token})
        url = f"{server_url}/api/events?{query}"
        while not stop_event.is_set():
            try:
                req = urllib.request.Request(url, headers={'Accept': 'text/event-stream'})
                with urllib.request.urlopen(req, timeout=30) as resp:
                    while not stop_event.is_set():
                        line = resp.readline()
                        if not line:
                            break
                        line_str = line.decode('utf-8').strip()
                        if line_str.startswith('data:'):
                            try:
                                raw_data = json.loads(line_str[5:].strip())
                                inner_payload = raw_data.get('payload') if isinstance(raw_data.get('payload'), dict) else raw_data
                                evt = inner_payload.get('type') or inner_payload.get('event') or raw_data.get('type') or raw_data.get('event') or ''

                                # Ignore old buffered events created prior to this call
                                ev_ts = inner_payload.get('timestamp') or raw_data.get('timestamp')
                                if ev_ts and (ev_ts / 1000.0) < (call_start_timestamp - 2.0):
                                    continue

                                # Validate call_id if present
                                event_call_id = inner_payload.get('call_id') or inner_payload.get('callId') or raw_data.get('call_id') or raw_data.get('callId')
                                if event_call_id and session.call_id and str(event_call_id).strip() != str(session.call_id).strip():
                                    continue

                                is_answer = evt in ['webrtc_call_answer', 'call_answered'] or (bool(inner_payload.get('sdp')) and 'answer' in str(inner_payload.get('sdp')).lower())
                                is_candidate = (evt in ['webrtc_ice_candidate', 'ice_candidate'] or bool(inner_payload.get('candidate')) or bool(raw_data.get('candidate')))
                                is_hangup = (
                                    evt in ['webrtc_call_hangup', 'call_ended', 'call_declined', 'hangup', 'call_rejected', 'call_cancelled', 'call_timeout'] or
                                    str(inner_payload.get('status')).lower() in ['ended', 'declined', 'rejected', 'cancelled', 'hungup'] or
                                    str(raw_data.get('status')).lower() in ['ended', 'declined', 'rejected', 'cancelled', 'hungup'] or
                                    str(inner_payload.get('reason')).lower() in ['ended', 'declined', 'rejected', 'cancelled', 'timeout', 'stale']
                                )

                                if is_answer:
                                    sdp = inner_payload.get('sdp') or raw_data.get('sdp')
                                    if sdp and session.is_active and loop.is_running():
                                        asyncio.run_coroutine_threadsafe(session.handle_remote_answer(sdp), loop)
                                elif is_candidate and session.is_active and loop.is_running():
                                    cand = inner_payload.get('candidate') or raw_data.get('candidate')
                                    if cand:
                                        asyncio.run_coroutine_threadsafe(session.handle_remote_ice_candidate(cand), loop)
                                elif is_hangup:
                                    if session.is_active:
                                        print(f"\n📴 [{host_name} Call] Remote party ended or declined the call.")
                                        session.hangup(notify_server=False)
                                    stop_event.set()
                                    return
                            except Exception:
                                pass
            except Exception:
                if not stop_event.is_set():
                    time.sleep(1.0)

    sse_thread = threading.Thread(target=sse_worker, daemon=True)
    sse_thread.start()

    def status_poller_worker():
        while not stop_event.is_set():
            time.sleep(1.0)
            if stop_event.is_set() or not session.is_active:
                break
            try:
                q = urllib.parse.urlencode({'call_id': session.call_id, 'uuid': uuid})
                s_url = f"{server_url}/api/call/status?{q}"
                s_req = urllib.request.Request(s_url)
                with urllib.request.urlopen(s_req, timeout=3) as s_resp:
                    st_data = json.loads(s_resp.read().decode('utf-8'))
                    st = (st_data.get('status') or '').upper()
                    is_act = st_data.get('active', False)
                    if st in ['ENDED', 'DECLINED', 'REJECTED', 'CANCELLED'] or (st == 'IDLE' and not is_act):
                        if session.is_active:
                            print(f"\n📴 [{host_name} Call] Remote party ended or declined the call.")
                            session.hangup(notify_server=False)
                        stop_event.set()
                        break
            except Exception:
                pass

    poller_thread = threading.Thread(target=status_poller_worker, daemon=True)
    poller_thread.start()

    async def run_call():
        success = await session.start_outgoing_call(video_source=video_source, audio_source=audio_source)
        if not success:
            stop_event.set()
            return
        print(f"[{host_name} Call] Calling in progress... (Press Ctrl+C or end on Mobile App)")
        while session.is_active and not stop_event.is_set():
            await asyncio.sleep(0.1)

    try:
        loop.run_until_complete(run_call())
    except KeyboardInterrupt:
        print(f"\n[{host_name} Call] Ending call...")
    finally:
        stop_event.set()
        session.hangup(notify_server=True)
        try:
            pending = [t for t in asyncio.all_tasks(loop) if not t.done()]
            for t in pending:
                t.cancel()
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            loop.close()
        except Exception:
            pass

def _listen_stream_worker(target, label, video_source=None, audio_source=None, save_streams=False):
    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    host_name = target.get('host_name') or label
    name = target.get('name') or 'Whisker CLI'

    query = urllib.parse.urlencode({'uuid': uuid, 'token': token})
    url = f"{server_url}/api/events?{query}"

    active_call_session = None
    call_loop = None

    while True:
        try:
            req = urllib.request.Request(url, headers={'Accept': 'text/event-stream'})
            with urllib.request.urlopen(req, timeout=30) as response:
                while True:
                    line = response.readline()
                    if not line:
                        break

                    line_str = line.decode('utf-8').strip()
                    if line_str.startswith('data:'):
                        data_str = line_str[5:].strip()
                        try:
                            raw_data = json.loads(data_str)
                            inner_payload = raw_data.get('payload') if isinstance(raw_data.get('payload'), dict) else raw_data
                            event_type = inner_payload.get('event') or inner_payload.get('type') or raw_data.get('event') or raw_data.get('type') or ''

                            is_incoming_call = (
                                event_type in ['webrtc_call_initiate', 'call', 'call_initiate'] or
                                raw_data.get('type') in ['webrtc_call_initiate', 'call', 'call_initiate'] or
                                raw_data.get('event') in ['call', 'webrtc_call_initiate'] or
                                (bool(inner_payload.get('sdp')) and ('offer' in str(inner_payload.get('sdp')).lower() or event_type == 'call'))
                            )
                            is_ice_candidate = (
                                event_type in ['webrtc_ice_candidate', 'ice_candidate'] or
                                bool(inner_payload.get('candidate')) or
                                bool(raw_data.get('candidate'))
                            ) and bool(inner_payload.get('candidate') or raw_data.get('candidate'))

                            is_hangup = (
                                event_type in ['hangup', 'call_ended', 'webrtc_call_hangup', 'call_declined', 'call_rejected', 'call_cancelled', 'call_timeout'] or
                                str(inner_payload.get('status')).lower() in ['ended', 'declined', 'rejected', 'cancelled', 'hungup'] or
                                str(raw_data.get('status')).lower() in ['ended', 'declined', 'rejected', 'cancelled', 'hungup'] or
                                str(inner_payload.get('reason')).lower() in ['ended', 'declined', 'rejected', 'cancelled', 'timeout', 'stale']
                            )

                            if event_type in ['chat_message', 'message']:
                                if (inner_payload.get('sender') or raw_data.get('sender')) == 'phone':
                                    print(f"\n[{host_name}] {inner_payload.get('text') or raw_data.get('text')}")
                            elif event_type == 'call_accepted':
                                print(f"\n[{host_name} Call] Host accepted the call.")
                            elif event_type == 'call_rejected':
                                print(f"\n[{host_name} Call] Host rejected the call.")
                            elif is_incoming_call:
                                caller_name = inner_payload.get('name') or raw_data.get('name') or host_name
                                caller_uuid = inner_payload.get('host_uuid') or raw_data.get('host_uuid') or target.get('host_uuid') or ''
                                is_video = inner_payload.get('video') is not False and raw_data.get('video') is not False
                                incoming_call_id = inner_payload.get('call_id') or inner_payload.get('callId') or raw_data.get('call_id') or raw_data.get('callId')

                                if WhiskerWebRTCSession is None:
                                    print(f"\n📞 [{caller_name} Call] Incoming Call received. (Install aiortc, pillow, and av to enable real-time WebRTC media rendering).")
                                    continue

                                print(f"\n📞 [{caller_name} Call] Incoming Audio+Video Call (ID: {incoming_call_id or 'new'})! Auto-accepting & opening live media...")

                                if active_call_session and active_call_session.is_active:
                                    active_call_session.hangup()

                                active_call_session = WhiskerWebRTCSession(
                                    server_url=server_url,
                                    client_uuid=uuid,
                                    client_token=token,
                                    client_name=name,
                                    host_uuid=caller_uuid,
                                    host_name=caller_name,
                                    call_id=incoming_call_id,
                                    save_streams=save_streams
                                )

                                call_loop = asyncio.new_event_loop()
                                active_call_session.loop = call_loop

                                offer_sdp = inner_payload.get('sdp') or raw_data.get('sdp') or ''

                                def start_incoming_call(sess, loop_inst, sdp, cname, is_vid, v_src, a_src, cid):
                                    asyncio.set_event_loop(loop_inst)
                                    try:
                                        loop_inst.run_until_complete(
                                            sess.accept_incoming_call(
                                                offer_sdp=sdp,
                                                peer_name=cname,
                                                is_video=is_vid,
                                                video_source=v_src,
                                                audio_source=a_src,
                                                call_id=cid
                                            )
                                        )
                                        loop_inst.run_forever()
                                    except Exception:
                                        pass
                                    finally:
                                        try:
                                            loop_inst.close()
                                        except Exception:
                                            pass

                                t = threading.Thread(
                                    target=start_incoming_call,
                                    args=(active_call_session, call_loop, offer_sdp, caller_name, is_video, video_source, audio_source, incoming_call_id),
                                    daemon=True
                                )
                                t.start()

                            elif is_ice_candidate:
                                event_cid = inner_payload.get('call_id') or inner_payload.get('callId') or raw_data.get('call_id') or raw_data.get('callId')
                                if event_cid and active_call_session and active_call_session.call_id and str(event_cid).strip() != str(active_call_session.call_id).strip():
                                    pass
                                elif active_call_session and call_loop and active_call_session.is_active and call_loop.is_running():
                                    cand = inner_payload.get('candidate') or raw_data.get('candidate')
                                    asyncio.run_coroutine_threadsafe(
                                        active_call_session.handle_remote_ice_candidate(cand),
                                        call_loop
                                    )
                            elif is_hangup:
                                event_cid = inner_payload.get('call_id') or inner_payload.get('callId') or raw_data.get('call_id') or raw_data.get('callId')
                                if event_cid and active_call_session and active_call_session.call_id and str(event_cid).strip() != str(active_call_session.call_id).strip():
                                    pass
                                else:
                                    print(f"\n📴 [{host_name} Call] Call ended by remote host.")
                                    if active_call_session and active_call_session.is_active:
                                        active_call_session.hangup()
                                        active_call_session = None
                            else:
                                print(f"\n[{host_name} Event] {raw_data}")
                        except json.JSONDecodeError:
                            print(f"\n[{host_name} Raw Event] {data_str}")
        except Exception as e:
            time.sleep(1.0)
        finally:
            if active_call_session and active_call_session.is_active:
                active_call_session.hangup()

def do_listen(contact=None, username=None, listen_all=False, video_source=None, audio_source=None, save_streams=False):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    target_ident = username or contact
    conn = init_cli_db()

    if listen_all:
        connections = get_all_connections(conn)
        if not connections:
            print("No connections found.")
            return

        print(f"Listening for events across all {len(connections)} paired connections (Press Ctrl+C to stop)...")
        threads = []
        for c in connections:
            t = threading.Thread(
                target=_listen_stream_worker,
                args=(c, c.get('host_name') or f"#{c['id']}", video_source, audio_source, save_streams),
                daemon=True
            )
            t.start()
            threads.append(t)

        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\nStopped listening.")
            return

    target = resolve_connection(conn, target_ident)
    host_name = target.get('host_name') or 'Host'
    print(f"Listening for events from '{host_name}' ({target['server_url']}) (Press Ctrl+C to stop)...")

    try:
        _listen_stream_worker(target, host_name, video_source=video_source, audio_source=audio_source, save_streams=save_streams)
    except KeyboardInterrupt:
        print("\nStopped listening.")

def do_info(contact=None, username=None):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    target_ident = username or contact
    conn = init_cli_db()
    target = resolve_connection(conn, target_ident)

    server_url = target['server_url']
    uuid = target['uuid']
    name = target['name']
    host_name = target.get('host_name') or 'Primary Device'
    host_uuid = target.get('host_uuid') or 'N/A'

    print(f"--- Client Configuration (Connection #{target['id']}) ---")
    print(f"Host / Device: {host_name} ({host_uuid})")
    print(f"Client Name:   {name}")
    print(f"Client UUID:   {uuid}")
    print(f"Home Server:   {server_url}")
    print(f"Active Status: {'[ACTIVE]' if target.get('is_active') == 1 else '[INACTIVE]'}")

    try:
        req = urllib.request.Request(f"{server_url}/api/info")
        with urllib.request.urlopen(req, timeout=5) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            print("\n--- Home Server Status ---")

            host_connected = res_data.get('host_connected', False)
            push_configured = res_data.get('push_configured', False)
            host_reachable = res_data.get('host_reachable', False)

            print(f"Direct connection to Mobile App: {'Active (Online)' if host_connected else 'Inactive'}")
            print(f"Push notifications enabled:      {'Yes' if push_configured else 'No'}")

            if host_reachable:
                print("E2E Status: The mobile app is reachable.")
            else:
                print("E2E Status: The mobile app is currently unreachable.")

    except Exception as e:
        print(f"\n--- Home Server Status ---")
        print(f"Could not connect to home server: {e}")

    # Also display overview of all connections
    do_list_connections(conn)

def print_help():
    print("""Whisker Unified CLI & Home Server Package

Usage:
  whisker server [--port PORT] [--db DB_FILE]                               Start Whisker Home Server
  whisker init <invite_url>                                                 Initialize/pair with a host via invite URL
  whisker connections (or: list, contacts)                                  List all configured paired host connections
  whisker switch <id|name>                                                  Switch the active default host connection
  whisker remove <id|name>                                                  Remove/disconnect a paired host connection
  whisker send [-u USER] [-c CONTACT] <message>                             Send message to active or targeted host
  whisker call [-u USER] [-c CONTACT] [-v DEV/FILE] [-a DEV/FILE] [--save-streams]  Initiate WebRTC call (A/V stream, records to <caller>.<callee>.<unixepoch>.mp4)
  whisker listen [-u USER] [-c CONTACT] [-v DEV/FILE] [-a DEV/FILE] [--save-streams] [--all]  Listen for SSE events & auto-answer incoming WebRTC calls
  whisker info [-u USER] [-c CONTACT]                                       Print connection & server status
  whisker setup-fcm                                                         Run Firebase / FCM OAuth2 setup tool
  whisker help                                                              Show this help menu
""")

def main():
    if len(sys.argv) < 2:
        print_help()
        sys.exit(1)

    cmd = sys.argv[1]

    if cmd in ['server', 'start']:
        parser = argparse.ArgumentParser(description="Start Whisker Home Server")
        parser.add_argument('--port', type=int, default=int(os.environ.get('PORT', 3000)), help="Port to listen on")
        parser.add_argument('--db', type=str, default=os.environ.get('DB_FILE', 'whisker_server.db'), help="Path to SQLite database file")
        args = parser.parse_args(sys.argv[2:])
        run_server(port=args.port, db_path=args.db)

    elif cmd == 'init':
        if len(sys.argv) < 3:
            print("Missing invite URL. Usage: whisker init <invite_url>")
            sys.exit(1)
        do_init(sys.argv[2])

    elif cmd in ['connections', 'contacts', 'list', 'conns']:
        do_list_connections()

    elif cmd in ['switch', 'use', 'select']:
        if len(sys.argv) < 3:
            print("Missing connection identifier. Usage: whisker switch <id|name|uuid>")
            do_list_connections()
            sys.exit(1)
        do_switch_connection(sys.argv[2])

    elif cmd in ['remove', 'delete', 'disconnect']:
        if len(sys.argv) < 3:
            print("Missing connection identifier. Usage: whisker remove <id|name|uuid>")
            do_list_connections()
            sys.exit(1)
        do_remove_connection(sys.argv[2])

    elif cmd == 'send':
        parser = argparse.ArgumentParser(description="Send message to Home Server host")
        parser.add_argument('-u', '--username', type=str, default=None, help="Target specific host/user by username or contact")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host by ID, name, or UUID")
        parser.add_argument('message', nargs='+', help="Message text to send")
        args = parser.parse_args(sys.argv[2:])
        do_send(" ".join(args.message), contact=args.contact, username=args.username)

    elif cmd == 'call':
        parser = argparse.ArgumentParser(description="Initiate WebRTC call to Home Server host")
        parser.add_argument('-u', '--username', type=str, default=None, help="Target specific host/user by username or contact")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host by ID, name, or UUID")
        parser.add_argument('-v', '--video', type=str, default=None, help="Video source (e.g. Linux V4L2 device /dev/video0 or file)")
        parser.add_argument('-a', '--audio', type=str, default=None, help="Audio source (e.g. Linux ALSA default, pulse, or file)")
        parser.add_argument('--save-streams', action='store_true', help="Save incoming and outgoing audio+video streams to a single MP4 file (<caller>.<callee>.<unixepoch>.mp4)")
        args = parser.parse_args(sys.argv[2:])
        do_call(contact=args.contact, username=args.username, video_source=args.video, audio_source=args.audio, save_streams=args.save_streams)

    elif cmd == 'listen':
        parser = argparse.ArgumentParser(description="Listen for SSE events from Home Server")
        parser.add_argument('-u', '--username', type=str, default=None, help="Target specific host/user by username or contact")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host by ID, name, or UUID")
        parser.add_argument('-v', '--video', type=str, default=None, help="Video source to stream if a call arrives (e.g. /dev/video0)")
        parser.add_argument('-a', '--audio', type=str, default=None, help="Audio source to stream if a call arrives (e.g. default, pulse)")
        parser.add_argument('--save-streams', action='store_true', help="Save incoming and outgoing audio+video streams to a single MP4 file (<caller>.<callee>.<unixepoch>.mp4)")
        parser.add_argument('--all', action='store_true', help="Listen to events across all paired connections simultaneously")
        args = parser.parse_args(sys.argv[2:])
        do_listen(contact=args.contact, username=args.username, listen_all=args.all, video_source=args.video, audio_source=args.audio, save_streams=args.save_streams)

    elif cmd == 'info':
        parser = argparse.ArgumentParser(description="Show client info and server status")
        parser.add_argument('-u', '--username', type=str, default=None, help="Target specific host/user by username or contact")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host by ID, name, or UUID")
        args = parser.parse_args(sys.argv[2:])
        do_info(contact=args.contact, username=args.username)

    elif cmd in ['setup-fcm', 'fcm-setup']:
        from whisker.setup_fcm import main as run_setup_fcm
        run_setup_fcm()

    elif cmd in ['-h', '--help', 'help']:
        print_help()

    else:
        print(f"Unknown command: {cmd}\n")
        print_help()
        sys.exit(1)

if __name__ == '__main__':
    main()
