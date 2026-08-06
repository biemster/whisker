#!/usr/bin/env python3
import sys
import json
import sqlite3
import urllib.request
import urllib.parse
import os

DB_FILE = 'whisker.db'

def init_db():
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS config (
            key TEXT PRIMARY KEY,
            value TEXT
        )
    ''')
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

def do_init(invite_url):
    try:
        parts = invite_url.split('/invite/')
        if len(parts) != 2:
            print("Invalid invite link format.")
            sys.exit(1)
        
        server_url = parts[0].rstrip('/')
        code = parts[1]
        
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
            
            if not uuid or not token:
                print("Invalid response from server (missing uuid or token).")
                sys.exit(1)
                
            conn = init_db()
            set_config(conn, 'server_url', server_url)
            set_config(conn, 'uuid', uuid)
            set_config(conn, 'token', token)
            set_config(conn, 'name', name)
            
            print(f"Initialized successfully as '{name}'.")
            print(f"UUID: {uuid}")
            print("You can now use 'send', 'call', and 'listen' commands.")
            
    except Exception as e:
        print(f"Failed to initialize: {e}")
        sys.exit(1)

def do_send(msg):
    if not os.path.exists(DB_FILE):
        print("Not initialized. Run 'whisker.py init <invite_url>' first.")
        sys.exit(1)
        
    conn = init_db()
    server_url = get_config(conn, 'server_url')
    uuid = get_config(conn, 'uuid')
    token = get_config(conn, 'token')
    
    if not server_url or not uuid or not token:
        print("Missing config. Please re-run init.")
        sys.exit(1)
        
    try:
        req_data = json.dumps({"uuid": uuid, "token": token, "text": msg}).encode('utf-8')
        req = urllib.request.Request(f"{server_url}/api/message", data=req_data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if 'error' in res_data:
                print(f"Error: {res_data['error']}")
            else:
                print("Message sent.")
    except Exception as e:
        print(f"Failed to send message: {e}")
        sys.exit(1)

def do_call():
    if not os.path.exists(DB_FILE):
        print("Not initialized. Run 'whisker.py init <invite_url>' first.")
        sys.exit(1)
        
    conn = init_db()
    server_url = get_config(conn, 'server_url')
    uuid = get_config(conn, 'uuid')
    token = get_config(conn, 'token')
    
    try:
        req_data = json.dumps({
            "uuid": uuid, 
            "token": token, 
            "video": False, 
            "sdp": "python-cli-dummy-sdp-replace-with-webrtc-later"
        }).encode('utf-8')
        req = urllib.request.Request(f"{server_url}/api/call", data=req_data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if 'error' in res_data:
                print(f"Error: {res_data['error']}")
            else:
                print("Call initiated (stub).")
    except Exception as e:
        print(f"Failed to initiate call: {e}")
        sys.exit(1)

def do_listen():
    if not os.path.exists(DB_FILE):
        print("Not initialized. Run 'whisker.py init <invite_url>' first.")
        sys.exit(1)
        
    conn = init_db()
    server_url = get_config(conn, 'server_url')
    uuid = get_config(conn, 'uuid')
    token = get_config(conn, 'token')
    
    query = urllib.parse.urlencode({'uuid': uuid, 'token': token})
    url = f"{server_url}/api/events?{query}"
    
    print(f"Listening for events from {server_url} ...")
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
                        event_type = data.get('event')
                        if event_type == 'chat_message':
                            print(f"\n[Message from host] {data.get('text')}")
                        elif event_type == 'call_accepted':
                            print("\n[Call] Host accepted the call.")
                        elif event_type == 'call_rejected':
                            print("\n[Call] Host rejected the call.")
                        elif event_type == 'webrtc_call_initiate':
                            print("\n[Call] Incoming call from Host!")
                        elif event_type == 'hangup':
                            print("\n[Call] Host hung up.")
                        elif event_type == 'ping':
                            pass # heartbeat
                        else:
                            print(f"\n[Event] {data}")
                    except json.JSONDecodeError:
                        print(f"\n[Raw Event] {data_str}")
    except KeyboardInterrupt:
        print("\nStopped listening.")
    except Exception as e:
        print(f"Listening error: {e}")
        sys.exit(1)

def do_info():
    if not os.path.exists(DB_FILE):
        print("Not initialized. Run 'whisker.py init <invite_url>' first.")
        sys.exit(1)
        
    conn = init_db()
    server_url = get_config(conn, 'server_url')
    uuid = get_config(conn, 'uuid')
    name = get_config(conn, 'name')
    
    print(f"--- Client Configuration ---")
    print(f"Name:       {name}")
    print(f"UUID:       {uuid}")
    print(f"Home Server: {server_url}")
    
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

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage:")
        print("  whisker.py init <invite_url>")
        print("  whisker.py send <message>")
        print("  whisker.py call")
        print("  whisker.py listen")
        print("  whisker.py info")
        sys.exit(1)
        
    cmd = sys.argv[1]
    
    if cmd == 'init':
        if len(sys.argv) < 3:
            print("Missing invite URL")
            sys.exit(1)
        do_init(sys.argv[2])
    elif cmd == 'send':
        if len(sys.argv) < 3:
            print("Missing message text")
            sys.exit(1)
        do_send(" ".join(sys.argv[2:]))
    elif cmd == 'call':
        do_call()
    elif cmd == 'listen':
        do_listen()
    elif cmd == 'info':
        do_info()
    else:
        print(f"Unknown command: {cmd}")
