/**
 * Whisker Lite - Home Server & Guest Companion Portal
 * -------------------------------------------------------------
 * This is a minimal, standalone Node.js server that manages SQLite-backed message
 * storage, registers calls, and routes low-overhead event notifications with a "tickle" architecture.
 *
 * Running:
 *   1. Node.js must be installed (uses standard 'sqlite3' or native 'better-sqlite3').
 *   2. Run `npm install sqlite3` (or it will auto-fallback to any available SQLite engine).
 *   3. Start with `node companion-server.js` or `PORT=8080 ACCESS_PIN=123456 node companion-server.js`.
 */

const http = require('http');
const https = require('https');
const url = require('url');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { exec } = require('child_process');

// FCM v1 SERVICE ACCOUNT OAUTH2 ENGINE
let cachedOAuthToken = null;
let oAuthTokenExpiry = 0;

function getServiceAccount() {
    // 1. Env var JSON
    if (process.env.SERVICE_ACCOUNT_JSON) {
        try { return JSON.parse(process.env.SERVICE_ACCOUNT_JSON); } catch(e) {}
    }
    // 2. Env var path
    const envPath = process.env.SERVICE_ACCOUNT_PATH || process.env.GOOGLE_APPLICATION_CREDENTIALS;
    if (envPath && fs.existsSync(envPath)) {
        try { return JSON.parse(fs.readFileSync(envPath, 'utf8')); } catch(e) {}
    }
    // 3. Local default files
    const defaultPaths = [
        path.join(__dirname, 'whisker-push-sa.json'),
        path.join(__dirname, 'service-account.json'),
        path.join(__dirname, 'firebase-service-account.json'),
        path.join(__dirname, 'serviceAccountKey.json')
    ];
    for (const p of defaultPaths) {
        if (fs.existsSync(p)) {
            try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch(e) {}
        }
    }
    return null;
}

function getFcmV1AccessToken(serviceAccount, callback) {
    const now = Math.floor(Date.now() / 1000);
    if (cachedOAuthToken && oAuthTokenExpiry > now + 60) {
        return callback(null, cachedOAuthToken);
    }

    if (!serviceAccount || !serviceAccount.client_email || !serviceAccount.private_key) {
        return callback(new Error("Invalid Service Account file. Missing client_email or private_key."));
    }

    const header = { alg: 'RS256', typ: 'JWT' };
    const claimSet = {
        iss: serviceAccount.client_email,
        scope: 'https://www.googleapis.com/auth/firebase.messaging',
        aud: 'https://oauth2.googleapis.com/token',
        exp: now + 3600,
        iat: now
    };

    function base64UrlEncode(data) {
        const str = typeof data === 'string' ? data : JSON.stringify(data);
        return Buffer.from(str).toString('base64')
            .replace(/=/g, '')
            .replace(/\+/g, '-')
            .replace(/\//g, '_');
    }

    const encodedHeader = base64UrlEncode(header);
    const encodedClaimSet = base64UrlEncode(claimSet);
    const signatureInput = `${encodedHeader}.${encodedClaimSet}`;

    try {
        const signer = crypto.createSign('RSA-SHA256');
        signer.update(signatureInput);
        const signature = signer.sign(serviceAccount.private_key, 'base64')
            .replace(/=/g, '')
            .replace(/\+/g, '-')
            .replace(/\//g, '_');

        const jwt = `${signatureInput}.${signature}`;
        const postData = `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`;

        const reqOpts = {
            hostname: 'oauth2.googleapis.com',
            path: '/token',
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'Content-Length': Buffer.byteLength(postData)
            }
        };

        const req = https.request(reqOpts, (res) => {
            let body = '';
            res.on('data', chunk => { body += chunk; });
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(body);
                    if (parsed.access_token) {
                        cachedOAuthToken = parsed.access_token;
                        oAuthTokenExpiry = now + (parsed.expires_in || 3600);
                        console.log(`[FCM OAuth2] Successfully authenticated with Google OAuth2 for FCM project '${serviceAccount.project_id}'. OAuth2 access token acquired.`);
                        callback(null, cachedOAuthToken);
                    } else {
                        console.error(`[FCM OAuth2 Error] Failed to obtain OAuth2 token for project '${serviceAccount.project_id}': ${parsed.error_description || body}`);
                        callback(new Error("OAuth Token error: " + (parsed.error_description || body)));
                    }
                } catch(e) {
                    callback(e);
                }
            });
        });

        req.on('error', callback);
        req.write(postData);
        req.end();
    } catch (e) {
        callback(e);
    }
}

let sqlite3;
try {
    sqlite3 = require('sqlite3').verbose();
} catch (e) {
    console.warn("Could not load 'sqlite3' dependency. Please run: npm install sqlite3");
    console.warn("Starting mock in-memory store for fallback (data will not persist without sqlite3)...");
}

// CONFIGURATION
const PORT = process.env.PORT || 3000;
const DB_FILE = process.env.DB_FILE || 'whisker_server.db';

class InMemoryDb {
    constructor() {
        this.requests = new Map();
        this.settings = new Map();
        this.messages = [];
        this.hostEvents = new Map();
        this.guestEvents = new Map();
        this.invites = new Map();
        this.clientPresence = new Map();
        this.messageIdCounter = 1;
    }

    serialize(cb) {
        if (cb) cb();
    }

    run(query, params, cb) {
        if (typeof params === 'function') {
            cb = params;
            params = [];
        }
        cb = cb || (() => {});
        try {
            if (query.includes("settings")) {
                let key = null;
                let value = null;
                if (params && params.length >= 2) {
                    key = params[0];
                    value = params[1];
                } else if (params && params.length === 1) {
                    value = params[0];
                    const match = query.match(/VALUES\s*\(\s*'([^']+)'\s*,\s*\?\s*\)/i);
                    if (match) {
                        key = match[1];
                    } else {
                        key = params[0];
                        value = null;
                    }
                } else {
                    const match = query.match(/VALUES\s*\(\s*'([^']+)'\s*,\s*'([^']+)'\s*\)/i);
                    if (match) {
                        key = match[1];
                        value = match[2];
                    }
                }
                if (key) {
                    this.settings.set(key, value);
                    console.log(`[InMemoryDb Debug] Set settings key '${key}' = '${value ? String(value).substring(0, 20) : value}...'`);
                }
                cb(null);
            } else if (query.includes("requests")) {
                if (query.includes("UPDATE")) {
                    const match = query.match(/status = '([^']+)'/i);
                    const status = match ? match[1] : 'APPROVED';
                    const [token, uuid] = params;
                    const req = this.requests.get(uuid);
                    if (req) {
                        req.status = status;
                        req.token = token;
                    }
                } else {
                    let uuid, name, reason, status, token, created_at;
                    if (params.length === 4) {
                        [uuid, name, reason, created_at] = params;
                        status = 'PENDING';
                        token = '';
                    } else if (params.length === 5) {
                        [uuid, name, reason, token, created_at] = params;
                        status = 'APPROVED';
                    } else {
                        [uuid, name, reason, status, token, created_at] = params;
                    }
                    if (uuid) this.requests.set(uuid, { uuid, name, reason, status, token, created_at });
                }
                cb(null);
            } else if (query.includes("host_events")) {
                if (query.includes("DELETE")) {
                    const eventId = params[0];
                    this.hostEvents.delete(eventId);
                } else {
                    const match = query.match(/VALUES\s*\(\s*\?,\s*'([^']+)'/i);
                    const type = match ? match[1] : (params.length >= 4 ? params[1] : 'unknown');
                    const [id, payload, created_at] = params.length === 3 ? params : [params[0], params[2], params[3]];
                    if (id) this.hostEvents.set(id, { id, type, payload, created_at });
                }
                cb(null);
            } else if (query.includes("guest_events")) {
                if (query.includes("DELETE")) {
                    const eventId = params[0];
                    this.guestEvents.delete(eventId);
                } else {
                    const [id, client_uuid, type, payload, created_at] = params;
                    if (id) this.guestEvents.set(id, { id, client_uuid, type, payload, created_at });
                }
                cb(null);
            } else if (query.includes("messages")) {
                const match = query.match(/VALUES\s*\(\s*\?,\s*'([^']+)'/i);
                const sender = match ? match[1] : (params.length >= 5 ? params[1] : 'unknown');
                let client_uuid, text, file_path, created_at;
                if (params.length === 4) {
                    [client_uuid, text, file_path, created_at] = params;
                } else if (params.length >= 5) {
                    [client_uuid, text, file_path, created_at] = [params[0], params[2], params[3], params[4]];
                }
                const msg = {
                    id: this.messageIdCounter++,
                    client_uuid,
                    sender,
                    text,
                    file_path,
                    created_at
                };
                this.messages.push(msg);
                cb(null);
            } else if (query.includes("invites")) {
                if (query.includes("UPDATE")) {
                    const code = params[0];
                    const inv = this.invites.get(code);
                    if (inv) inv.used = 1;
                } else if (query.includes("DELETE")) {
                    const code = params[0];
                    this.invites.delete(code);
                } else {
                    const [code, name, created_at] = params;
                    if (code) this.invites.set(code, { code, name, used: 0, created_at: created_at || Date.now() });
                }
                cb(null);
            } else if (query.includes("client_presence")) {
                const [uuid, is_online, last_seen] = params;
                if (uuid) this.clientPresence.set(uuid, { uuid, is_online, last_seen });
                cb(null);
            } else {
                cb(null);
            }
        } catch (err) {
            cb(err);
        }
    }

    get(query, params, cb) {
        if (typeof params === 'function') {
            cb = params;
            params = [];
        }
        cb = cb || (() => {});
        try {
            if (query.includes("settings")) {
                let key = (params && params.length > 0) ? params[0] : null;
                if (!key) {
                    const match = query.match(/key\s*=\s*'([^']+)'/i);
                    if (match) key = match[1];
                }
                const val = this.settings.get(key);
                cb(null, val !== undefined && val !== null ? { value: val } : null);
            } else if (query.includes("requests")) {
                let uuid = (params && params.length > 0) ? params[0] : null;
                if (!uuid) {
                    const match = query.match(/uuid\s*=\s*'([^']+)'/i);
                    if (match) uuid = match[1];
                }
                const val = this.requests.get(uuid) || null;
                cb(null, val);
            } else if (query.includes("invites")) {
                let code = (params && params.length > 0) ? params[0] : null;
                if (!code) {
                    const match = query.match(/code\s*=\s*'([^']+)'/i);
                    if (match) code = match[1];
                }
                cb(null, this.invites.get(code) || null);
            } else {
                cb(null, null);
            }
        } catch (err) {
            cb(err);
        }
    }

    all(query, params, cb) {
        if (typeof params === 'function') {
            cb = params;
            params = [];
        }
        cb = cb || (() => {});
        try {
            if (query.includes("messages")) {
                const client_uuid = params[0];
                const list = this.messages.filter(m => m.client_uuid === client_uuid)
                    .sort((a, b) => a.created_at - b.created_at);
                cb(null, list);
            } else if (query.includes("guest_events")) {
                const client_uuid = params[0];
                const list = Array.from(this.guestEvents.values())
                    .filter(e => e.client_uuid === client_uuid)
                    .sort((a, b) => a.created_at - b.created_at);
                cb(null, list);
            } else if (query.includes("host_events")) {
                const list = Array.from(this.hostEvents.values())
                    .sort((a, b) => a.created_at - b.created_at);
                cb(null, list);
            } else if (query.includes("invites")) {
                const list = Array.from(this.invites.values()).sort((a, b) => b.created_at - a.created_at);
                cb(null, list);
            } else if (query.includes("requests")) {
                const list = Array.from(this.requests.values()).map(r => {
                    const presence = this.clientPresence.get(r.uuid) || { is_online: 1, last_seen: Date.now() };
                    return {
                        uuid: r.uuid,
                        name: r.name,
                        reason: r.reason || '',
                        status: r.status || 'PENDING',
                        req_status: r.status || 'PENDING',
                        token: r.token || '',
                        created_at: r.created_at || Date.now(),
                        is_online: presence.is_online,
                        last_seen: presence.last_seen
                    };
                }).sort((a, b) => b.created_at - a.created_at);
                cb(null, list);
            } else {
                cb(null, []);
            }
        } catch (err) {
            cb(err);
        }
    }
}

