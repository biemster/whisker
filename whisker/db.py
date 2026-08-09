import sqlite3
import threading
import time
import json
import os

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
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    client_uuid TEXT,
                    sender TEXT,
                    text TEXT,
                    file_path TEXT,
                    created_at INTEGER,
                    previous_message_id INTEGER DEFAULT NULL
                )
                """)
                try:
                    cursor.execute("ALTER TABLE messages ADD COLUMN previous_message_id INTEGER DEFAULT NULL")
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

    def add_request(self, uuid, name, reason):
        now = int(time.time() * 1000)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT OR REPLACE INTO requests (uuid, name, reason, status, created_at) VALUES (?, ?, ?, 'PENDING', ?)",
                    (uuid, name, reason, now)
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

    def add_message(self, client_uuid, sender, text, file_path=None):
        now = int(time.time() * 1000)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT id FROM messages WHERE client_uuid = ? ORDER BY id DESC LIMIT 1",
                    (client_uuid,)
                )
                prev_row = cursor.fetchone()
                prev_id = prev_row['id'] if prev_row else None

                cursor.execute(
                    "INSERT INTO messages (client_uuid, sender, text, file_path, created_at, previous_message_id) VALUES (?, ?, ?, ?, ?, ?)",
                    (client_uuid, sender, text, file_path, now, prev_id)
                )
                msg_id = cursor.lastrowid
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
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT id, client_uuid, sender, text, file_path, created_at, previous_message_id FROM messages WHERE id = ?",
                    (msg_id,)
                )
                row = cursor.fetchone()
                return dict(row) if row else None
            finally:
                conn.close()

    def add_host_event(self, event_id, event_type, payload_dict):
        now = int(time.time() * 1000)
        payload_str = json.dumps(payload_dict)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT INTO host_events (id, type, payload, created_at) VALUES (?, ?, ?, ?)",
                    (event_id, event_type, payload_str, now)
                )
                conn.commit()
            finally:
                conn.close()

    def get_host_events(self):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute("SELECT * FROM host_events ORDER BY created_at ASC")
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

    def add_invite(self, code, name):
        now = int(time.time() * 1000)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT INTO invites (code, name, used, created_at) VALUES (?, ?, 0, ?)",
                    (code, name, now)
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

    def get_invites(self):
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
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

    def add_guest_event(self, event_id, client_uuid, event_type, payload_dict):
        now = int(time.time() * 1000)
        payload_str = json.dumps(payload_dict)
        with self.lock:
            conn = self.get_connection()
            try:
                cursor = conn.cursor()
                cursor.execute(
                    "INSERT INTO guest_events (id, client_uuid, type, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                    (event_id, client_uuid, event_type, payload_str, now)
                )
                conn.commit()
            finally:
                conn.close()

    def pop_guest_events(self, client_uuid):
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
                if events:
                    cursor.execute("DELETE FROM guest_events WHERE client_uuid = ?", (client_uuid,))
                    conn.commit()
                return events
            finally:
                conn.close()
