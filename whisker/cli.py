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
import re

if __package__ is None or __package__ == '':
    sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from whisker.server import run_server
try:
    from whisker.webrtc_manager import WhiskerWebRTCSession
except Exception:
    WhiskerWebRTCSession = None


def sanitize_name(name: str) -> str:
    """Sanitize a username or server name for safe inclusion in filenames."""
    if not name:
        return 'default'
    clean = "".join(c if c.isalnum() else '_' for c in str(name).strip().lower())
    clean = re.sub(r'_+', '_', clean).strip('_')
    return clean or 'default'


def sanitize_filename(filename: str) -> str:
    """Sanitize a filename to prevent invalid URL characters, spaces, and percent encoding."""
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


def format_client_db_name(username: str, server_name: str) -> str:
    """Returns the standardized client database filename: whisker_client_<username>_<servername>.db"""
    u = sanitize_name(username)
    s = sanitize_name(server_name)
    return f"whisker_client_{u}_{s}.db"


def read_client_db_info(db_path: str):
    """Inspects a SQLite client database file to extract stored client & server metadata."""
    if not os.path.exists(db_path):
        return None
    try:
        conn = sqlite3.connect(db_path)
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        # Check for config table
        cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='config'")
        if not cur.fetchone():
            conn.close()
            return None

        cur.execute("SELECT key, value FROM config")
        cfg = {r['key']: r['value'] for r in cur.fetchall()}

        # Check for legacy connections table if config is incomplete
        cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='connections'")
        conn_row = None
        if cur.fetchone():
            cur.execute("SELECT * FROM connections ORDER BY is_active DESC, id ASC LIMIT 1")
            conn_row = cur.fetchone()

        conn.close()

        username = cfg.get('username') or cfg.get('name') or (conn_row['name'] if conn_row else None)
        server_name = cfg.get('server_name') or cfg.get('servername')
        server_url = cfg.get('server_url') or (conn_row['server_url'] if conn_row else None)
        uuid = cfg.get('uuid') or (conn_row['uuid'] if conn_row else None)
        token = cfg.get('token') or (conn_row['token'] if conn_row else None)
        host_uuid = cfg.get('host_uuid') or (conn_row['host_uuid'] if conn_row else None)
        host_name = cfg.get('host_name') or (conn_row['host_name'] if conn_row else None)

        # Fallback to parsing filename if server_name is missing: whisker_client_<user>_<server>.db
        basename = os.path.basename(db_path)
        if (not server_name or not username) and basename.startswith('whisker_client_') and basename.endswith('.db'):
            inner = basename[len('whisker_client_'):-3]
            parts = inner.split('_', 1)
            if len(parts) == 2:
                if not username:
                    username = parts[0]
                if not server_name:
                    server_name = parts[1]
            elif len(parts) == 1:
                if not username:
                    username = parts[0]
                if not server_name:
                    server_name = 'server'

        return {
            'file': basename,
            'filepath': db_path,
            'username': username or 'default',
            'server_name': server_name or 'server',
            'server_url': server_url or '',
            'uuid': uuid or '',
            'token': token or '',
            'host_uuid': host_uuid or '',
            'host_name': host_name or 'Primary Device'
        }
    except Exception:
        return None


def get_all_client_dbs(directory='.'):
    """Finds all client database files (*.db) in the given directory."""
    results = []
    try:
        files = sorted(os.listdir(directory))
    except Exception:
        return []

    for f in files:
        if f.endswith('-journal') or f.endswith('-shm') or f.endswith('-wal'):
            continue
        if (f.startswith('whisker_client_') and f.endswith('.db')) or f == 'whisker_client.db':
            full_path = os.path.join(directory, f)
            info = read_client_db_info(full_path)
            if info:
                results.append(info)
    return results


def _print_client_db_table(dbs):
    print(f"{'USERNAME':<16} {'SERVER NAME':<18} {'DATABASE FILE':<38} {'SERVER URL'}")
    print("-" * 96)
    for d in dbs:
        u = d.get('username') or 'N/A'
        s = d.get('server_name') or 'N/A'
        f = d.get('file') or ''
        url = d.get('server_url') or ''
        print(f"{u:<16} {s:<18} {f:<38} {url}")


