import sqlite3
import threading
import time
import json
import os
import uuid as uuid_lib

class DatabaseManager:
    def __init__(self, db_path="whisker_server.db"):
        self.db_path = db_path
        self.lock = threading.RLock()
        self.init_db()

    def get_connection(self):
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def init_db(self):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS requests (
                    uuid TEXT PRIMARY KEY,
                    name TEXT,
                    reason TEXT,
                    status TEXT DEFAULT 'PENDING',
                    token TEXT,
                    created_at INTEGER
                )
                """)
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    client_uuid TEXT,
                    host_uuid TEXT,
                    sender TEXT,
                    text TEXT,
                    file_path TEXT,
                    created_at INTEGER,
                    previous_message_id TEXT DEFAULT NULL
                )
                """)
                try:
                    cursor.execute("ALTER TABLE messages ADD COLUMN host_uuid TEXT DEFAULT NULL")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE messages ADD COLUMN previous_message_id TEXT DEFAULT NULL")
                except sqlite3.OperationalError:
                    pass
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS host_events (
                    id TEXT PRIMARY KEY,
                    type TEXT,
                    payload TEXT,
                    created_at INTEGER
                )
                """)
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS hosts (
                    uuid TEXT PRIMARY KEY,
                    username TEXT,
                    pin_token TEXT,
                    push_token TEXT,
                    is_admin INTEGER DEFAULT 0,
                    status TEXT DEFAULT 'PENDING',
                    created_at INTEGER
                )
                """)
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS client_host_approvals (
                    client_uuid TEXT,
                    host_uuid TEXT,
                    PRIMARY KEY (client_uuid, host_uuid)
                )
                """)
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
                """)
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS invites (
                    code TEXT PRIMARY KEY,
                    name TEXT,
                    used INTEGER DEFAULT 0,
                    created_at INTEGER
                )
                """)
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS client_presence (
                    uuid TEXT PRIMARY KEY,
                    is_online INTEGER DEFAULT 0,
                    last_seen INTEGER
                )
                """)
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS client_status_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT,
                    status TEXT,
                    timestamp INTEGER
                )
                """)
                cursor.execute("""
                CREATE TABLE IF NOT EXISTS guest_events (
                    id TEXT PRIMARY KEY,
                    client_uuid TEXT,
                    type TEXT,
                    payload TEXT,
                    created_at INTEGER
                )
                """)
                
                try:
                    cursor.execute("ALTER TABLE messages ADD COLUMN host_uuid TEXT DEFAULT NULL")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE host_events ADD COLUMN host_uuid TEXT DEFAULT NULL")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE guest_events ADD COLUMN host_uuid TEXT DEFAULT NULL")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE invites ADD COLUMN host_uuid TEXT DEFAULT NULL")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE requests ADD COLUMN client_type TEXT DEFAULT 'WEB'")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE requests ADD COLUMN server_address TEXT DEFAULT 'local'")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE hosts ADD COLUMN allow_direct_calls INTEGER DEFAULT 0")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE requests ADD COLUMN remote_uuid TEXT DEFAULT NULL")
                except sqlite3.OperationalError:
                    pass
                try:
                    cursor.execute("ALTER TABLE requests ADD COLUMN remote_token TEXT DEFAULT NULL")
                except sqlite3.OperationalError:
                    pass

                # Backfill unlinked approved requests with primary host
                try:
                    cursor.execute("SELECT uuid FROM hosts WHERE is_admin = 1 OR status = 'APPROVED' ORDER BY is_admin DESC, created_at ASC LIMIT 1")
                    primary_host = cursor.fetchone()
                    if primary_host:
                        cursor.execute("""
                            INSERT OR IGNORE INTO client_host_approvals (client_uuid, host_uuid)
                            SELECT r.uuid, ? FROM requests r
                            WHERE r.status = 'APPROVED'
                            AND NOT EXISTS (
                                SELECT 1 FROM client_host_approvals cha WHERE cha.client_uuid = r.uuid
                            )
                        """, (primary_host['uuid'],))
                except Exception:
                    pass

                conn.commit()
            finally:
                conn.close()

    def get_setting(self, key):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("SELECT value FROM settings WHERE key = ?", (key,))
                row = cursor.fetchone()
                return row['value'] if row else None
            finally:
                conn.close()

    def set_setting(self, key, value):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", (key, value))
                conn.commit()
            finally:
                conn.close()

    def add_request(self, uuid, name, reason, status='PENDING', token='', client_type='WEB', server_address='local', remote_uuid=None, remote_token=None):
        now = int(time.time() * 1000)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    """INSERT OR REPLACE INTO requests 
                       (uuid, name, reason, status, token, created_at, client_type, server_address, remote_uuid, remote_token) 
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (uuid, name, reason, status, token, now, client_type, server_address, remote_uuid, remote_token)
                )
                conn.commit()
            finally:
                conn.close()

    def approve_request(self, uuid, token):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("UPDATE requests SET status = 'APPROVED', token = ? WHERE uuid = ?", (token, uuid))
                conn.commit()
            finally:
                conn.close()

    def get_request(self, uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("SELECT * FROM requests WHERE uuid = ?", (uuid,))
                row = cursor.fetchone()
                return dict(row) if row else None
            finally:
                conn.close()

    def add_message(self, client_uuid, host_uuid, sender, text, file_path=None, msg_id=None):
        now = int(time.time() * 1000)
        msg_id = str(msg_id) if msg_id else str(uuid_lib.uuid4())
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT id FROM messages WHERE client_uuid = ? ORDER BY created_at DESC, rowid DESC LIMIT 1",
                    (client_uuid,)
                )
                prev_row = cursor.fetchone()
                prev_id = str(prev_row['id']) if (prev_row and prev_row['id'] is not None) else None

                cursor.execute(
                    "INSERT INTO messages (id, client_uuid, host_uuid, sender, text, file_path, created_at, previous_message_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (msg_id, client_uuid, host_uuid, sender, text, file_path, now, prev_id)
                )
                conn.commit()
                return msg_id, prev_id
            finally:
                conn.close()

    def get_messages(self, client_uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT id, client_uuid, sender, text, file_path, created_at, previous_message_id FROM messages WHERE client_uuid = ? ORDER BY created_at ASC",
                    (client_uuid,)
                )
                rows = cursor.fetchall()
                return [dict(r) for r in rows]
            finally:
                conn.close()

    def get_message_by_id(self, msg_id):
        if msg_id is None:
            return None
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT id, client_uuid, sender, text, file_path, created_at, previous_message_id FROM messages WHERE id = ? OR CAST(id AS TEXT) = ?",
                    (str(msg_id), str(msg_id))
                )
                row = cursor.fetchone()
                return dict(row) if row else None
            finally:
                conn.close()

    def add_host_event(self, host_uuid, event_id, event_type, payload_dict):
        now = int(time.time() * 1000)
        payload_str = json.dumps(payload_dict)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT INTO host_events (id, type, payload, created_at, host_uuid) VALUES (?, ?, ?, ?, ?)",
                    (event_id, event_type, payload_str, now, host_uuid)
                )
                conn.commit()
            finally:
                conn.close()

    def get_host_events(self, host_uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("SELECT * FROM host_events WHERE host_uuid = ? ORDER BY created_at ASC", (host_uuid,))
                rows = cursor.fetchall()
                result = []
                for r in rows:
                    item = dict(r)
                    try:
                        item['payload'] = json.loads(item['payload'])
                    except Exception:
                        pass
                    result.append(item)
                return result
            finally:
                conn.close()

    def delete_host_event(self, event_id):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("DELETE FROM host_events WHERE id = ?", (event_id,))
                conn.commit()
            finally:
                conn.close()

    def add_invite(self, code, name, host_uuid):
        now = int(time.time() * 1000)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT INTO invites (code, name, used, created_at, host_uuid) VALUES (?, ?, 0, ?, ?)",
                    (code, name, now, host_uuid)
                )
                conn.commit()
            finally:
                conn.close()

    def claim_invite(self, code):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("SELECT * FROM invites WHERE code = ?", (code,))
                row = cursor.fetchone()
                if not row:
                    return None, "Invite link not found or invalid."
                if row['used'] == 1:
                    return None, "This invite link has already been used once."
                cursor.execute("UPDATE invites SET used = 1 WHERE code = ?", (code,))
                conn.commit()
                return dict(row), None
            finally:
                conn.close()

    def get_invites(self, host_uuid=None):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                if host_uuid:
                    cursor.execute("SELECT * FROM invites WHERE host_uuid = ? ORDER BY created_at DESC", (host_uuid,))
                else:
                    cursor.execute("SELECT * FROM invites ORDER BY created_at DESC")
                rows = cursor.fetchall()
                return [dict(r) for r in rows]
            finally:
                conn.close()

    def delete_invite(self, code):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("DELETE FROM invites WHERE code = ?", (code,))
                conn.commit()
            finally:
                conn.close()

    def update_presence(self, uuid, is_online):
        now = int(time.time() * 1000)
        status_str = "online" if is_online else "offline"
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT OR REPLACE INTO client_presence (uuid, is_online, last_seen) VALUES (?, ?, ?)",
                    (uuid, 1 if is_online else 0, now)
                )
                cursor.execute(
                    "INSERT INTO client_status_logs (uuid, status, timestamp) VALUES (?, ?, ?)",
                    (uuid, status_str, now)
                )
                conn.commit()
            finally:
                conn.close()

    def get_clients(self):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("""
                    SELECT r.uuid, r.name, r.reason, r.status as req_status, r.token, r.created_at,
                           c.is_online, c.last_seen
                    FROM requests r
                    LEFT JOIN client_presence c ON r.uuid = c.uuid
                """)
                rows = cursor.fetchall()
                return [dict(r) for r in rows]
            finally:
                conn.close()

    def add_guest_event(self, host_uuid, event_id, client_uuid, event_type, payload_dict):
        now = int(time.time() * 1000)
        payload_str = json.dumps(payload_dict)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT INTO guest_events (id, client_uuid, type, payload, created_at, host_uuid) VALUES (?, ?, ?, ?, ?, ?)",
                    (event_id, client_uuid, event_type, payload_str, now, host_uuid)
                )
                conn.commit()
            finally:
                conn.close()

    def get_guest_events(self, client_uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT * FROM guest_events WHERE client_uuid = ? ORDER BY created_at ASC",
                    (client_uuid,)
                )
                rows = cursor.fetchall()
                events = [dict(r) for r in rows]
                return events
            finally:
                conn.close()

    def delete_guest_events(self, client_uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("DELETE FROM guest_events WHERE client_uuid = ?", (client_uuid,))
                conn.commit()
            finally:
                conn.close()

    # --- New Host Methods ---
    def add_host(self, uuid, username, pin_token, push_token, is_admin, status):
        now = int(time.time() * 1000)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT INTO hosts (uuid, username, pin_token, push_token, is_admin, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (uuid, username, pin_token, push_token, is_admin, status, now)
                )
                if is_admin == 1:
                    cursor.execute("""
                        INSERT OR IGNORE INTO client_host_approvals (client_uuid, host_uuid)
                        SELECT r.uuid, ? FROM requests r
                        WHERE r.status = 'APPROVED'
                    """, (uuid,))
                conn.commit()
            finally:
                conn.close()

    def update_host(self, uuid, **kwargs):
        if not kwargs: return
        set_clause = ", ".join([f"{k} = ?" for k in kwargs.keys()])
        values = list(kwargs.values())
        values.append(uuid)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(f"UPDATE hosts SET {set_clause} WHERE uuid = ?", values)
                conn.commit()
            finally:
                conn.close()

    def get_host(self, uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("SELECT * FROM hosts WHERE uuid = ?", (uuid,))
                row = cursor.fetchone()
                return dict(row) if row else None
            finally:
                conn.close()

    def get_hosts(self):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("SELECT * FROM hosts ORDER BY created_at ASC")
                return [dict(row) for row in cursor.fetchall()]
            finally:
                conn.close()

    def get_admin_host(self):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("SELECT * FROM hosts WHERE is_admin = 1 LIMIT 1")
                row = cursor.fetchone()
                return dict(row) if row else None
            finally:
                conn.close()
                
    def delete_host(self, uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("DELETE FROM hosts WHERE uuid = ?", (uuid,))
                conn.commit()
            finally:
                conn.close()

    # --- Client-Host Approvals ---
    def add_client_host_approval(self, client_uuid, host_uuid):
        if not client_uuid or not host_uuid: return
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT OR IGNORE INTO client_host_approvals (client_uuid, host_uuid) VALUES (?, ?)",
                    (client_uuid, host_uuid)
                )
                conn.commit()
            finally:
                conn.close()

    def get_approved_hosts_for_client(self, client_uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("""
                    SELECT h.* FROM hosts h
                    JOIN client_host_approvals c ON h.uuid = c.host_uuid
                    WHERE c.client_uuid = ? AND h.status = 'APPROVED'
                    ORDER BY h.is_admin DESC, h.created_at ASC
                """, (client_uuid,))
                rows = [dict(row) for row in cursor.fetchall()]
                if not rows:
                    # Check if client has an approved request in requests table
                    cursor.execute("SELECT * FROM requests WHERE uuid = ? AND status = 'APPROVED'", (client_uuid,))
                    req = cursor.fetchone()
                    if req:
                        cursor.execute("SELECT * FROM hosts WHERE status = 'APPROVED' ORDER BY is_admin DESC, created_at ASC LIMIT 1")
                        primary = cursor.fetchone()
                        if primary:
                            primary_dict = dict(primary)
                            cursor.execute("INSERT OR IGNORE INTO client_host_approvals (client_uuid, host_uuid) VALUES (?, ?)", (client_uuid, primary_dict['uuid']))
                            conn.commit()
                            return [primary_dict]
                return rows
            finally:
                conn.close()

    def get_approved_clients_for_host(self, host_uuid):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("""
                    SELECT r.uuid, r.name, r.reason, r.status as req_status, r.token, r.created_at,
                           r.client_type, r.server_address,
                           c.is_online, c.last_seen
                    FROM requests r
                    JOIN client_host_approvals cha ON r.uuid = cha.client_uuid
                    LEFT JOIN client_presence c ON r.uuid = c.uuid
                    WHERE cha.host_uuid = ?
                """, (host_uuid,))
                rows = [dict(row) for row in cursor.fetchall()]
                if not rows:
                    cursor.execute("SELECT is_admin FROM hosts WHERE uuid = ?", (host_uuid,))
                    h_row = cursor.fetchone()
                    if h_row and h_row['is_admin'] == 1:
                        cursor.execute("""
                            SELECT r.uuid, r.name, r.reason, r.status as req_status, r.token, r.created_at,
                                   r.client_type, r.server_address,
                                   c.is_online, c.last_seen
                            FROM requests r
                            LEFT JOIN client_presence c ON r.uuid = c.uuid
                            WHERE r.status = 'APPROVED'
                            AND NOT EXISTS (
                                SELECT 1 FROM client_host_approvals cha WHERE cha.client_uuid = r.uuid
                            )
                        """)
                        unlinked = [dict(r) for r in cursor.fetchall()]
                        for u in unlinked:
                            cursor.execute("INSERT OR IGNORE INTO client_host_approvals (client_uuid, host_uuid) VALUES (?, ?)", (u['uuid'], host_uuid))
                        if unlinked:
                            conn.commit()
                            rows.extend(unlinked)
                return rows
            finally:
                conn.close()