// STATE STORES ( SSE Stream Connections)
const clientStreams = new Map(); // uuid -> http response object
const hostStreams = new Set(); // http response objects for host app

// INITIALIZE DATABASE & PAIRING STATE
let db = null;
let isInitialized = false;
let savedHostPin = null;

function setupDatabaseSchema() {
    db.serialize(() => {
        db.run(`CREATE TABLE IF NOT EXISTS requests (
            uuid TEXT PRIMARY KEY,
            name TEXT,
            reason TEXT,
            status TEXT DEFAULT 'PENDING',
            token TEXT,
            created_at INTEGER
        )`);

        db.run(`CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            client_uuid TEXT,
            sender TEXT,
            text TEXT,
            file_path TEXT,
            created_at INTEGER
        )`);

        db.run(`CREATE TABLE IF NOT EXISTS host_events (
            id TEXT PRIMARY KEY,
            type TEXT,
            payload TEXT,
            created_at INTEGER
        )`);

        db.run(`CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT
        )`);

        db.run(`CREATE TABLE IF NOT EXISTS invites (
            code TEXT PRIMARY KEY,
            name TEXT,
            used INTEGER DEFAULT 0,
            created_at INTEGER
        )`);
        
        db.run(`CREATE TABLE IF NOT EXISTS client_presence (
            uuid TEXT PRIMARY KEY,
            is_online INTEGER DEFAULT 0,
            last_seen INTEGER
        )`);

        db.run(`CREATE TABLE IF NOT EXISTS client_status_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT,
            status TEXT,
            timestamp INTEGER
        )`);

        db.run(`CREATE TABLE IF NOT EXISTS guest_events (
            id TEXT PRIMARY KEY,
            client_uuid TEXT,
            type TEXT,
            payload TEXT,
            created_at INTEGER
        )`);

        // Load dynamic pairing access token if it exists
        db.get("SELECT value FROM settings WHERE key = 'host_pin_token'", (err, row) => {
            if (!err && row && row.value) {
                savedHostPin = row.value;
                isInitialized = true;
                console.log("Loaded active host private access key pairing boundary.");
            } else {
                isInitialized = false;
                console.log("No stored host access key found. Companion server in uninitialized pairing mode; waiting for first mobile app connection.");
            }
        });
    });
}

if (sqlite3) {
    db = new sqlite3.Database(DB_FILE, (err) => {
        if (err) {
            console.error("Database connection failed, falling back to in-memory store:", err.message);
            db = new InMemoryDb();
            setupDatabaseSchema();
        } else {
            console.log("Prism SQLite Database established:", DB_FILE);
            setupDatabaseSchema();
        }
    });
} else {
    console.log("No sqlite3 driver found. Initializing mock in-memory store in pairing mode.");
    db = new InMemoryDb();
    setupDatabaseSchema();
}

function initDb(tokenToSave, callback) {
    if (isInitialized) {
        if (callback) callback();
        return;
    }

    console.log("Saving dynamic pairing credentials on first mobile app connection...");
    db.run(`INSERT OR REPLACE INTO settings (key, value) VALUES ('host_pin_token', ?)`, [tokenToSave], (err) => {
        if (err) {
            console.error("Failed to store pairing access token settings:", err.message);
            if (callback) callback(err);
        } else {
            console.log("Successfully paired home server with mobile app access key!");
            savedHostPin = tokenToSave;
            isInitialized = true;
            if (callback) callback();
        }
    });
}