def resolve_client_db(username=None, server_name=None, custom_db=None):
    """
    Resolves the target client database adhering to the following rules:
    - If custom_db is provided, use that database file directly.
    - If there is a single client db file in directory, use that.
    - If there is a single username registered on multiple servers, throw an error if server_name is not given (defaulting to the single known username).
    - If there are multiple usernames on a single home server, throw an error if username is not given (defaulting to the single known home server).
    - If both username and server_name are provided, select that exact matching pair.
    - Otherwise, report ambiguity with available choices.
    """
    if custom_db:
        if not os.path.exists(custom_db):
            print(f"Error: Client database file '{custom_db}' does not exist.")
            sys.exit(1)
        info = read_client_db_info(custom_db)
        if not info:
            print(f"Error: Unable to read valid client configuration from '{custom_db}'.")
            sys.exit(1)
        return info

    # Environment variable fallbacks
    env_user = os.environ.get('WHISKER_USER') or os.environ.get('WHISKER_USERNAME') or os.environ.get('WHISKER_CLIENT')
    env_server = os.environ.get('WHISKER_SERVER') or os.environ.get('WHISKER_SERVER_NAME')
    env_db = os.environ.get('WHISKER_DB')

    if not username and env_user:
        username = env_user
    if not server_name and env_server:
        server_name = env_server
    if env_db and not username and not server_name:
        return resolve_client_db(custom_db=env_db)

    dbs = get_all_client_dbs('.')
    if len(dbs) == 0:
        print("Error: No client databases found in current directory.")
        print("Run 'whisker init <invite_url> [--name <username>]' to pair with a home server.")
        sys.exit(1)

    # Rule: If there is a single client db file, use that!
    if len(dbs) == 1:
        single = dbs[0]
        if username:
            u_clean = sanitize_name(username)
            if sanitize_name(single['username']) != u_clean and username.lower() != single['username'].lower():
                print(f"Error: No client database found for username '{username}'.")
                print(f"Found 1 registered client: user '{single['username']}' on server '{single['server_name']}' ({single['file']}).")
                sys.exit(1)
        if server_name:
            s_clean = sanitize_name(server_name)
            if (sanitize_name(single['server_name']) != s_clean and 
                server_name.lower() != single['server_name'].lower() and 
                server_name.lower() not in single.get('server_url', '').lower()):
                print(f"Error: No client database found for server '{server_name}'.")
                print(f"Found 1 registered client: user '{single['username']}' on server '{single['server_name']}' ({single['file']}).")
                sys.exit(1)
        return single

    # Helper matching routines
    def matches_user(db_entry, u):
        if not u:
            return True
        u_str = str(u).strip().lower()
        return (db_entry['username'].lower() == u_str or 
                sanitize_name(db_entry['username']) == sanitize_name(u_str))

    def matches_server(db_entry, s):
        if not s:
            return True
        s_str = str(s).strip().lower()
        return (db_entry['server_name'].lower() == s_str or 
                sanitize_name(db_entry['server_name']) == sanitize_name(s_str) or
                s_str in db_entry.get('server_url', '').lower())

    # Case 1: Both username and server_name provided
    if username and server_name:
        matches = [d for d in dbs if matches_user(d, username) and matches_server(d, server_name)]
        if len(matches) >= 1:
            return matches[0]
        else:
            print(f"Error: No client database found matching user '{username}' on server '{server_name}'.")
            print("Available client databases:")
            _print_client_db_table(dbs)
            sys.exit(1)

    # Case 2: username provided, server_name NOT provided
    if username and not server_name:
        user_matches = [d for d in dbs if matches_user(d, username)]
        if len(user_matches) == 0:
            print(f"Error: No client database found for user '{username}'.")
            print("Available client databases:")
            _print_client_db_table(dbs)
            sys.exit(1)
        elif len(user_matches) == 1:
            return user_matches[0]
        else:
            # Single username registered on multiple servers -> error because server_name is not given
            servers_list = ", ".join(f"'{m['server_name']}'" for m in user_matches)
            print(f"Error: Ambiguous home server. User '{username}' is registered on multiple servers: {servers_list}.")
            print(f"Please specify --server <server_name> (e.g. -u '{username}' -s <server_name>).")
            sys.exit(1)

    # Case 3: server_name provided, username NOT provided
    if server_name and not username:
        server_matches = [d for d in dbs if matches_server(d, server_name)]
        if len(server_matches) == 0:
            print(f"Error: No client database found for server '{server_name}'.")
            print("Available client databases:")
            _print_client_db_table(dbs)
            sys.exit(1)
        elif len(server_matches) == 1:
            return server_matches[0]
        else:
            # Multiple usernames on a single home server -> error because username is not given
            users_list = ", ".join(f"'{m['username']}'" for m in server_matches)
            print(f"Error: Ambiguous client username. Multiple users registered on server '{server_name}': {users_list}.")
            print(f"Please specify --username <username> (e.g. -u <username> -s '{server_name}').")
            sys.exit(1)

    # Case 4: NEITHER username NOR server_name provided (len(dbs) > 1)
    distinct_users = sorted(list(set(d['username'] for d in dbs if d.get('username'))))
    distinct_servers = sorted(list(set(d['server_name'] for d in dbs if d.get('server_name'))))

    # Subcase 4a: Single username registered on multiple servers
    if len(distinct_users) == 1:
        only_user = distinct_users[0]
        servers_list = ", ".join(f"'{s}'" for s in distinct_servers)
        print(f"Error: Ambiguous home server. User '{only_user}' is registered on multiple servers: {servers_list}.")
        print(f"Please specify --server <server_name> (e.g. -s <server_name>).")
        sys.exit(1)

    # Subcase 4b: Multiple usernames on a single home server
    if len(distinct_servers) == 1:
        only_server = distinct_servers[0]
        users_list = ", ".join(f"'{u}'" for u in distinct_users)
        print(f"Error: Ambiguous client username. Multiple users registered on server '{only_server}': {users_list}.")
        print(f"Please specify --username <username> (e.g. -u <username>).")
        sys.exit(1)

    # Subcase 4c: Multiple users AND multiple servers
    print(f"Error: Multiple client databases found ({len(dbs)}). Please specify --username <username> and/or --server <server_name>.")
    print("Available client databases:")
    _print_client_db_table(dbs)
    sys.exit(1)


