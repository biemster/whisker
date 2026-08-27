import sys
import os
import json
import argparse
import sqlite3
import urllib.request
import urllib.parse
import threading
import time

if __package__ is None or __package__ == '':
    sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from whisker.server import run_server

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

def do_send(msg, contact=None):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    conn = init_cli_db()
    target = resolve_connection(conn, contact)

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

def do_call(contact=None):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    conn = init_cli_db()
    target = resolve_connection(conn, contact)

    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    host_name = target.get('host_name') or 'Host'
    host_uuid = target.get('host_uuid') or ''

    try:
        payload = {
            "uuid": uuid,
            "token": token,
            "video": False,
            "sdp": "python-cli-dummy-sdp-replace-with-webrtc-later"
        }
        if host_uuid:
            payload["contact"] = host_uuid
            payload["host_uuid"] = host_uuid

        req_data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(f"{server_url}/api/call", data=req_data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if 'error' in res_data:
                print(f"Error: {res_data['error']}")
            else:
                print(f"[{host_name}] Call initiated (stub).")
    except Exception as e:
        print(f"Failed to initiate call to {host_name}: {e}")
        sys.exit(1)

def _listen_stream_worker(target, label):
    server_url = target['server_url']
    uuid = target['uuid']
    token = target['token']
    host_name = target.get('host_name') or label

    query = urllib.parse.urlencode({'uuid': uuid, 'token': token})
    url = f"{server_url}/api/events?{query}"

    try:
        req = urllib.request.Request(url, headers={'Accept': 'text/event-stream'})
        with urllib.request.urlopen(req) as response:
            while True:
                line = response.readline()
                if not line:
                    break

                line_str = line.decode('utf-8').strip()
                if line_str.startswith('data:'):
                    data_str = line_str[5:].strip()
                    try:
                        data = json.loads(data_str)
                        event_type = data.get('event') or data.get('type')
                        if event_type in ['chat_message', 'message']:
                            if data.get('sender') == 'phone':
                                print(f"\n[{host_name}] {data.get('text')}")
                        elif event_type == 'call_accepted':
                            print(f"\n[{host_name} Call] Host accepted the call.")
                        elif event_type == 'call_rejected':
                            print(f"\n[{host_name} Call] Host rejected the call.")
                        elif event_type == 'webrtc_call_initiate':
                            print(f"\n[{host_name} Call] Incoming call from Host!")
                        elif event_type in ['hangup', 'call_ended']:
                            print(f"\n[{host_name} Call] Host hung up.")
                        else:
                            print(f"\n[{host_name} Event] {data}")
                    except json.JSONDecodeError:
                        print(f"\n[{host_name} Raw Event] {data_str}")
    except Exception as e:
        print(f"[{host_name}] Stream closed: {e}")

def do_listen(contact=None, listen_all=False):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    conn = init_cli_db()

    if listen_all:
        connections = get_all_connections(conn)
        if not connections:
            print("No connections found.")
            return

        print(f"Listening for events across all {len(connections)} paired connections (Press Ctrl+C to stop)...")
        threads = []
        for c in connections:
            t = threading.Thread(target=_listen_stream_worker, args=(c, c.get('host_name') or f"#{c['id']}"), daemon=True)
            t.start()
            threads.append(t)

        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\nStopped listening.")
            return

    target = resolve_connection(conn, contact)
    host_name = target.get('host_name') or 'Host'
    print(f"Listening for events from '{host_name}' ({target['server_url']}) (Press Ctrl+C to stop)...")

    try:
        _listen_stream_worker(target, host_name)
    except KeyboardInterrupt:
        print("\nStopped listening.")

def do_info(contact=None):
    if not os.path.exists(CLI_DB_FILE):
        print("Not initialized. Run 'whisker init <invite_url>' first.")
        sys.exit(1)

    conn = init_cli_db()
    target = resolve_connection(conn, contact)

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
  whisker server [--port PORT] [--db DB_FILE]      Start Whisker Home Server
  whisker init <invite_url>                        Initialize/pair with a host via invite URL
  whisker connections (or: list, contacts)         List all configured paired host connections
  whisker switch <id|name>                         Switch the active default host connection
  whisker remove <id|name>                         Remove/disconnect a paired host connection
  whisker send [-c CONTACT] <message>              Send message to active or targeted host
  whisker call [-c CONTACT]                        Initiate voice/video call to active or targeted host
  whisker listen [-c CONTACT] [--all]              Listen for realtime SSE events
  whisker info [-c CONTACT]                        Print connection & server status
  whisker setup-fcm                                Run Firebase / FCM OAuth2 setup tool
  whisker help                                     Show this help menu
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
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host by ID, name, or UUID")
        parser.add_argument('message', nargs='+', help="Message text to send")
        args = parser.parse_args(sys.argv[2:])
        do_send(" ".join(args.message), contact=args.contact)

    elif cmd == 'call':
        parser = argparse.ArgumentParser(description="Initiate call to Home Server host")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host by ID, name, or UUID")
        args = parser.parse_args(sys.argv[2:])
        do_call(contact=args.contact)

    elif cmd == 'listen':
        parser = argparse.ArgumentParser(description="Listen for SSE events from Home Server")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host by ID, name, or UUID")
        parser.add_argument('--all', action='store_true', help="Listen to events across all paired connections simultaneously")
        args = parser.parse_args(sys.argv[2:])
        do_listen(contact=args.contact, listen_all=args.all)

    elif cmd == 'info':
        parser = argparse.ArgumentParser(description="Show client info and server status")
        parser.add_argument('-c', '--contact', type=str, default=None, help="Target specific host by ID, name, or UUID")
        args = parser.parse_args(sys.argv[2:])
        do_info(contact=args.contact)

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