// TICKLE NOTIFIER ROUTER
// Performs a direct tiny HTTP POST to registered push endpoints using FCM HTTP v1 API
function sendTickleNotification(eventId, eventType) {
    if (!isInitialized || !db) {
        return;
    }

    db.get("SELECT value FROM settings WHERE key = 'pushToken'", (err, row) => {
        if (err || !row || !row.value) {
            console.log(`[Push Notification] Skipping FCM tickle for event '${eventType}' (id: ${eventId}): No FCM pushToken registered from mobile app yet.`);
            return;
        }

        const pushToken = row.value;

        if (pushToken.startsWith('device-push-token-') || pushToken.startsWith('mock-') || pushToken.startsWith('fallback-')) {
            console.log(`[Push Notification Info] Skipping FCM HTTP v1 dispatch for event '${eventType}' (id: ${eventId}): Token '${pushToken}' is a local fallback mock token. Real FCM push notifications require an active Google Play Services FCM token from the mobile app.`);
            return;
        }

        const sa = getServiceAccount();

        if (!sa || !sa.project_id) {
            console.log(`[Push Notification] Skipping FCM tickle for event '${eventType}' (id: ${eventId}): 'whisker-push-sa.json' not found on home server. Run 'python fcm_setup.py' or place whisker-push-sa.json in the server root directory.`);
            return;
        }

        console.log(`[Push Notification] Dispatching FCM HTTP v1 tickle for event '${eventType}' (id: ${eventId}) to token ${pushToken.substring(0, 15)}... using service-account (${sa.project_id})`);
        getFcmV1AccessToken(sa, (err, accessToken) => {
            if (err) {
                console.error(`[FCM v1 Error] Failed to obtain OAuth token from service account (${sa.project_id}):`, err.message);
                return;
            }

            const v1Body = JSON.stringify({
                message: {
                    token: pushToken,
                    data: {
                        event: "tickle",
                        eventId: String(eventId),
                        type: String(eventType),
                        timestamp: String(Date.now())
                    },
                    android: {
                        priority: "HIGH"
                    }
                }
            });

            const reqOpts = {
                hostname: 'fcm.googleapis.com',
                path: `/v1/projects/${sa.project_id}/messages:send`,
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${accessToken}`,
                    'Content-Length': Buffer.byteLength(v1Body)
                }
            };

            const clientReq = https.request(reqOpts, (res) => {
                let resData = '';
                res.on('data', chunk => { resData += chunk.toString(); });
                res.on('end', () => {
                    if (res.statusCode >= 200 && res.statusCode < 300) {
                        console.log(`[FCM v1 Push Success] HTTP ${res.statusCode}: Successfully sent push tickle to mobile app for event '${eventType}' (id: ${eventId})!`);
                    } else {
                        console.error(`[FCM v1 Push Error] HTTP ${res.statusCode}: FCM push tickle failed for event '${eventType}' (id: ${eventId}). Server response: ${resData}`);
                        if (res.statusCode === 403 && resData.includes("cloudmessaging.messages.create")) {
                            console.error(`[FCM Setup Help] HTTP 403 indicates that 'cloudmessaging.googleapis.com' (Firebase Cloud Messaging API) needs to be enabled on GCP project '${sa.project_id}' and 'roles/firebasemessaging.admin' or 'roles/firebase.admin' assigned to service account '${sa.client_email}'. Run updated 'python3 fcm_setup.py' to configure automatically.`);
                        }
                    }
                });
            });

            clientReq.on('error', (e) => {
                console.error("[FCM v1 Push Error] Failed to dispatch FCM v1 push request:", e.message);
            });

            clientReq.write(v1Body);
            clientReq.end();
        });
    });
}

// CORE HTTP ROUTING ENGINE
const server = http.createServer((req, res) => {
    const parsedUrl = url.parse(req.url, true);
    const pathname = parsedUrl.pathname;

    // CORS Headers logic
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');

    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }

    if (pathname === '/api/init-status' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ initialized: isInitialized }));
        return;
    }

    // Server-wide init-blocker for non-host APIs & non-static views
    if (!isInitialized) {
        if (pathname === '/' || pathname === '/index.html' || pathname.startsWith('/invite/')) {
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(getWaitingForAppHtml());
            return;
        }

        if (!pathname.startsWith('/api/host/')) {
            res.writeHead(503, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: "Server is not initialized. Please connect your Whisker mobile app first." }));
            return;
        }
    }

    // STATIC PORTAL ROUTER
    if (pathname === '/' || pathname === '/index.html' || pathname.startsWith('/invite/')) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        let html = getEmbeddedCompanionHtml();
        if (pathname.startsWith('/invite/')) {
            const inviteCode = pathname.split('/')[2] || '';
            html = html.replace('</head>', `<script>window.WHISKER_INVITE_CODE = "${inviteCode}";</script></head>`);
        }
        res.end(html);
        return;
    }

    // JSON API PARSER HELPER
    function collectJsonBody(callback) {
        let body = '';
        req.on('data', chunk => { body += chunk.toString(); });
        req.on('end', () => {
            try {
                const data = JSON.parse(body);
                callback(data);
            } catch (e) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: "Invalid JSON format payload" }));
            }
        });
    }

    // SECURITY ROUTER: HOST API AUTHENTICATION
    function authorizeHost(apiHandler) {
        const authHeader = req.headers['authorization'];
        const token = authHeader ? authHeader.replace(/^Bearer\s+/i, '').trim() : '';

        if (!isInitialized) {
            if (token) {
                initDb(token, (err) => {
                    if (err) {
                        res.writeHead(500, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ error: "Failed to initialize storage pairing: " + err.message }));
                    } else {
                        apiHandler();
                    }
                });
            } else {
                console.warn("Unauthorized initialization attempt: Missing authorization header access key!");
                res.writeHead(401, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: "Access Key required in Authorization header to complete home server pairing." }));
            }
            return;
        }

        if (token && token === savedHostPin) {
            apiHandler();
        } else {
            console.warn("Unauthorized host attempt blocked. Remote IP:", req.socket.remoteAddress);
            res.writeHead(401, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: "Unauthorized access key" }));
        }
    }

    // SECURITY ROUTER: GUEST API AUTHENTICATION
    function authorizeGuest(uuid, token, apiHandler) {
        if (!uuid) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: "Missing visitor UUID" }));
            return;
        }

        db.get("SELECT * FROM requests WHERE uuid = ?", [uuid], (err, row) => {
            if (err || !row) {
                res.writeHead(403, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: "No visitor registration request found" }));
            } else if (row.status !== 'APPROVED') {
                res.writeHead(403, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: "Visitor request is still pending approval" }));
            } else if (row.token !== token) {
                res.writeHead(403, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: "Invalid registration token" }));
            } else {
                apiHandler(row);
            }
        });
    }

    // ============================================
    // GUEST APIs
    // ============================================

    // 1. Submit Visitor Request
    if (pathname === '/api/register' && req.method === 'POST') {
        collectJsonBody((body) => {
            const { uuid, name, reason } = body;
            if (!uuid || !name) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: "Missing fields" }));
                return;
            }

            const timestamp = Date.now();
            db.run(`INSERT OR REPLACE INTO requests (uuid, name, reason, status, token, created_at) 
                    VALUES (?, ?, ?, 'PENDING', '', ?)`, [uuid, name, reason || '', timestamp], (err) => {
                if (err) {
                    res.writeHead(500, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: err.message }));
                    return;
                }

                // Add to device event queue
                const eventId = 'evt_' + Math.random().toString(36).substr(2, 9);
                const hostPayload = JSON.stringify({ uuid, name, reason: reason || '' });
                db.run(`INSERT INTO host_events (id, type, payload, created_at) VALUES (?, 'visitor', ?, ?)`,
                    [eventId, hostPayload, timestamp], () => {
                        sendTickleNotification(eventId, 'visitor');
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ status: "queued", eventId }));
                    });
            });
        });
    }

    // 1.5. Claim One-Time Invite
    else if (pathname === '/api/claim_invite' && req.method === 'POST') {
        collectJsonBody((body) => {
            const { code } = body;
            if (!code) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: "Missing invite code" }));
                return;
            }
            db.get("SELECT * FROM invites WHERE code = ?", [code], (err, row) => {
                if (err || !row) {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: "Invite link not found or invalid." }));
                } else if (row.used) {
                    res.writeHead(403, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: "This invite link has already been used once." }));
                } else {
                    db.run("UPDATE invites SET used = 1 WHERE code = ?", [code], () => {
                        const clientUuid = 'cli_' + Math.random().toString(36).substr(2, 9);
                        const clientToken = 'tok_' + Math.random().toString(36).substr(2, 12);
                        const clientName = row.name || 'Invited Guest';
                        const timestamp = Date.now();

                        console.log(`[Invite] Claimed invite code: ${code} for client: ${clientName} (${clientUuid})`);

                        db.run(`INSERT OR REPLACE INTO requests (uuid, name, reason, status, token, created_at) 
                                VALUES (?, ?, ?, 'APPROVED', ?, ?)`,
                                [clientUuid, clientName, `Invited via link (${code})`, clientToken, timestamp], () => {
                            const eventId = 'evt_' + Math.random().toString(36).substr(2, 9);
                            const hostPayload = JSON.stringify({
                                event: "invite_claimed",
                                code,
                                uuid: clientUuid,
                                name: clientName,
                                token: clientToken
                            });
                            db.run(`INSERT INTO host_events (id, type, payload, created_at) VALUES (?, 'invite_claimed', ?, ?)`,
                                [eventId, hostPayload, timestamp], () => {
                                    sendTickleNotification(eventId, 'invite_claimed');
                                    res.writeHead(200, { 'Content-Type': 'application/json' });
                                    res.end(JSON.stringify({ success: true, uuid: clientUuid, token: clientToken, name: clientName }));
                                });
                        });
                    });
                }
            });
        });
    }

    // 2. Load Message History
    else if (pathname === '/api/messages' && req.method === 'GET') {
        const uuid = parsedUrl.query.uuid;
        const token = parsedUrl.query.token;

        authorizeGuest(uuid, token, (client) => {
            db.all("SELECT * FROM messages WHERE client_uuid = ? ORDER BY created_at ASC", [uuid], (err, rows) => {
                if (err) {
                    res.writeHead(500, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: err.message }));
                    return;
                }
                const formatted = rows.map(r => ({
                    id: r.id,
                    sender: r.sender,
                    text: r.text,
                    file_path: r.file_path,
                    created_at: r.created_at
                }));
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(formatted));
            });
        });
    }

    // 3. Send Message
    else if (pathname === '/api/message' && req.method === 'POST') {
        collectJsonBody((body) => {
            const { uuid, token, text, file } = body;
            authorizeGuest(uuid, token, (client) => {
                const timestamp = Date.now();
                db.run("INSERT INTO messages (client_uuid, sender, text, file_path, created_at) VALUES (?, 'guest', ?, ?, ?)",
                    [uuid, text, file || null, timestamp], function(err) {
                        if (err) {
                            res.writeHead(550, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ error: err.message }));
                            return;
                        }

                        // Add to host events
                        const eventId = 'evt_' + Math.random().toString(36).substr(2, 9);
                        const hostPayload = JSON.stringify({
                            uuid,
                            name: client.name,
                            text,
                            file: file || null,
                            timestamp,
                            token: client.token
                        });

                        db.run(`INSERT INTO host_events (id, type, payload, created_at) VALUES (?, 'message', ?, ?)`,
                            [eventId, hostPayload, timestamp], () => {
                                sendTickleNotification(eventId, 'message');
                                res.writeHead(200, { 'Content-Type': 'application/json' });
                                res.end(JSON.stringify({ status: "delivered", id: this.lastID }));
                            });
                    });
            });
        });
    }

    // 4. Send WebRTC Call SDP
    else if (pathname === '/api/call' && req.method === 'POST') {
        collectJsonBody((body) => {
            const { uuid, token, video, sdp } = body;
            authorizeGuest(uuid, token, (client) => {
                const timestamp = Date.now();
                const eventId = 'evt_' + Math.random().toString(36).substr(2, 9);
                const hostPayload = JSON.stringify({
                    uuid,
                    name: client.name,
                    video: !!video,
                    sdp,
                    token: client.token
                });

                db.run(`INSERT INTO host_events (id, type, payload, created_at) VALUES (?, 'call', ?, ?)`,
                    [eventId, hostPayload, timestamp], () => {
                        sendTickleNotification(eventId, 'call');
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ status: "dialing" }));
                    });
            });
        });
    }

    // 5. Send Hangup SDP
    else if (pathname === '/api/hangup' && req.method === 'POST') {
        collectJsonBody((body) => {
            const { uuid, token } = body;
            authorizeGuest(uuid, token, (client) => {
                const timestamp = Date.now();
                const eventId = 'evt_' + Math.random().toString(36).substr(2, 9);
                const hostPayload = JSON.stringify({
                    uuid,
                    token: client.token
                });

                db.run(`INSERT INTO host_events (id, type, payload, created_at) VALUES (?, 'hangup', ?, ?)`,
                    [eventId, hostPayload, timestamp], () => {
                        sendTickleNotification(eventId, 'hangup');
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ status: "hungup" }));
                    });
            });
        });
    }

    // 6. SSE Event Sourcing for Guests (real-time stream)
    else if (pathname === '/api/events' && req.method === 'GET') {
        const uuid = parsedUrl.query.uuid;
        const token = parsedUrl.query.token;

        // Note: visitor is allowed to connect to events even if pending (token is empty)
        // to listen for their approval!
        if (!uuid) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: "Missing subscriber UUID" }));
            return;
        }

        console.log(`Guest streaming channel subscribed: ${uuid}`);
        res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive'
        });

        // Store response stream
        clientStreams.set(uuid, res);
        
        // Log presence
        db.run(`INSERT OR REPLACE INTO client_presence (uuid, is_online, last_seen) VALUES (?, 1, ?)`, [uuid, Date.now()]);
        db.run(`INSERT INTO client_status_logs (uuid, status, timestamp) VALUES (?, 'online', ?)`, [uuid, Date.now()]);

        // Deliver offline events
        db.all("SELECT * FROM guest_events WHERE client_uuid = ? ORDER BY created_at ASC", [uuid], (err, rows) => {
            if (!err && rows && rows.length > 0) {
                console.log(`Delivering ${rows.length} offline events to guest ${uuid}`);
                rows.forEach(row => {
                    res.write(`data: ${row.payload}\n\n`);
                    db.run(`DELETE FROM guest_events WHERE id = ?`, [row.id]);
                });
            }
        });

        // Periodically send simple comment to prevent stream timeouts
        const intervalId = setInterval(() => {
            res.write(': keep-alive\n\n');
        }, 15000);

        req.on('close', () => {
            clearInterval(intervalId);
            clientStreams.delete(uuid);
            
            // Log offline presence
            db.run(`INSERT OR REPLACE INTO client_presence (uuid, is_online, last_seen) VALUES (?, 0, ?)`, [uuid, Date.now()]);
            db.run(`INSERT INTO client_status_logs (uuid, status, timestamp) VALUES (?, 'offline', ?)`, [uuid, Date.now()]);
            
            console.log(`Guest streaming channel closed: ${uuid}`);
        });
    }

    // ============================================
    // HOST (PHONE) APIs
    // ============================================

    // Direct SSE Stream for Mobile App Fallback
    else if (pathname === '/api/host/stream' && req.method === 'GET') {
        authorizeHost(() => {
            res.writeHead(200, {
                'Content-Type': 'text/event-stream',
                'Cache-Control': 'no-cache',
                'Connection': 'keep-alive',
                'Access-Control-Allow-Origin': '*'
            });
            res.write('retry: 5000\n\n');
            res.write(': keep-alive\n\n');

            hostStreams.add(res);

            const intervalId = setInterval(() => {
                try {
                    res.write(': keep-alive\n\n');
                } catch (e) {
                    clearInterval(intervalId);
                    hostStreams.delete(res);
                }
            }, 15000);

            req.on('close', () => {
                clearInterval(intervalId);
                hostStreams.delete(res);
                console.log("Host direct stream closed");
            });
        });
    }

    // 7. Register Phone FCM Push Token
    else if (pathname === '/api/host/register_push' && req.method === 'POST') {
        authorizeHost(() => {
            collectJsonBody((body) => {
                const pushToken = body.pushToken || body.fcmToken || body.token || req.headers['x-push-token'];
                if (pushToken && pushToken !== 'undefined' && pushToken !== 'null' && pushToken.trim().length > 0 && !pushToken.startsWith("Fetching") && !pushToken.startsWith("Searching") && !pushToken.startsWith("device-push-token-")) {
                    db.get("SELECT value FROM settings WHERE key = 'pushToken'", (err, row) => {
                        if (!row || row.value !== pushToken) {
                            db.run(`INSERT OR REPLACE INTO settings (key, value) VALUES ('pushToken', ?)`, [pushToken], (err) => {
                                if (err) {
                                    console.error("[Push Token Error] Failed to save pushToken in settings DB:", err.message);
                                } else {
                                    console.log(`[Push Token] Successfully registered active phone FCM push Token in settings: ${pushToken.substring(0, 20)}...`);
                                }
                            });
                        }
                    });
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ message: "Push token registered successfully" }));
                } else {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ message: "No active FCM push token provided (ignored)" }));
                }
            });
        });
    }

    // 7.5 Get / Update FCM Configuration for Mobile App Pairing
    else if (pathname === '/api/host/fcm_config' && req.method === 'GET') {
        authorizeHost(() => {
            const sa = getServiceAccount();
            db.get("SELECT value FROM settings WHERE key = 'fcm_api_key'", (err, row) => {
                const dbApiKey = row ? row.value : null;
                if (sa && sa.project_id) {
                    const senderId = sa.project_number || sa.gcm_sender_id || sa.sender_id || sa.project_id;
                    const apiKey = sa.api_key || sa.apiKey || dbApiKey || "AIzaSyWhiskerAutoConfiguredKey";
                    const appId = sa.app_id || (sa.client_id ? `1:${senderId}:android:${sa.client_id}` : `1:${senderId}:android:whisker`);

                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({
                        configured: true,
                        project_id: sa.project_id,
                        sender_id: String(senderId),
                        app_id: appId,
                        api_key: apiKey
                    }));
                } else {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({
                        configured: false,
                        error: "whisker-push-sa.json not found on Home Server"
                    }));
                }
            });
        });
    }
    else if (pathname === '/api/host/fcm_config' && req.method === 'POST') {
        authorizeHost(() => {
            collectJsonBody((body) => {
                const newApiKey = body.api_key || body.apiKey;
                if (newApiKey) {
                    db.run(`INSERT OR REPLACE INTO settings (key, value) VALUES ('fcm_api_key', ?)`, [newApiKey], (err) => {
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ message: "FCM API Key updated successfully" }));
                    });
                } else {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: "Missing api_key parameter" }));
                }
            });
        });
    }

    // 8. Pull Unprocessed Host Events (Tickled Events)
    else if (pathname === '/api/host/events' && req.method === 'GET') {
        authorizeHost(() => {
            db.all("SELECT * FROM host_events ORDER BY created_at ASC", [], (err, rows) => {
                if (err) {
                    res.writeHead(500, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: err.message }));
                    return;
                }
                const formatted = rows.map(r => ({
                    id: r.id,
                    type: r.type,
                    payload: JSON.parse(r.payload)
                }));
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(formatted));
            });
        });
    }

    // 9. Acknowledge Received Event (Clears it from SQLite server)
    else if (pathname === '/api/host/ack' && req.method === 'POST') {
        authorizeHost(() => {
            collectJsonBody((body) => {
                const { eventId } = body;
                db.run("DELETE FROM host_events WHERE id = ?", [eventId], (err) => {
                    if (err) {
                        res.writeHead(500, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ error: err.message }));
                        return;
                    }
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ message: "Event cleared" }));
                });
            });
        });
    }

    // 9.5. Create Invite
    else if (pathname === '/api/host/create_invite' && req.method === 'POST') {
        authorizeHost(() => {
            collectJsonBody((body) => {
                const name = body.name || 'Guest';
                const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
                let code = body.code || '';
                if (!code) {
                    for (let i = 0; i < 5; i++) {
                        code += chars.charAt(Math.floor(Math.random() * chars.length));
                    }
                }
                const timestamp = Date.now();
                db.run("INSERT OR REPLACE INTO invites (code, name, used, created_at) VALUES (?, ?, 0, ?)", [code, name, timestamp], (err) => {
                    if (err) {
                        res.writeHead(500, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ error: err.message }));
                        return;
                    }
                    console.log(`[Invite] Created one-time invite code: ${code} for ${name}`);
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: true, code, name, created_at: timestamp }));
                });
            });
        });
    }

    // 9.6. Get Invites
    else if (pathname === '/api/host/invites' && req.method === 'GET') {
        authorizeHost(() => {
            db.all("SELECT * FROM invites ORDER BY created_at DESC", [], (err, rows) => {
                if (err) {
                    res.writeHead(500, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: err.message }));
                    return;
                }
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(rows || []));
            });
        });
    }

    // 9.7. Delete Invite
    else if (pathname === '/api/host/delete_invite' && req.method === 'POST') {
        authorizeHost(() => {
            collectJsonBody((body) => {
                db.run("DELETE FROM invites WHERE code = ?", [body.code], () => {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: true }));
                });
            });
        });
    }

    // 9b. Fetch Client Presence
    else if (pathname === '/api/host/clients' && req.method === 'GET') {
        authorizeHost(() => {
            db.all(`
                SELECT r.uuid, r.name, r.reason, r.status as req_status, r.token, r.created_at, 
                       c.is_online, c.last_seen
                FROM requests r
                LEFT JOIN client_presence c ON r.uuid = c.uuid
            `, [], (err, rows) => {
                if (err) {
                    res.writeHead(500, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: err.message }));
                    return;
                }
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(rows));
            });
        });
    }

    // 10. Post Phone Responses (Replying to Chat Messages, Approving Requests, WebRTC Handshake answers)
    else if (pathname === '/api/host/response' && req.method === 'POST') {
        authorizeHost(() => {
            collectJsonBody((body) => {
                const clientUuid = body.clientUuid || body.uuid;
                if (!clientUuid) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: "Missing client destination ID" }));
                    return;
                }

                console.log(`Relaying host response to client: ${clientUuid}`);

                // Intercept approval to save credentials in database
                if (body.type === 'registration_response' && body.event === 'approved') {
                    db.run("UPDATE requests SET status = 'APPROVED', token = ? WHERE uuid = ?", [body.token, clientUuid], (err) => {
                        if (err) console.error("Database status approval update failed:", err.message);
                    });
                }

                // Intercept manual text messages reply to save in historical storage
                if (body.type === 'chat_message' && body.sender === 'phone') {
                    db.run("INSERT INTO messages (client_uuid, sender, text, file_path, created_at) VALUES (?, 'phone', ?, ?, ?)",
                        [clientUuid, body.text, body.filePath || null, body.timestamp || Date.now()], (err) => {
                            if (err) console.error("Database chat history append failed:", err.message);
                        });
                }

                const eventId = 'gevt_' + Math.random().toString(36).substr(2, 9);
                db.run(`INSERT INTO guest_events (id, client_uuid, type, payload, created_at) VALUES (?, ?, ?, ?, ?)`,
                    [eventId, clientUuid, body.type, JSON.stringify(body), Date.now()], (err) => {
                        if (err) console.error("Guest event storage failed:", err.message);
                        
                        // Forward message payload to active web SSE client stream
                        const stream = clientStreams.get(clientUuid);
                        if (stream) {
                            stream.write(`data: ${JSON.stringify(body)}\n\n`);
                            // auto-delete if delivered immediately via active stream
                            db.run(`DELETE FROM guest_events WHERE id = ?`, [eventId]);
                        }
                    });

                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ message: "Response routed" }));
            });
        });
    }

    // 10b. Global Info Endpoint
    else if (pathname === '/api/info' && req.method === 'GET') {
        db.get("SELECT value FROM settings WHERE key = 'pushToken'", (err, row) => {
            const pushConfigured = (!err && row && row.value);
            const hostConnected = hostStreams.size > 0;
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
                host_connected: hostConnected,
                push_configured: pushConfigured,
                host_reachable: hostConnected || pushConfigured,
                server_time: Date.now()
            }));
        });
    }

    // 11. Unknown Routes Fallback
    else {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: "Endpoint not found" }));
    }
});

// START LISTENING
server.listen(PORT, () => {
    console.log(`=======================================================`);
    console.log(`🐯 Whisker Home Server running securely on port ${PORT}!`);
    console.log(`   - SQLite DB configured: "${DB_FILE}"`);
    console.log(`   - Local guest web panel: http://localhost:${PORT}/`);
    const sa = getServiceAccount();
    if (sa && sa.project_id) {
        console.log(`   - FCM Service Account: LOADED ('whisker-push-sa.json' for project '${sa.project_id}')`);
    } else {
        console.log(`   - FCM Service Account: NOT FOUND (Run 'python fcm_setup.py' to create 'whisker-push-sa.json')`);
    }
    console.log(`=======================================================`);
});

// GET WAITING FOR APP PRE-INITIALIZATION SCREEN
function getWaitingForAppHtml() {
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🐯 Whisker Setup</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <style>
        body {
            background-color: #0b0f19;
            color: #f3f4f6;
            font-family: system-ui, -apple-system, sans-serif;
        }
        .glow-cyan {
            box-shadow: 0 0 25px rgba(6, 182, 212, 0.4);
        }
        .pulse-ring {
            animation: pulse-glow 2s infinite;
        }
        @keyframes pulse-glow {
            0%, 100% { transform: scale(1); opacity: 0.6; }
            50% { transform: scale(1.1); opacity: 1; }
        }
    </style>
</head>
<body class="flex flex-col min-h-screen items-center justify-center p-4">
    <div class="max-w-md w-full bg-gray-900 border border-gray-800 p-8 rounded-3xl glow-cyan text-center space-y-6">
        <div class="relative w-20 h-20 mx-auto flex items-center justify-center">
            <div class="absolute inset-0 bg-cyan-500 rounded-full blur-xl opacity-30 pulse-ring"></div>
            <div class="relative w-16 h-16 rounded-2xl bg-cyan-500 flex items-center justify-center font-bold text-black text-3xl">
                🐯
            </div>
        </div>
        
        <div class="space-y-2">
            <h1 class="text-2xl font-black text-white tracking-tight">Whisker Home Server</h1>
            <p class="text-sm text-cyan-400 font-medium">Device Link Pending Setup</p>
        </div>

        <div class="p-4 rounded-2xl bg-gray-950 border border-gray-850 text-left space-y-3">
            <p class="text-xs text-gray-400 leading-relaxed">
                As long as the local database is not initialized, the web client is waiting for the mobile app to make its first connection.
            </p>
            <div class="border-t border-gray-800 pt-3 space-y-2">
                <span class="text-[10px] font-bold text-gray-400 uppercase tracking-wider">Quick Instructions:</span>
                <ol class="list-decimal list-inside text-xs text-gray-300 space-y-1.5 leading-normal">
                    <li>Launch the <b class="text-white">Whisker App</b> on your phone.</li>
                    <li>Go to the <span class="bg-gray-800 px-1 py-0.5 rounded">Setup / Cloud Configuration</span> panel.</li>
                    <li>Set the Home Server URL to:<br>
                        <code id="serverAddress" class="text-cyan-400 font-mono font-bold select-all break-all">http://localhost:3000</code>
                    </li>
                    <li>Choose a custom URL or use the detected Home Server URL.</li>
                    <li>Your private access key is automatically transmitted to pair!</li>
                </ol>
            </div>
        </div>

        <div class="flex items-center justify-center space-x-2 text-xs text-gray-400 font-mono bg-gray-950/50 py-2 rounded-xl">
            <span class="w-2 h-2 rounded-full bg-yellow-500 animate-pulse"></span>
            <span>Waiting for first contact...</span>
        </div>
    </div>

    <script>
        // Dynamically insert location origin to help user copy address
        document.getElementById('serverAddress').innerText = window.location.origin;

        // Poll server initialization status every 2 seconds
        setInterval(async () => {
            try {
                const res = await fetch('/api/init-status');
                const data = await res.json();
                if (data && data.initialized) {
                    window.location.reload();
                }
            } catch (e) {
                console.log("Waiting for server...");
            }
        }, 2000);
    </script>
</body>
</html>`;
}

// THE LIGHTWEIGHT COMPANION WEB CLIENT HTML
const GUEST_CLIENT_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Get In Touch</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <style>
        body {
            background-color: #0b0f19;
            color: #f3f4f6;
            font-family: system-ui, -apple-system, sans-serif;
        }
        .glow-cyan {
            box-shadow: 0 0 15px rgba(6, 182, 212, 0.4);
        }
        .glow-ring {
            animation: pulse-glow 2s infinite;
        }
        @keyframes pulse-glow {
            0%, 100% { box-shadow: 0 0 10px rgba(6, 182, 212, 0.2); }
            50% { box-shadow: 0 0 25px rgba(6, 182, 212, 0.6); }
        }
    </style>
</head>
<body class="flex flex-col min-h-screen p-4 md:p-8">

    <header class="max-w-4xl mx-auto w-full mb-6 flex justify-between items-center bg-gray-900 border border-gray-800 p-4 rounded-2xl">
        <div class="flex items-center space-x-3">
            <div class="w-10 h-10 rounded-xl bg-cyan-500 flex items-center justify-center font-bold text-black text-xl">
                🐱
            </div>
            <div>
                <h1 class="text-lg font-bold text-white leading-none">Whisker</h1>
            </div>
        </div>
        <div id="connectionStatus" class="flex items-center space-x-2 bg-gray-800 px-3 py-1.5 rounded-full text-xs">
            <span class="w-2.5 h-2.5 rounded-full bg-yellow-500" id="statusDot"></span>
            <span id="statusText" class="text-gray-300">Checking connection</span>
        </div>
    </header>

    <main class="max-w-4xl mx-auto w-full flex-1 flex flex-col justify-center">

        <!-- STEP 1: CHOOSE NAME & REASON FLUID WINDOW -->
        <div id="unprovisionedView" class="hidden bg-gray-900 border border-gray-800 p-6 md:p-8 rounded-3xl glow-cyan max-w-md mx-auto w-full transition duration-300">
            <h2 class="text-2xl font-black text-cyan-400 mb-2">Get in Touch</h2>
            <p class="text-sm text-gray-400 mb-4 leading-relaxed">
                Please introduce yourself to start a voice call or send direct text messages.
            </p>
            <div class="mb-6 p-3 rounded-xl bg-gray-950 border border-gray-850 text-xs text-gray-400 space-y-1">
                <p>💡 <span class="font-bold text-gray-300">Direct Connection Active:</span> Your messages and calls connect directly to the phone via low-latency secure event streams, keeping your history stored safely on your private server.</p>
            </div>

            <form id="requestForm" class="space-y-4">
                <div>
                    <label class="block text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1.5">Your Name</label>
                    <input type="text" id="clientNameInput" required placeholder="e.g. Liam" 
                           class="w-full bg-gray-950 border border-gray-800 rounded-xl px-4 py-3 text-white text-sm focus:outline-none focus:ring-1 focus:ring-cyan-500 focus:border-cyan-500">
                </div>
                <div>
                    <label class="block text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1.5">Reason for Contact</label>
                    <textarea id="clientIntentInput" required rows="3" placeholder="e.g. Liam calling regarding our meeting." 
                               class="w-full bg-gray-950 border border-gray-800 rounded-xl px-4 py-3 text-white text-sm focus:outline-none focus:ring-1 focus:ring-cyan-500 focus:border-cyan-500"></textarea>
                </div>
                <button type="submit" 
                        class="w-full bg-cyan-500 hover:bg-cyan-400 text-black font-bold py-3 px-4 rounded-xl shadow-lg transition transform hover:-translate-y-0.5 active:translate-y-0 text-sm">
                    Send Request
                </button>
            </form>
        </div>

        <!-- STEP 2: PENDING APPROVAL SPINNER -->
        <div id="pendingView" class="hidden text-center max-w-sm mx-auto p-8 bg-gray-900 border border-gray-800 rounded-3xl glow-ring">
            <div class="inline-flex items-center justify-center w-16 h-16 rounded-full bg-cyan-950 border border-cyan-800 text-cyan-400 font-extrabold text-2xl mb-4 animate-spin">
                ⏳
            </div>
            <h2 class="text-xl font-bold text-white mb-2">Waiting for Response</h2>
            <p id="pendingDesc" class="text-sm text-gray-400 leading-relaxed mb-6">
                Your request has been sent. Please stay on this page while we connect your device.
            </p>
            <div class="text-xs bg-gray-950 px-3 py-2 rounded-lg text-cyan-600 font-mono select-all hidden" id="uuidDisplay"></div>
            <button onclick="resetCredentials()" class="mt-4 text-xs text-red-400 hover:underline">Cancel Request</button>
        </div>

        <!-- STEP 3: MESSAGE & CALL PANEL -->
        <div id="provisionedView" class="hidden grid grid-cols-1 lg:grid-cols-12 gap-6 w-full h-[600px] md:h-[650px]">
            
            <!-- LEFT PANEL: Call Control -->
            <div class="lg:col-span-12 xl:col-span-5 flex flex-col space-y-4">
                
                <!-- Welcome status -->
                <div class="bg-gray-900 border border-gray-800 p-4 rounded-2xl">
                    <div class="flex justify-between items-center">
                        <div>
                            <span class="text-xs text-cyan-400 uppercase tracking-widest font-extrabold">Connected as</span>
                            <h3 class="text-lg font-bold text-white" id="approvedName">Guest</h3>
                        </div>
                        <button onclick="resetCredentials()" title="Logs Out of Session" 
                                class="text-xs text-gray-500 hover:text-red-400 border border-gray-800 hover:border-red-950 px-2.5 py-1 rounded-lg bg-gray-950 font-medium font-sans">
                            Disconnect
                        </button>
                    </div>
                </div>

                <!-- Call Control -->
                <div class="bg-gray-900 border border-gray-800 p-5 rounded-3xl flex-1 flex flex-col justify-between glow-cyan relative overflow-hidden">
                    <div class="space-y-4">
                        <div class="flex items-center justify-between">
                            <span class="text-xs font-bold text-gray-400 uppercase">Calling status</span>
                            <span class="px-2 py-0.5 rounded bg-cyan-950 text-cyan-400 text-[10px] font-sans" id="callDuration">READY TO CONNECT</span>
                        </div>
                        <div class="aspect-video bg-gray-950 rounded-2xl relative overflow-hidden flex items-center justify-center border border-gray-800">
                            <!-- Video Stream -->
                            <video id="localVideo" class="absolute inset-0 w-full h-full object-cover rounded-2xl hidden" autoplay muted playsinline></video>
                            <div class="absolute bottom-2 right-2 w-1/4 aspect-video bg-gray-900 rounded border border-cyan-800 hidden" id="remoteMini"></div>
                            
                            <div id="videoPlaceholder" class="text-center p-4">
                                <div class="text-3xl mb-1 text-gray-650">📞</div>
                                <p class="text-xs text-gray-500">Camera line closed</p>
                            </div>
                        </div>
                    </div>

                    <div class="pt-4 space-y-3">
                        <div class="flex space-x-2">
                            <button id="audioCallBtn" onclick="toggleCall(false)" 
                                    class="flex-1 bg-cyan-500 hover:bg-cyan-400 text-black py-3 rounded-xl font-bold text-xs flex items-center justify-center space-x-2 transition font-sans">
                                <span>📞</span> <span>Voice Call</span>
                            </button>
                            <button id="videoCallBtn" onclick="toggleCall(true)" 
                                    class="flex-1 bg-gradient-to-r from-blue-600 to-cyan-500 hover:from-blue-500 hover:to-cyan-400 text-white py-3 rounded-xl font-bold text-xs flex items-center justify-center space-x-2 transition font-sans">
                                <span>📷</span> <span>Video Call</span>
                            </button>
                        </div>
                        <button id="hangUpBtn" onclick="hangUpCall(false)" disabled 
                                class="w-full bg-red-600 hover:bg-red-500 disabled:opacity-50 disabled:cursor-not-allowed text-white py-3 rounded-xl font-bold text-xs flex items-center justify-center space-x-2 transition font-sans">
                            <span>🚫</span> <span>End Call</span>
                        </button>
                    </div>
                </div>
            </div>

            <!-- RIGHT PANEL: Chat History & Messaging -->
            <div class="lg:col-span-12 xl:col-span-7 flex flex-col bg-gray-900 border border-gray-800 rounded-3xl overflow-hidden h-full">
                <!-- Chat header -->
                <div class="bg-gray-900 border-b border-gray-800 p-4 flex justify-between items-center">
                    <span class="text-xs font-bold text-gray-400 uppercase">Direct Messages</span>
                    <button onclick="clearChatHistory()" class="text-xs text-gray-500 hover:text-white">Clear Chats</button>
                </div>

                <!-- Chat history boxes -->
                <div id="chatBox" class="flex-1 p-4 overflow-y-auto space-y-3 bg-gray-950 text-xs flex flex-col font-sans">
                    <div class="text-cyan-400 text-[10px] text-center bg-cyan-950/20 py-1.5 px-3 rounded-lg border border-cyan-900/40 mb-3 self-center font-sans">
                        ⚡ Connected. Real-time direct link is active.
                    </div>
                    <!-- Dynamically generated items -->
                </div>

                <!-- Messaging input -->
                <div class="p-3 bg-gray-900 border-t border-gray-800 space-y-2">
                    <div class="flex items-center space-x-2 bg-gray-950 p-2 rounded-xl border border-gray-800">
                        <button onclick="document.getElementById('fileInput').click()" 
                                class="w-9 h-9 rounded-lg bg-gray-900 hover:bg-gray-800 border border-gray-800 flex items-center justify-center text-sm" title="Share Photo or File">
                            📎
                        </button>
                        <input type="file" id="fileInput" class="hidden" onchange="sendFile(event)">
                        
                        <input type="text" id="chatInput" placeholder="Write a message..." 
                               onkeydown="if(event.key === 'Enter') submitMessage()"
                               class="flex-1 bg-transparent border-none text-white text-xs py-1.5 focus:outline-none">
                        
                        <button onclick="submitMessage()" 
                                class="bg-cyan-500 hover:bg-cyan-400 text-black font-extrabold w-11 h-8 rounded-lg flex items-center justify-center text-xs transition">
                            SEND
                        </button>
                    </div>
                    <div id="fileUploadProgress" class="hidden text-[10px] text-cyan-400 px-2 flex justify-between items-center">
                        <span id="fileNameDisplay">file.ext</span>
                        <span class="font-sans text-gray-400">sending...</span>
                    </div>
                </div>
            </div>

        </div>

    </main>

    <script>
        // CORE CONFIG & STATE
        const PLACEHOLDER_URL = "__WHISKER_HOME_SERVER_URL__";
        const HOME_SERVER_URL = (PLACEHOLDER_URL.startsWith("__") || !PLACEHOLDER_URL) ? window.location.origin : PLACEHOLDER_URL;

        let clientUuid = localStorage.getItem('dc_uuid') || '';
        let clientName = localStorage.getItem('dc_name') || '';
        let clientToken = localStorage.getItem('dc_token') || '';
        let chatLogs = JSON.parse(localStorage.getItem('dc_chat_history') || '[]');
        let isCallActive = false;
        let callTimer = null;
        let callSeconds = 0;
        let sseConnection = null;

        // E2E STATUS POLLING
        let e2eStatusInterval = null;
        function startE2eStatusPolling() {
            if (e2eStatusInterval) return;
            
            const pollStatus = () => {
                fetch(HOME_SERVER_URL + "/api/info")
                    .then(res => res.json())
                    .then(data => {
                        const dot = document.getElementById('statusDot');
                        const text = document.getElementById('statusText');
                        
                        if (data.host_reachable) {
                            dot.className = "w-2.5 h-2.5 rounded-full bg-green-500";
                            text.innerText = "Mobile App Reachable";
                        } else {
                            dot.className = "w-2.5 h-2.5 rounded-full bg-yellow-500";
                            text.innerText = "Mobile App Offline";
                        }
                    })
                    .catch(err => {
                        document.getElementById('statusDot').className = "w-2.5 h-2.5 rounded-full bg-red-500";
                        document.getElementById('statusText').innerText = "Home Server Unreachable";
                    });
            };
            
            pollStatus(); // initial poll
            e2eStatusInterval = setInterval(pollStatus, 5000);
        }

        // INITIALIZATION
        window.addEventListener('load', () => {
            if (window.WHISKER_INVITE_CODE) {
                document.getElementById('statusDot').className = "w-2.5 h-2.5 rounded-full bg-yellow-500 glow-ring";
                document.getElementById('statusText').innerText = "Claiming invite...";
                fetch(HOME_SERVER_URL + "/api/claim_invite", {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ code: window.WHISKER_INVITE_CODE })
                }).then(res => res.json()).then(data => {
                    if (data.error) {
                        alert("Invite error: " + data.error);
                        document.getElementById('statusDot').className = "w-2.5 h-2.5 rounded-full bg-red-500";
                        document.getElementById('statusText').innerText = "Invite Invalid";
                        showView('unprovisionedView');
                    } else {
                        clientUuid = data.uuid;
                        clientToken = data.token;
                        clientName = data.name;
                        localStorage.setItem('dc_uuid', clientUuid);
                        localStorage.setItem('dc_token', clientToken);
                        localStorage.setItem('dc_name', clientName);
                        localStorage.removeItem('dc_chat_history');
                        chatLogs = [];
                        window.history.replaceState({}, document.title, "/");
                        initSession();
                    }
                }).catch(err => {
                    console.error("Invite claim failed", err);
                    showView('unprovisionedView');
                });
                return;
            }
            initSession();
        });

        function initSession() {
            if (clientToken) {
                document.getElementById('statusDot').className = "w-2.5 h-2.5 rounded-full bg-green-500";
                document.getElementById('statusText').innerText = "Connected";
                showView('provisionedView');
                document.getElementById('approvedName').innerText = clientName;
                
                // Fetch server-side historical messages for approved sessions!
                fetchHistoryFromServer();
                startClientSse();
                startE2eStatusPolling();
            } else if (clientUuid) {
                document.getElementById('statusDot').className = "w-2.5 h-2.5 rounded-full bg-yellow-500";
                document.getElementById('statusText').innerText = "Awaiting response";
                document.getElementById('uuidDisplay').innerText = "Guest ID: " + clientUuid;
                showView('pendingView');
                startClientSse();
            } else {
                if (e2eStatusInterval) {
                    clearInterval(e2eStatusInterval);
                    e2eStatusInterval = null;
                }
                document.getElementById('statusDot').className = "w-2.5 h-2.5 rounded-full bg-red-500";
                document.getElementById('statusText').innerText = "Currently Offline";
                showView('unprovisionedView');
                if (sseConnection) {
                    sseConnection.close();
                    sseConnection = null;
                }
            }
        }

        function showView(viewId) {
            document.getElementById('unprovisionedView').classList.add('hidden');
            document.getElementById('pendingView').classList.add('hidden');
            document.getElementById('provisionedView').classList.add('hidden');
            document.getElementById(viewId).classList.remove('hidden');
        }

        // LOAD MESSAGE HISTORY FROM SERVER-SIDE SQLITE
        function fetchHistoryFromServer() {
            if (!clientUuid || !clientToken) return;
            const historyUrl = HOME_SERVER_URL + "/api/messages?uuid=" + clientUuid + "&token=" + clientToken;
            fetch(historyUrl)
                .then(res => {
                    if (!res.ok) throw new Error("Unauthorized message history call");
                    return res.json();
                })
                .then(history => {
                    chatLogs = history.map(item => ({
                        id: item.id || item.created_at || Date.now(),
                        text: item.text,
                        timestamp: item.created_at,
                        isIncoming: item.sender === 'phone',
                        filePath: item.file_path || null
                    }));
                    localStorage.setItem('dc_chat_history', JSON.stringify(chatLogs));
                    renderChat();
                })
                .catch(err => {
                    console.error("Error loading chat history:", err);
                    renderChat(); // Fallback to local logs
                });
        }

        // SSE STREAMING FOR REALTIME NOTIFICATIONS
        function startClientSse() {
            if (!clientUuid) return;
            if (sseConnection) return; // Already listening

            const sseUrl = HOME_SERVER_URL + "/api/events?uuid=" + clientUuid + "&token=" + (clientToken || '');
            console.log("Subscribing to response stream:", sseUrl);
            sseConnection = new EventSource(sseUrl);
            
            sseConnection.onmessage = (event) => {
                try {
                    const data = JSON.parse(event.data);
                    handleIncomingPayload(data);
                } catch (e) {
                    console.error("Error parsing SSE incoming message:", e);
                }
            };

            sseConnection.onerror = (e) => {
                console.error("SSE connection closed or lost. Stream automatically reconnects.");
            };
        }

        function handleIncomingPayload(payload) {
            console.log("Received payload:", payload);
            if (payload.type === "registration_response" || payload.event === "approved") {
                const token = payload.token;
                if (token) {
                    clientToken = token;
                    localStorage.setItem('dc_token', clientToken);
                    initSession();
                }
            } else if (payload.type === "chat_message" || payload.event === "message") {
                if (payload.sender === "phone") {
                    const newMsg = {
                        id: payload.timestamp || Date.now(),
                        text: payload.text,
                        timestamp: payload.timestamp || Date.now(),
                        isIncoming: true,
                        filePath: payload.filePath || null
                    };
                    chatLogs.push(newMsg);
                    localStorage.setItem('dc_chat_history', JSON.stringify(chatLogs));
                    renderChat();
                }
            } else if (payload.type === "webrtc_call_initiate") {
                document.getElementById('callDuration').innerText = "INCOMING CALL...";
                isCallActive = true;
                document.getElementById('audioCallBtn').disabled = true;
                document.getElementById('videoCallBtn').disabled = true;
                document.getElementById('hangUpBtn').disabled = false;
                
                // Stub answering
                setTimeout(() => {
                    document.getElementById('callDuration').innerText = "CALL ACTIVE";
                    startCallTimer();
                }, 1500);
            } else if (payload.type === "webrtc_call_answer" || payload.event === "call_answered") {
                document.getElementById('callDuration').innerText = "CALL ACTIVE";
                isCallActive = true;
                if (window.pc && payload.sdp) {
                    window.pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: payload.sdp }))
                        .catch(err => console.error("Error setting remote answer SDP", err));
                }
            } else if (payload.type === "webrtc_call_hangup" || payload.event === "call_ended") {
                hangUpCall(true);
            }
        }

        // SUBMIT ACCESS REQUEST
        const requestForm = document.getElementById('requestForm');
        requestForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const name = document.getElementById('clientNameInput').value.trim();
            const intent = document.getElementById('clientIntentInput').value.trim();
            if (!name || !intent) return;

            // Generate UUID
            clientUuid = 'cli_' + Math.random().toString(36).substr(2, 9);
            clientName = name;
            localStorage.setItem('dc_uuid', clientUuid);
            localStorage.setItem('dc_name', clientName);

            console.log("Posting registration request...", {clientUuid, clientName, intent});
            
            const payload = {
                event: "visitor",
                uuid: clientUuid,
                name: clientName,
                reason: intent
            };

            fetch(HOME_SERVER_URL + "/api/register", {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }).then(() => {
                console.log("Registration request launched.");
                initSession();
            }).catch(err => {
                console.error("Error submitting registration", err);
                initSession();
            });
        });

        // CHAT OPERATIONS
        function renderChat() {
            const chatBox = document.getElementById('chatBox');
            const banner = chatBox.firstElementChild;
            chatBox.innerHTML = '';
            if (banner) chatBox.appendChild(banner);

            chatLogs.forEach(msg => {
                const messageDiv = document.createElement('div');
                messageDiv.className = 'flex flex-col max-w-[80%] ' + (msg.isIncoming ? 'self-start' : 'self-end');
                
                const timeStr = new Date(msg.timestamp).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
                
                let textBody = '';
                if (msg.filePath) {
                    textBody = '<div class="bg-cyan-950 border border-cyan-800 p-2.5 rounded-xl flex items-center space-x-2 text-cyan-300">' +
                                   '<span class="text-lg">📎</span>' +
                                   '<div>' +
                                       '<p class="font-bold underline cursor-pointer hover:text-white" onclick="downloadAttachment(' + JSON.stringify(msg.text) + ', ' + JSON.stringify(msg.filePath) + ')">' + msg.text + '</p>' +
                                   '</div>' +
                               '</div>';
                } else {
                    textBody = '<div class="p-2.5 rounded-xl ' + (msg.isIncoming ? 'bg-gray-900 text-gray-200 border border-gray-800' : 'bg-cyan-500 text-black font-medium') + '">' +
                                   msg.text +
                               '</div>';
                }

                messageDiv.innerHTML = 
                    '<span class="text-[9px] text-gray-500 mb-0.5 px-1 ' + (msg.isIncoming ? 'text-left' : 'text-right') + '">' + (msg.isIncoming ? 'Phone' : 'You') + ' • ' + timeStr + '</span>' +
                    textBody;
                chatBox.appendChild(messageDiv);
            });
            chatBox.scrollTop = chatBox.scrollHeight;
        }

        function downloadAttachment(name, path) {
            const link = document.createElement('a');
            link.href = path;
            link.download = name;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        }

        // ... truncated operations ...
        function submitMessage() {
            const chatInput = document.getElementById('chatInput');
            const text = chatInput.value.trim();
            if (!text) return;

            const newMsg = {
                id: Date.now(),
                text: text,
                timestamp: Date.now(),
                isIncoming: false,
                filePath: null
            };
            chatLogs.push(newMsg);
            localStorage.setItem('dc_chat_history', JSON.stringify(chatLogs));
            renderChat();
            chatInput.value = '';

            console.log("Transmitting message to Home Server...", text);

            const payload = {
                event: "message",
                uuid: clientUuid,
                token: clientToken,
                name: clientName,
                text: text,
                file: null
            };

            fetch(HOME_SERVER_URL + "/api/message", {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }).then(() => {
                console.log("Message delivered.");
            }).catch(e => {
                console.error("Delivery failed", e);
            });
        }

        function sendFile(event) {
            const file = event.target.files[0];
            if (!file) return;

            const progress = document.getElementById('fileUploadProgress');
            const fileNameDisplay = document.getElementById('fileNameDisplay');
            progress.classList.remove('hidden');
            fileNameDisplay.innerText = file.name;

            const reader = new FileReader();
            reader.onload = function(e) {
                const base64Data = e.target.result;
                const fileMsg = {
                    id: Date.now(),
                    text: file.name,
                    timestamp: Date.now(),
                    isIncoming: false,
                    filePath: base64Data
                };
                
                chatLogs.push(fileMsg);
                localStorage.setItem('dc_chat_history', JSON.stringify(chatLogs));
                renderChat();

                const payload = {
                    event: "message",
                    uuid: clientUuid,
                    token: clientToken,
                    name: clientName,
                    text: file.name,
                    file: base64Data
                };

                fetch(HOME_SERVER_URL + "/api/message", {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                }).then(() => {
                    progress.classList.add('hidden');
                    console.log("File uploaded successfully.");
                }).catch(err => {
                    progress.classList.add('hidden');
                    console.error("File upload failed", err);
                });
            };
            reader.readAsDataURL(file);
        }

        function clearChatHistory() {
            chatLogs = [];
            localStorage.setItem('dc_chat_history', '[]');
            renderChat();
        }

        // WEBRTC CALL OPERATIONS
        async function toggleCall(isVideo) {
            isCallActive = true;
            document.getElementById('audioCallBtn').disabled = true;
            document.getElementById('videoCallBtn').disabled = true;
            document.getElementById('hangUpBtn').disabled = false;
            
            document.getElementById('localVideo').classList.remove('hidden');
            document.getElementById('videoPlaceholder').classList.add('hidden');
            document.getElementById('callDuration').innerText = "ROUTING CALL SIGNAL...";
            
            let localStream = null;
            try {
                localStream = await navigator.mediaDevices.getUserMedia({ video: isVideo, audio: true });
                const localVid = document.getElementById('localVideo');
                localVid.srcObject = localStream;
            } catch (err) {
                console.error("Media devices capture error", err);
            }

            const pc = new RTCPeerConnection({
                iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
            });
            window.pc = pc;

            if (localStream) {
                localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
            }

            pc.ontrack = (event) => {
                const remoteVid = document.getElementById('remoteMini');
                remoteVid.classList.remove('hidden');
            };

            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);

            const payload = {
                event: "call",
                uuid: clientUuid,
                token: clientToken,
                name: clientName,
                video: isVideo,
                sdp: offer.sdp
            };

            fetch(HOME_SERVER_URL + "/api/call", {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }).then(() => {
                console.log("Call handshaking.");
            }).catch(e => {
                console.error("Dial signal failure", e);
            });

            callSeconds = 0;
            if (callTimer) clearInterval(callTimer);
            callTimer = setInterval(() => {
                callSeconds++;
                const mins = Math.floor(callSeconds / 60).toString().padStart(2, '0');
                const secs = (callSeconds % 60).toString().padStart(2, '0');
                document.getElementById('callDuration').innerText = "CALL ALIVE • " + mins + ":" + secs;
            }, 1000);
        }

        function hangUpCall(receivedFromPhone = false) {
            isCallActive = false;
            if (callTimer) clearInterval(callTimer);
            document.getElementById('audioCallBtn').disabled = false;
            document.getElementById('videoCallBtn').disabled = false;
            document.getElementById('hangUpBtn').disabled = true;
            
            document.getElementById('localVideo').classList.add('hidden');
            const localVid = document.getElementById('localVideo');
            if (localVid.srcObject) {
                localVid.srcObject.getTracks().forEach(track => track.stop());
                localVid.srcObject = null;
            }
            if (window.pc) {
                window.pc.close();
                window.pc = null;
            }
            document.getElementById('videoPlaceholder').classList.remove('hidden');
            document.getElementById('callDuration').innerText = "CALL ENDED";

            if (!receivedFromPhone) {
                const payload = {
                    event: "hangup",
                    uuid: clientUuid,
                    token: clientToken
                };
                fetch(HOME_SERVER_URL + "/api/hangup", {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                }).catch(e => console.error("Hangup signal error", e));
            }
        }

        function resetCredentials() {
            if (confirm("Disconnect session? History will clean.")) {
                if (sseConnection) {
                    sseConnection.close();
                    sseConnection = null;
                }
                localStorage.clear();
                clientUuid = '';
                clientName = '';
                clientToken = '';
                chatLogs = [];
                initSession();
            }
        }
    </script>
</body>
</html>`;

// GET COMPILER STRING COMPANION CLIENT
function getEmbeddedCompanionHtml() {
    return GUEST_CLIENT_HTML.replace("__WHISKER_HOME_SERVER_URL__", "");
}