def save_client_credentials(db_path: str, client_data: dict):
    """Stores client credentials and server metadata into the dedicated client SQLite DB."""
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute('''
        CREATE TABLE IF NOT EXISTS config (
            key TEXT PRIMARY KEY,
            value TEXT
        )
    ''')
    for k, v in client_data.items():
        cur.execute('INSERT OR REPLACE INTO config (key, value) VALUES (?, ?)', (str(k), str(v) if v is not None else ''))
    conn.commit()
    conn.close()


def do_init(invite_url, username=None, server_name=None, custom_db=None):
    """Pairs with a Whisker host via an invite URL and provisions a dedicated client database."""
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
            if invite_url.startswith('http://') or invite_url.startswith('https://'):
                server_url = invite_url.rstrip('/')
                code = input("Enter invite code: ").strip()
            else:
                print("Invalid invite URL format. Expected: http://server:3000/invite/CODE or http://server:3000?code=CODE")
                sys.exit(1)

        req_payload = {"action": "claim_invite", "code": code, "client_type": "CLI"}
        if username and str(username).strip():
            req_payload["name"] = str(username).strip()

        req_data = json.dumps(req_payload).encode('utf-8')
        req = urllib.request.Request(f"{server_url}/api/public", data=req_data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if 'error' in res_data:
                print(f"Error: {res_data['error']}")
                sys.exit(1)

            uuid = res_data.get('uuid')
            token = res_data.get('token')
            final_user = (username or '').strip() or res_data.get('name') or 'CLI Client'
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
                print("Invalid response from server (missing uuid or token).")
                sys.exit(1)

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

            print(f"\n✓ Successfully paired with '{host_name}'!")
            print(f"  Client Username: {final_user}")
            print(f"  Home Server:     {final_server} ({server_url})")
            print(f"  Database File:   {chosen_db}")
            print(f"  Client UUID:     {uuid}")
            print(f"  Paired Host:     {host_name} ({host_uuid or 'global'})\n")
            print("Commands:")
            print("  whisker send <message>                     Send a message to paired host")
            print("  whisker send -u <user> -s <srv> <msg>      Send message with specific user/server")
            print("  whisker call                               Initiate audio/video call to host")
            print("  whisker listen                             Listen for live events & auto-answer calls")
            print("  whisker info                               Show client info and home server status")
            print("  whisker clients                            List all local client database files")

    except Exception as e:
        print(f"Failed to initialize: {e}")
        sys.exit(1)


def do_list_clients():
    """Lists all local client databases in the current directory."""
    dbs = get_all_client_dbs('.')
    if not dbs:
        print("No local client databases found in current directory.")
        print("Run 'whisker init <invite_url> [--name <username>]' to connect to a host.")
        return

    print("\n--- Local Whisker Client Databases ---")
    _print_client_db_table(dbs)
    print("\nTip: Pass '-u <username>' and/or '-s <server_name>' to target a specific profile.")
    print("     If there is only one client database, parameters are optional.\n")


def do_remove_client(username=None, server_name=None, custom_db=None):
    """Deletes a local client database file."""
    target = resolve_client_db(username=username, server_name=server_name, custom_db=custom_db)
    db_file = target['filepath'] if 'filepath' in target else target['file']
    if os.path.exists(db_file):
        try:
            os.remove(db_file)
            print(f"✓ Removed client database '{db_file}' (User: '{target['username']}', Server: '{target['server_name']}').")
        except Exception as e:
            print(f"Failed to remove database '{db_file}': {e}")
            sys.exit(1)
    else:
        print(f"Database file '{db_file}' not found.")


def do_send(msg, contact=None, username=None, server_name=None, custom_db=None):
    """Sends a chat message to the host through the home server."""
    target = resolve_client_db(username=username, server_name=server_name, custom_db=custom_db)

    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    client_display_name = target['username']
    host_name = target.get('host_name') or 'Host'
    host_uuid = contact or target.get('host_uuid') or ''

    try:
        payload = {
            "action": "message",
            "uuid": uuid,
            "token": token,
            "text": msg,
            "name": client_display_name
        }
        if host_uuid:
            payload["contact"] = host_uuid
            payload["host_uuid"] = host_uuid

        req_data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(f"{server_url}/api/host", data=req_data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if 'error' in res_data:
                print(f"Error: {res_data['error']}")
            else:
                updated_hname = res_data.get('host_name')
                if updated_hname and updated_hname != host_name:
                    host_name = updated_hname
                    # Update host_name in DB
                    db_p = target.get('filepath') or target.get('file')
                    if db_p and os.path.exists(db_p):
                        save_client_credentials(db_p, {'host_name': host_name})
                print(f"[{host_name}] Message sent as '{client_display_name}' ({target['server_name']}).")
    except Exception as e:
        print(f"Failed to send message to {host_name}: {e}")
        sys.exit(1)


def do_send_file(file_path, contact=None, username=None, server_name=None, custom_db=None):
    """Sends a local file to the host/contact through the home server."""
    if not os.path.isfile(file_path):
        print(f"Error: File '{file_path}' does not exist.")
        sys.exit(1)

    target = resolve_client_db(username=username, server_name=server_name, custom_db=custom_db)

    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    client_display_name = target['username']
    host_name = target.get('host_name') or 'Host'
    host_uuid = contact or target.get('host_uuid') or ''

    filename = sanitize_filename(os.path.basename(file_path))
    file_size = os.path.getsize(file_path)

    try:
        import base64
        with open(file_path, 'rb') as f:
            file_bytes = f.read()

        b64_content = base64.b64encode(file_bytes).decode('utf-8')
        payload = {
            "action": "file_upload",
            "uuid": uuid,
            "token": token,
            "filename": filename,
            "content": b64_content,
            "name": client_display_name,
            "text": filename
        }
        if host_uuid:
            payload["contact"] = host_uuid
            payload["receiver"] = host_uuid

        req_data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(
            f"{server_url}/api/host",
            data=req_data,
            headers={'Content-Type': 'application/json'}
        )
        print(f"Uploading '{filename}' ({file_size} bytes) to {host_name} via {target['server_name']}...")
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if 'error' in res_data:
                print(f"Error: {res_data['error']}")
            else:
                download_url = res_data.get('url') or res_data.get('download_url') or ''
                print(f"✓ File '{filename}' successfully sent to '{host_name}' via '{target['server_name']}'.")
                if download_url:
                    print(f"  Download URL: {server_url}{download_url}")
    except Exception as e:
        print(f"Failed to send file to {host_name}: {e}")
        sys.exit(1)


def do_call(contact=None, username=None, server_name=None, video_source=None, audio_source=None, save_streams=False, custom_db=None):
    """Initiates an audio/video WebRTC call to the host."""
    target = resolve_client_db(username=username, server_name=server_name, custom_db=custom_db)

    if WhiskerWebRTCSession is None:
        print("Error: WebRTC calling requires aiortc, pillow, and av. Please install with: pip install aiortc pillow av")
        sys.exit(1)

    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    name = target['username']
    host_name = target.get('host_name') or 'Host'
    host_uuid = contact or target.get('host_uuid') or ''

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
        sse_payload = json.dumps({'action': 'events', 'uuid': uuid, 'token': token}).encode('utf-8')
        url = f"{server_url}/api/host"
        while not stop_event.is_set():
            try:
                req = urllib.request.Request(
                    url,
                    data=sse_payload,
                    headers={'Content-Type': 'application/json', 'Accept': 'text/event-stream'}
                )
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

                                ev_ts = inner_payload.get('timestamp') or raw_data.get('timestamp')
                                if ev_ts and (ev_ts / 1000.0) < (call_start_timestamp - 2.0):
                                    continue

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
        s_payload = json.dumps({'action': 'call_status', 'call_id': session.call_id, 'uuid': uuid}).encode('utf-8')
        s_url = f"{server_url}/api/host"
        while not stop_event.is_set():
            time.sleep(1.0)
            if stop_event.is_set() or not session.is_active:
                break
            try:
                s_req = urllib.request.Request(
                    s_url,
                    data=s_payload,
                    headers={'Content-Type': 'application/json'}
                )
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
        print(f"[{host_name} Call] Calling in progress... (Press Ctrl+C to end call)")
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


def _listen_stream_worker(target, label, video_source=None, audio_source=None, save_streams=False, storage_dir=None):
    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    host_name = target.get('host_name') or label
    name = target.get('username') or 'Whisker CLI'

    listen_payload = json.dumps({'action': 'events', 'uuid': uuid, 'token': token}).encode('utf-8')
    url = f"{server_url}/api/host"

    active_call_session = None
    call_loop = None
    processed_event_keys = set()

    while True:
        try:
            req = urllib.request.Request(
                url,
                data=listen_payload,
                headers={'Content-Type': 'application/json', 'Accept': 'text/event-stream'}
            )
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

                            # Deduplicate stream events
                            evt_key = inner_payload.get('eventId') or inner_payload.get('messageId') or raw_data.get('eventId') or raw_data.get('messageId')
                            if not evt_key:
                                file_target_tmp = inner_payload.get('filePath') or inner_payload.get('file') or raw_data.get('filePath') or raw_data.get('file')
                                if file_target_tmp:
                                    evt_key = file_target_tmp
                            if evt_key:
                                if evt_key in processed_event_keys:
                                    continue
                                processed_event_keys.add(evt_key)
                                if len(processed_event_keys) > 2000:
                                    processed_event_keys.clear()

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
                                sender_val = inner_payload.get('name') or inner_payload.get('sender') or raw_data.get('name') or raw_data.get('sender') or 'sender'
                                file_target = inner_payload.get('filePath') or inner_payload.get('file') or raw_data.get('filePath') or raw_data.get('file')
                                file_name = inner_payload.get('fileName') or raw_data.get('fileName') or inner_payload.get('text') or 'attachment.bin'
                                if file_target:
                                    rec_user = sanitize_name(target.get('username') or 'client')
                                    snd_user = sanitize_name(sender_val)
                                    now_folder = time.strftime('%Y%m%d.%H%M%S')
                                    base_dir = storage_dir or './downloads'
                                    effective_dir = os.path.join(base_dir, rec_user, snd_user, now_folder)
                                    os.makedirs(effective_dir, exist_ok=True)
                                    safe_name = sanitize_filename(file_name)
                                    out_path = os.path.join(effective_dir, safe_name)
                                    try:
                                        if file_target.startswith('data:'):
                                            import base64
                                            b64_part = file_target.split(',', 1)[1] if ',' in file_target else file_target
                                            data_bytes = base64.b64decode(b64_part)
                                            with open(out_path, 'wb') as f_out:
                                                f_out.write(data_bytes)
                                            print(f"\n📥 [{label} File Received] Saved '{safe_name}' ({len(data_bytes)} bytes) to '{out_path}'")
                                        else:
                                            if file_target.startswith('http://') or file_target.startswith('https://'):
                                                parsed_u = urllib.parse.urlparse(file_target)
                                                clean_path = urllib.parse.quote(urllib.parse.unquote(parsed_u.path), safe='/')
                                                clean_query = urllib.parse.quote(urllib.parse.unquote(parsed_u.query), safe='=&/?%')
                                                dl_url = f"{parsed_u.scheme}://{parsed_u.netloc}{clean_path}" + (f"?{clean_query}" if clean_query else "")
                                            else:
                                                clean_target = file_target if file_target.startswith('/') else f"/{file_target}"
                                                if '?' in clean_target:
                                                    p_part, q_part = clean_target.split('?', 1)
                                                    clean_p = urllib.parse.quote(urllib.parse.unquote(p_part), safe='/')
                                                    clean_q = urllib.parse.quote(urllib.parse.unquote(q_part), safe='=&/?%')
                                                    dl_url = f"{server_url}{clean_p}?{clean_q}"
                                                else:
                                                    clean_p = urllib.parse.quote(urllib.parse.unquote(clean_target), safe='/')
                                                    dl_url = f"{server_url}{clean_p}"

                                            dl_payload = json.dumps({'action': 'file_download', 'path': file_target, 'name': safe_name}).encode('utf-8')
                                            dl_req = urllib.request.Request(
                                                f"{server_url}/api/host",
                                                data=dl_payload,
                                                headers={'Content-Type': 'application/json'}
                                            )
                                            with urllib.request.urlopen(dl_req) as dl_resp:
                                                data_bytes = dl_resp.read()
                                            with open(out_path, 'wb') as f_out:
                                                f_out.write(data_bytes)
                                            print(f"\n📥 [{label} File Received] Downloaded '{safe_name}' ({len(data_bytes)} bytes) to '{out_path}'")
                                    except Exception as dl_err:
                                        print(f"\n⚠️ [{label}] Error saving received file '{safe_name}': {dl_err}")
                                elif sender_val == 'phone' or sender_val != target.get('uuid'):
                                    print(f"\n[{label}] {inner_payload.get('text') or raw_data.get('text')}")
                            elif event_type == 'call_accepted':
                                print(f"\n[{label} Call] Host accepted the call.")
                            elif event_type == 'call_rejected':
                                print(f"\n[{label} Call] Host rejected the call.")
                            elif is_incoming_call:
                                caller_name = inner_payload.get('name') or raw_data.get('name') or host_name
                                caller_uuid = inner_payload.get('host_uuid') or raw_data.get('host_uuid') or target.get('host_uuid') or ''
                                is_video = inner_payload.get('video') is not False and raw_data.get('video') is not False
                                incoming_call_id = inner_payload.get('call_id') or inner_payload.get('callId') or raw_data.get('call_id') or raw_data.get('callId')

                                if WhiskerWebRTCSession is None:
                                    print(f"\n📞 [{caller_name} Call] Incoming Call received. (Install aiortc, pillow, and av to enable real-time WebRTC media).")
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
                                    print(f"\n📴 [{caller_name if 'caller_name' in locals() else host_name} Call] Call ended by remote host.")
                                    if active_call_session and active_call_session.is_active:
                                        active_call_session.hangup()
                                        active_call_session = None
                            else:
                                print(f"\n[{label} Event] {raw_data}")
                        except json.JSONDecodeError:
                            print(f"\n[{label} Raw Event] {data_str}")
        except Exception:
            time.sleep(1.0)
        finally:
            if active_call_session and active_call_session.is_active:
                active_call_session.hangup()


def do_listen(contact=None, username=None, server_name=None, listen_all=False, video_source=None, audio_source=None, save_streams=False, custom_db=None, storage_dir=None):
    """Listens for SSE real-time events, automatically downloads received files/backups, and auto-answers incoming calls."""
    if listen_all:
        dbs = get_all_client_dbs('.')
        if not dbs:
            print("No client databases found.")
            sys.exit(1)
        print(f"Listening for events across all {len(dbs)} local client databases (Press Ctrl+C to stop)...")
        threads = []
        for d in dbs:
            lbl = f"{d['username']}@{d['server_name']}"
            t = threading.Thread(
                target=_listen_stream_worker,
                args=(d, lbl, video_source, audio_source, save_streams, storage_dir),
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

    target = resolve_client_db(username=username, server_name=server_name, custom_db=custom_db)
    if contact:
        target['host_uuid'] = contact
    label = f"{target['username']}@{target['server_name']}"
    save_info = f" [Auto-saving files to '{storage_dir or './downloads'}']"
    print(f"Listening for events as '{target['username']}' on '{target['server_name']}' ({target['server_url']}){save_info} (Press Ctrl+C to stop)...")
    try:
        _listen_stream_worker(target, label, video_source=video_source, audio_source=audio_source, save_streams=save_streams, storage_dir=storage_dir)
    except KeyboardInterrupt:
        print("\nStopped listening.")


def do_info(contact=None, username=None, server_name=None, custom_db=None):
    """Displays client configuration and queries the Home Server for status."""
    target = resolve_client_db(username=username, server_name=server_name, custom_db=custom_db)

    server_url = target['server_url']
    uuid = target['uuid']
    name = target['username']
    s_name = target['server_name']
    host_name = target.get('host_name') or 'Primary Device'
    host_uuid = contact or target.get('host_uuid') or 'N/A'

    print(f"--- Client Configuration ({target['file']}) ---")
    print(f"Client Username: {name}")
    print(f"Home Server:     {s_name} ({server_url})")
    print(f"Client UUID:     {uuid}")
    print(f"Paired Host:     {host_name} ({host_uuid})")
    print(f"Database File:   {target['file']}")

    try:
        req = urllib.request.Request(f"{server_url}/api/public")
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


def get_cmd_prog(subcmd: str = None) -> str:
    """Derives a clean program name (e.g. 'whisker' or 'python -m whisker') and attaches subcommand."""
    argv0 = sys.argv[0] if sys.argv and sys.argv[0] else ''
    if argv0.endswith('__main__.py') or argv0.endswith('cli.py'):
        base = "python -m whisker"
    elif os.path.basename(argv0):
        base = os.path.basename(argv0)
    else:
        base = "whisker"
    return f"{base} {subcmd}" if subcmd else base


def print_help():
    base = get_cmd_prog()
    print(f"""Whisker Unified CLI & Home Server Package

Usage:
  {base} server --name <NAME> [--port PORT] [--db DB_FILE] [--admin-password PWD]
      Start Whisker Home Server with optional admin console password

  {base} init <invite_url> [--name USERNAME] [--server SERVER_NAME] [--db DB_FILE]
      Pair with a host via invite URL (creates whisker_client_<user>_<server>.db)

  {base} clients
      List local client databases in the current directory

  {base} send [-u USER] [-s SERVER] [-c CONTACT] [--db DB_FILE] <message>
      Send message to paired host

  {base} send-file [-u USER] [-s SERVER] [-c CONTACT] [--db DB_FILE] <file_path>
      Upload and send file or photo attachment to paired host

  {base} call [-u USER] [-s SERVER] [-c CONTACT] [-v DEV] [-a DEV] [--save-streams]
      Initiate WebRTC call (saves to <caller>.<callee>.<unixepoch>.mp4 with --save-streams)

  {base} listen [-u USER] [-s SERVER] [-c CONTACT] [-v DEV] [-a DEV] [--save-streams] [--all] [--storage-dir DIR]
      Listen for SSE events, automatically download incoming files/photos, and auto-answer calls.
      Run with '-u backup' to act as an automated continuous backup vault.

  {base} info [-u USER] [-s SERVER] [-c CONTACT] [--db DB_FILE]
      Show client configuration and home server status

  {base} remove [-u USER] [-s SERVER] [--db DB_FILE]
      Delete a local client database file

  {base} setup-fcm
      Run Firebase / FCM OAuth2 setup tool

  {base} mcp [--directory DIR]
      Start Whisker Model Context Protocol (MCP) Server for AI agents

  {base} help
      Show this help menu
""")


def main():
    if len(sys.argv) < 2:
        print_help()
        sys.exit(1)

    cmd = sys.argv[1]

    if cmd in ['server', 'start']:
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Start Whisker Home Server")
        parser.add_argument('--name', '-n', '--server-name', '--servername', dest='server_name', type=str, default=os.environ.get('SERVER_NAME', None), help="Obligatory name of the Home Server")
        parser.add_argument('--port', '-p', type=int, default=int(os.environ.get('PORT', 3000)), help="Port to listen on (default: 3000)")
        parser.add_argument('--db', '-d', type=str, default=os.environ.get('DB_FILE', None), help="Custom path to SQLite database file")
        parser.add_argument('--admin-password', '-a', '--password', dest='admin_password', type=str, default=os.environ.get('ADMIN_PASSWORD', None), help="Administrator password for web admin console")
        args = parser.parse_args(sys.argv[2:])

        if not args.server_name or not str(args.server_name).strip():
            print("Error: Server name is required to run Whisker Server.")
            print(f"Usage: {get_cmd_prog(cmd)} --name <server_name> [--port 3000] [--db <db_file>] [--admin-password <pwd>]")
            print(f"Example: {get_cmd_prog(cmd)} --name 'Home Base' --port 3000")
            sys.exit(1)

        run_server(port=args.port, db_path=args.db, server_name=args.server_name, admin_password=args.admin_password)

    elif cmd == 'init':
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Initialize / pair CLI client via invite URL")
        parser.add_argument('invite_url', help="Invite URL from Whisker host (e.g. http://server:3000/invite/CODE)")
        parser.add_argument('--name', '-n', '--user', '-u', '--username', '-C', '--client', dest='username', type=str, default=os.environ.get('WHISKER_USER', None), help="Client username / profile identity (e.g. 'alice')")
        parser.add_argument('--server', '-s', '--server-name', '--servername', dest='server_name', type=str, default=os.environ.get('WHISKER_SERVER', None), help="Home Server name (defaults to server's registered name)")
        parser.add_argument('--db', '-d', dest='db_file', type=str, default=os.environ.get('WHISKER_DB', None), help="Custom database file for this client")
        args = parser.parse_args(sys.argv[2:])
        do_init(args.invite_url, username=args.username, server_name=args.server_name, custom_db=args.db_file)

    elif cmd in ['clients', 'client', 'list-clients', 'dbs']:
        if len(sys.argv) > 2 and sys.argv[2] in ['switch', 'use', 'select']:
            print("Note: Active client switching via file is no longer needed.")
            print("Whisker separates databases per (username, server) as 'whisker_client_<user>_<server>.db'.")
            print("If you have a single client database it is used automatically. Otherwise specify -u <user> and/or -s <server>.")
            do_list_clients()
        else:
            parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="List local client databases in the current directory")
            parser.parse_args(sys.argv[2:])
            do_list_clients()

    elif cmd in ['connections', 'contacts', 'conns']:
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="List local client databases (per-server separation)")
        parser.parse_args(sys.argv[2:])
        print("Note: Each client-server pairing is now maintained in its own database file (whisker_client_<user>_<server>.db).")
        do_list_clients()

    elif cmd in ['switch', 'use', 'select']:
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Switch connection (legacy command)")
        parser.add_argument('target', nargs='?', default=None, help="Connection target or identifier")
        parser.parse_args(sys.argv[2:])
        print("Note: Active host switching within a database is no longer needed.")
        print("Whisker maintains dedicated per-server client databases. Use '-u <username>' and '-s <server_name>' on operations.")
        do_list_clients()

    elif cmd in ['remove', 'delete', 'disconnect']:
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Remove / delete local client database")
        parser.add_argument('-u', '--user', '--username', '-C', '--client', dest='username', type=str, default=None, help="Client username")
        parser.add_argument('-s', '--server', '--server-name', '--servername', dest='server_name', type=str, default=None, help="Home server name")
        parser.add_argument('--db', '-d', dest='db', type=str, default=None, help="Custom client database file")
        args = parser.parse_args(sys.argv[2:])
        do_remove_client(username=args.username, server_name=args.server_name, custom_db=args.db)

    elif cmd == 'send':
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Send message to Home Server host")
        parser.add_argument('-u', '--user', '--username', '-C', '--client', dest='username', type=str, default=None, help="Client username to send as")
        parser.add_argument('-s', '--server', '--server-name', '--servername', dest='server_name', type=str, default=None, help="Home server name to connect to")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host contact UUID or name")
        parser.add_argument('--db', '-d', type=str, default=None, help="Custom client database file")
        parser.add_argument('message', nargs='+', help="Message text to send")
        args = parser.parse_args(sys.argv[2:])
        do_send(" ".join(args.message), contact=args.contact, username=args.username, server_name=args.server_name, custom_db=args.db)

    elif cmd in ['send-file', 'sendfile']:
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Upload and send file or photo to Home Server host")
        parser.add_argument('-u', '--user', '--username', '-C', '--client', dest='username', type=str, default=None, help="Client username to send as")
        parser.add_argument('-s', '--server', '--server-name', '--servername', dest='server_name', type=str, default=None, help="Home server name to connect to")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host contact UUID or name")
        parser.add_argument('--db', '-d', type=str, default=None, help="Custom client database file")
        parser.add_argument('file_path', help="Local file path to upload and transmit")
        args = parser.parse_args(sys.argv[2:])
        do_send_file(args.file_path, contact=args.contact, username=args.username, server_name=args.server_name, custom_db=args.db)

    elif cmd == 'call':
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Initiate WebRTC call to Home Server host")
        parser.add_argument('-u', '--user', '--username', '-C', '--client', dest='username', type=str, default=None, help="Client username to call as")
        parser.add_argument('-s', '--server', '--server-name', '--servername', dest='server_name', type=str, default=None, help="Home server name to connect to")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host contact UUID or name")
        parser.add_argument('-v', '--video', type=str, default=None, help="Video source (e.g. /dev/video0 or video file)")
        parser.add_argument('-a', '--audio', type=str, default=None, help="Audio source (e.g. default, pulse, or audio file)")
        parser.add_argument('--save-streams', action='store_true', help="Save incoming and outgoing streams to MP4 file")
        parser.add_argument('--db', '-d', type=str, default=None, help="Custom client database file")
        args = parser.parse_args(sys.argv[2:])
        do_call(contact=args.contact, username=args.username, server_name=args.server_name, video_source=args.video, audio_source=args.audio, save_streams=args.save_streams, custom_db=args.db)

    elif cmd == 'listen':
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Listen for SSE events and auto-download incoming files")
        parser.add_argument('-u', '--user', '--username', '-C', '--client', dest='username', type=str, default=None, help="Client username to listen as")
        parser.add_argument('-s', '--server', '--server-name', '--servername', dest='server_name', type=str, default=None, help="Home server name to connect to")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host contact UUID or name")
        parser.add_argument('-v', '--video', type=str, default=None, help="Video source to stream if a call arrives")
        parser.add_argument('-a', '--audio', type=str, default=None, help="Audio source to stream if a call arrives")
        parser.add_argument('--save-streams', action='store_true', help="Save incoming and outgoing streams to MP4 file")
        parser.add_argument('--all', action='store_true', help="Listen to events across all local client databases simultaneously")
        parser.add_argument('--storage-dir', '--backup-dir', '--output-dir', '-o', dest='storage_dir', type=str, default=None, help="Directory to save received files (defaults to ./downloads)")
        parser.add_argument('--db', '-d', type=str, default=None, help="Custom client database file")
        args = parser.parse_args(sys.argv[2:])
        do_listen(contact=args.contact, username=args.username, server_name=args.server_name, listen_all=args.all, video_source=args.video, audio_source=args.audio, save_streams=args.save_streams, custom_db=args.db, storage_dir=args.storage_dir)

    elif cmd == 'info':
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Show client info and server status")
        parser.add_argument('-u', '--user', '--username', '-C', '--client', dest='username', type=str, default=None, help="Client username to inspect")
        parser.add_argument('-s', '--server', '--server-name', '--servername', dest='server_name', type=str, default=None, help="Home server name to inspect")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host contact UUID or name")
        parser.add_argument('--db', '-d', type=str, default=None, help="Custom client database file")
        args = parser.parse_args(sys.argv[2:])
        do_info(contact=args.contact, username=args.username, server_name=args.server_name, custom_db=args.db)

    elif cmd in ['setup-fcm', 'fcm-setup']:
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Run Firebase / FCM OAuth2 setup tool")
        parser.parse_args(sys.argv[2:])
        try:
            from whisker.setup_fcm import main as run_setup_fcm
            run_setup_fcm()
        except ImportError as e:
            if 'requests' in str(e):
                print("Error: The FCM setup wizard requires the 'requests' library.")
                print("Please install it with: pip install requests")
                sys.exit(1)
            raise

    elif cmd in ['mcp', 'mcp-server']:
        parser = argparse.ArgumentParser(prog=get_cmd_prog(cmd), description="Start Whisker Model Context Protocol (MCP) Server")
        parser.add_argument('--directory', '-d', type=str, default='.', help="Working directory containing client databases")
        args = parser.parse_args(sys.argv[2:])
        from whisker.mcp import run_mcp_server
        run_mcp_server(directory=args.directory)

    elif cmd in ['-h', '--help', 'help']:
        print_help()

    else:
        print(f"Unknown command: {cmd}\n")
        print_help()
        sys.exit(1)


if __name__ == '__main__':
    main()
