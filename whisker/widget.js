/**
 * Whisker Embeddable Web Client Widget
 * Usage: <script src="http://<server-ip>:3000/widget.js"></script>
 *
 * Supports:
 * - Multi-session tabs for contacting different mobile apps on the home server
 * - Mobile app host selection via radio buttons
 * - Custom username per session (remembered across sessions)
 * - Real-time chat messaging and message history restoration
 * - Two-way WebRTC audio and video calling
 * - Seamless session restoration across browser visits (localStorage)
 * - Transparent host approval flow with responsive desktop/mobile UI
 */
(function() {
  if (window.__WHISKER_WIDGET_LOADED__) return;
  window.__WHISKER_WIDGET_LOADED__ = true;

  // Resolve Home Server base URL
  const scriptTag = document.currentScript;
  let serverUrl = '';
  if (scriptTag) {
    serverUrl = scriptTag.getAttribute('data-server') || '';
    if (!serverUrl && scriptTag.src) {
      try {
        serverUrl = new URL(scriptTag.src).origin;
      } catch (e) {}
    }
  }
  if (!serverUrl) {
    serverUrl = window.location.origin;
  }
  serverUrl = serverUrl.replace(/\/+$/, '');

  // Storage keys
  const STORAGE_SESSIONS = 'whisker_embed_sessions_v2';
  const STORAGE_ACTIVE_ID = 'whisker_embed_active_session_id';
  const STORAGE_LAST_USERNAME = 'whisker_embed_last_username';

  // Legacy single-session keys for backward-compatible migration
  const LEGACY_KEY_UUID = 'whisker_embed_uuid';
  const LEGACY_KEY_NAME = 'whisker_embed_name';
  const LEGACY_KEY_TOKEN = 'whisker_embed_token';
  const LEGACY_KEY_STATUS = 'whisker_embed_status';
  const LEGACY_KEY_MSGS = 'whisker_embed_messages';

  // Sessions State
  let sessions = [];
  try {
    const rawSessions = localStorage.getItem(STORAGE_SESSIONS);
    if (rawSessions) {
      sessions = JSON.parse(rawSessions);
    }
  } catch (e) {
    sessions = [];
  }

  // Migrate legacy session if present and no v2 sessions
  if ((!sessions || sessions.length === 0) && localStorage.getItem(LEGACY_KEY_UUID)) {
    const oldUuid = localStorage.getItem(LEGACY_KEY_UUID);
    const oldName = localStorage.getItem(LEGACY_KEY_NAME) || 'Guest';
    const oldToken = localStorage.getItem(LEGACY_KEY_TOKEN) || '';
    const oldStatus = localStorage.getItem(LEGACY_KEY_STATUS) || 'PENDING';
    let oldMsgs = [];
    try {
      oldMsgs = JSON.parse(localStorage.getItem(LEGACY_KEY_MSGS) || '[]');
    } catch (e) {}

    const migratedSession = {
      id: 'sess_' + oldUuid,
      client_uuid: oldUuid,
      client_name: oldName,
      client_token: oldToken,
      client_status: oldStatus,
      target_host_uuid: '',
      target_host_name: 'Primary Mobile',
      is_host_online: false,
      messages: oldMsgs
    };
    sessions = [migratedSession];
    saveSessions();
    if (!localStorage.getItem(STORAGE_LAST_USERNAME)) {
      localStorage.setItem(STORAGE_LAST_USERNAME, oldName);
    }
  }

  let activeSessionId = localStorage.getItem(STORAGE_ACTIVE_ID) || (sessions[0] ? sessions[0].id : null);
  if (sessions.length > 0 && !sessions.some(s => s.id === activeSessionId)) {
    activeSessionId = sessions[0].id;
  }

  let isNewSessionMode = (sessions.length === 0);
  let isOpen = false;
  let unreadCount = 0;
  let serverName = 'Whisker Support';
  let sseSource = null;
  let pollInterval = null;

  // WebRTC Call State
  let pc = null;
  let localStream = null;
  let currentCallId = null;
  let callTimerInterval = null;
  let callStartTime = null;
  let isMicMuted = false;
  let isVideoMuted = false;
  let isInCall = false;
  let isCallDialing = false;
  let pendingIncomingCall = null;
  let ringAudioContext = null;
  let ringOscillator = null;
  let ringInterval = null;

  const rtcConfig = {
    iceServers: [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' }
    ]
  };

  function getActiveSession() {
    return sessions.find(s => s.id === activeSessionId) || null;
  }

  function saveSessions() {
    try {
      localStorage.setItem(STORAGE_SESSIONS, JSON.stringify(sessions));
      if (activeSessionId) {
        localStorage.setItem(STORAGE_ACTIVE_ID, activeSessionId);
      }
    } catch (e) {}
  }

  // Inject CSS Styles
  const styleEl = document.createElement('style');
  styleEl.id = 'whisker-widget-styles';
  styleEl.textContent = `
    #whisker-launcher {
      position: fixed;
      bottom: 24px;
      right: 24px;
      width: 60px;
      height: 60px;
      border-radius: 50%;
      background: linear-gradient(135deg, #6366f1 0%, #a855f7 100%);
      box-shadow: 0 4px 20px rgba(99, 102, 241, 0.45), 0 2px 8px rgba(0, 0, 0, 0.2);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 2147483645;
      transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.25s ease;
      user-select: none;
      -webkit-tap-highlight-color: transparent;
    }
    #whisker-launcher:hover {
      transform: scale(1.08);
      box-shadow: 0 6px 24px rgba(99, 102, 241, 0.6);
    }
    #whisker-launcher:active {
      transform: scale(0.95);
    }
    #whisker-badge {
      position: absolute;
      top: -3px;
      right: -3px;
      background: #ef4444;
      color: #ffffff;
      font-size: 11px;
      font-weight: 700;
      padding: 2px 7px;
      border-radius: 12px;
      border: 2px solid #0f172a;
      display: none;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    }
    #whisker-panel {
      position: fixed;
      bottom: 96px;
      right: 24px;
      width: 390px;
      height: 590px;
      max-height: calc(100vh - 120px);
      background: #0f172a;
      color: #f8fafc;
      border: 1px solid #334155;
      border-radius: 16px;
      box-shadow: 0 16px 48px rgba(0, 0, 0, 0.5), 0 0 0 1px rgba(255, 255, 255, 0.05);
      display: flex;
      flex-direction: column;
      overflow: hidden;
      z-index: 2147483646;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      opacity: 0;
      transform: translateY(20px) scale(0.96);
      pointer-events: none;
      transition: opacity 0.25s ease, transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
    }
    #whisker-panel.whisker-open {
      opacity: 1;
      transform: translateY(0) scale(1);
      pointer-events: auto;
    }
    @media (max-width: 600px) {
      #whisker-launcher {
        bottom: 16px;
        right: 16px;
        width: 54px;
        height: 54px;
      }
      #whisker-panel {
        bottom: 0 !important;
        left: 0 !important;
        right: 0 !important;
        width: 100% !important;
        height: 85vh !important;
        max-height: 85vh !important;
        border-radius: 20px 20px 0 0 !important;
        border-bottom: none !important;
      }
    }
    .whisker-header {
      background: #1e293b;
      padding: 12px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid #334155;
      flex-shrink: 0;
    }
    .whisker-header-info {
      display: flex;
      align-items: center;
      gap: 10px;
      min-width: 0;
    }
    .whisker-avatar {
      width: 36px;
      height: 36px;
      border-radius: 10px;
      background: linear-gradient(135deg, #6366f1, #8b5cf6);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 18px;
      flex-shrink: 0;
    }
    .whisker-header-text {
      min-width: 0;
    }
    .whisker-title {
      font-size: 14px;
      font-weight: 700;
      color: #ffffff;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .whisker-subtitle {
      font-size: 11px;
      color: #94a3b8;
      display: flex;
      align-items: center;
      gap: 5px;
      margin-top: 1px;
    }
    .whisker-status-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: #64748b;
      display: inline-block;
    }
    .whisker-status-dot.whisker-online {
      background: #10b981;
      box-shadow: 0 0 6px #10b981;
    }
    .whisker-actions {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .whisker-icon-btn {
      background: transparent;
      border: none;
      color: #94a3b8;
      width: 32px;
      height: 32px;
      border-radius: 8px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background 0.15s, color 0.15s;
    }
    .whisker-icon-btn:hover {
      background: #334155;
      color: #ffffff;
    }
    .whisker-call-btn {
      color: #38bdf8;
    }
    .whisker-call-btn:hover {
      background: rgba(56, 189, 248, 0.15);
      color: #7dd3fc;
    }
    /* Tab Bar Styles */
    .whisker-tab-bar {
      background: #1e293b;
      border-bottom: 1px solid #334155;
      padding: 6px 12px;
      display: flex;
      align-items: center;
      gap: 6px;
      overflow-x: auto;
      scrollbar-width: none;
      flex-shrink: 0;
    }
    .whisker-tab-bar::-webkit-scrollbar {
      display: none;
    }
    .whisker-tab-pill {
      background: #0f172a;
      color: #94a3b8;
      border: 1px solid #334155;
      padding: 5px 10px;
      border-radius: 20px;
      font-size: 11px;
      font-weight: 500;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      white-space: nowrap;
      transition: all 0.15s ease;
      user-select: none;
    }
    .whisker-tab-pill:hover {
      background: #334155;
      color: #ffffff;
    }
    .whisker-tab-pill.whisker-tab-active {
      background: #6366f1;
      color: #ffffff;
      border-color: #818cf8;
      font-weight: 600;
      box-shadow: 0 2px 8px rgba(99, 102, 241, 0.4);
    }
    .whisker-tab-close {
      font-size: 13px;
      opacity: 0.6;
      line-height: 1;
      cursor: pointer;
      border-radius: 50%;
      padding: 1px 3px;
    }
    .whisker-tab-close:hover {
      opacity: 1;
      background: rgba(0, 0, 0, 0.2);
    }
    .whisker-tab-add {
      background: #0f172a;
      color: #38bdf8;
      border: 1px dashed #38bdf8;
      width: 26px;
      height: 26px;
      border-radius: 50%;
      font-size: 15px;
      font-weight: 700;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      transition: all 0.15s;
    }
    .whisker-tab-add:hover {
      background: rgba(56, 189, 248, 0.2);
      transform: scale(1.08);
    }
    .whisker-body {
      flex: 1;
      overflow-y: auto;
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      background: #0f172a;
    }
    /* New Session / Device Picker Form */
    .whisker-picker-box {
      margin: auto;
      padding: 20px 16px;
      background: #1e293b;
      border-radius: 14px;
      border: 1px solid #334155;
      width: 100%;
      max-width: 340px;
      box-sizing: border-box;
    }
    .whisker-picker-box h3 {
      margin: 0 0 6px;
      font-size: 15px;
      font-weight: 700;
      color: #ffffff;
      text-align: center;
    }
    .whisker-picker-box p {
      font-size: 12px;
      color: #94a3b8;
      margin: 0 0 14px;
      line-height: 1.4;
      text-align: center;
    }
    .whisker-section-label {
      font-size: 11px;
      font-weight: 600;
      color: #cbd5e1;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-bottom: 8px;
      display: block;
    }
    .whisker-radio-list {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-bottom: 16px;
      max-height: 180px;
      overflow-y: auto;
      padding-right: 2px;
    }
    .whisker-radio-card {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 12px;
      background: #0f172a;
      border: 1px solid #334155;
      border-radius: 10px;
      cursor: pointer;
      transition: border-color 0.15s, background 0.15s;
    }
    .whisker-radio-card:hover {
      border-color: #6366f1;
      background: rgba(99, 102, 241, 0.05);
    }
    .whisker-radio-card input[type="radio"] {
      appearance: none;
      -webkit-appearance: none;
      width: 16px;
      height: 16px;
      border: 2px solid #64748b;
      border-radius: 50%;
      outline: none;
      cursor: pointer;
      margin: 0;
      position: relative;
      flex-shrink: 0;
    }
    .whisker-radio-card input[type="radio"]:checked {
      border-color: #6366f1;
      background: #6366f1;
    }
    .whisker-radio-card input[type="radio"]:checked::after {
      content: '';
      position: absolute;
      top: 3px;
      left: 3px;
      width: 6px;
      height: 6px;
      background: #ffffff;
      border-radius: 50%;
    }
    .whisker-radio-card.whisker-radio-selected {
      border-color: #6366f1;
      background: rgba(99, 102, 241, 0.1);
    }
    .whisker-radio-text {
      flex: 1;
      min-width: 0;
    }
    .whisker-radio-title {
      font-size: 13px;
      font-weight: 600;
      color: #ffffff;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .whisker-admin-pill {
      font-size: 9px;
      background: #3b82f6;
      color: #ffffff;
      padding: 1px 5px;
      border-radius: 8px;
      font-weight: 700;
      text-transform: uppercase;
    }
    .whisker-companion-pill {
      font-size: 9px;
      background: #64748b;
      color: #ffffff;
      padding: 1px 5px;
      border-radius: 8px;
      font-weight: 700;
      text-transform: uppercase;
    }
    .whisker-input-field {
      width: 100%;
      padding: 10px 12px;
      background: #0f172a;
      border: 1px solid #475569;
      border-radius: 8px;
      color: #ffffff;
      font-size: 13px;
      box-sizing: border-box;
      outline: none;
      margin-bottom: 14px;
    }
    .whisker-input-field:focus {
      border-color: #6366f1;
    }
    .whisker-btn-row {
      display: flex;
      gap: 8px;
    }
    .whisker-primary-btn {
      flex: 1;
      padding: 10px;
      background: linear-gradient(135deg, #6366f1, #8b5cf6);
      border: none;
      border-radius: 8px;
      color: white;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      transition: opacity 0.15s, transform 0.1s;
    }
    .whisker-primary-btn:hover {
      opacity: 0.92;
      transform: translateY(-1px);
    }
    .whisker-secondary-btn {
      padding: 10px 14px;
      background: #334155;
      border: none;
      border-radius: 8px;
      color: #cbd5e1;
      font-size: 13px;
      cursor: pointer;
      transition: background 0.15s;
    }
    .whisker-secondary-btn:hover {
      background: #475569;
      color: #ffffff;
    }
    .whisker-msg {
      max-width: 82%;
      padding: 9px 13px;
      border-radius: 14px;
      font-size: 13px;
      line-height: 1.45;
      word-break: break-word;
      animation: whiskerFadeIn 0.2s ease-out;
    }
    @keyframes whiskerFadeIn {
      from { opacity: 0; transform: translateY(6px); }
      to { opacity: 1; transform: translateY(0); }
    }
    .whisker-msg-sent {
      align-self: flex-end;
      background: linear-gradient(135deg, #6366f1, #4f46e5);
      color: #ffffff;
      border-bottom-right-radius: 4px;
    }
    .whisker-msg-received {
      align-self: flex-start;
      background: #1e293b;
      color: #e2e8f0;
      border: 1px solid #334155;
      border-bottom-left-radius: 4px;
    }
    .whisker-msg-time {
      font-size: 10px;
      opacity: 0.7;
      margin-top: 4px;
      text-align: right;
    }
    .whisker-footer {
      background: #1e293b;
      padding: 10px 12px;
      display: flex;
      align-items: center;
      gap: 8px;
      border-top: 1px solid #334155;
      flex-shrink: 0;
    }
    .whisker-chat-input {
      flex: 1;
      background: #0f172a;
      border: 1px solid #334155;
      border-radius: 20px;
      padding: 8px 14px;
      color: #ffffff;
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s;
    }
    .whisker-chat-input:focus {
      border-color: #6366f1;
    }
    .whisker-send-btn {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      background: #6366f1;
      color: white;
      border: none;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background 0.15s, transform 0.1s;
      flex-shrink: 0;
    }
    .whisker-send-btn:hover {
      background: #4f46e5;
      transform: scale(1.05);
    }
    /* WebRTC Call Overlay */
    #whisker-call-overlay {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: #0b0f19;
      z-index: 10;
      display: none;
      flex-direction: column;
      align-items: center;
      justify-content: space-between;
      padding: 16px;
      box-sizing: border-box;
    }
    .whisker-call-video-container {
      position: relative;
      width: 100%;
      flex: 1;
      background: #000;
      border-radius: 12px;
      overflow: hidden;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .whisker-remote-video {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
    .whisker-local-video {
      position: absolute;
      top: 10px;
      right: 10px;
      width: 90px;
      height: 120px;
      border-radius: 8px;
      object-fit: cover;
      border: 2px solid #334155;
      background: #1e293b;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);
    }
    .whisker-call-status-box {
      position: absolute;
      top: 12px;
      left: 12px;
      background: rgba(15, 23, 42, 0.75);
      backdrop-filter: blur(8px);
      padding: 5px 10px;
      border-radius: 20px;
      font-size: 11px;
      color: #f8fafc;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .whisker-call-controls {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 16px;
      margin-top: 14px;
      width: 100%;
    }
    .whisker-call-btn-circle {
      width: 48px;
      height: 48px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      border: none;
      font-size: 20px;
      transition: transform 0.15s;
    }
    .whisker-call-btn-circle:hover {
      transform: scale(1.1);
    }
    .whisker-control-btn {
      background: #334155;
      color: #ffffff;
    }
    .whisker-control-btn.active {
      background: #ef4444;
    }
    .whisker-hangup-btn {
      background: #ef4444;
      color: white;
    }
    /* Incoming Call Modal Overlay */
    #whisker-incoming-dialog {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(15, 23, 42, 0.94);
      backdrop-filter: blur(12px);
      z-index: 10000;
      display: none;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 24px;
      text-align: center;
      box-sizing: border-box;
      border-radius: 18px;
    }
    .whisker-incoming-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 10px;
      width: 100%;
    }
    .whisker-incoming-avatar {
      width: 68px;
      height: 68px;
      border-radius: 50%;
      background: linear-gradient(135deg, #6366f1, #a855f7);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 30px;
      animation: whiskerRingingPulse 1.4s infinite;
      margin-bottom: 6px;
    }
    @keyframes whiskerRingingPulse {
      0% {
        box-shadow: 0 0 0 0 rgba(99, 102, 241, 0.7);
        transform: scale(1);
      }
      50% {
        box-shadow: 0 0 0 16px rgba(99, 102, 241, 0);
        transform: scale(1.06);
      }
      100% {
        box-shadow: 0 0 0 0 rgba(99, 102, 241, 0);
        transform: scale(1);
      }
    }
    .whisker-incoming-title {
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.1em;
      color: #818cf8;
    }
    .whisker-incoming-caller {
      font-size: 18px;
      font-weight: 700;
      color: #ffffff;
      margin: 0;
      word-break: break-word;
    }
    .whisker-incoming-type {
      font-size: 12px;
      color: #94a3b8;
    }
    .whisker-incoming-actions {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 36px;
      margin-top: 24px;
      width: 100%;
    }
    .whisker-incoming-btn {
      width: 54px;
      height: 54px;
      border-radius: 50%;
      border: none;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: transform 0.15s, box-shadow 0.15s;
    }
    .whisker-incoming-btn:hover {
      transform: scale(1.1);
    }
    .whisker-incoming-btn:active {
      transform: scale(0.95);
    }
    .whisker-incoming-btn-label {
      font-size: 11px;
      font-weight: 500;
      color: #cbd5e1;
      margin-top: 6px;
    }
    .whisker-incoming-accept-btn {
      background: #22c55e;
      color: white;
      box-shadow: 0 4px 14px rgba(34, 197, 94, 0.4);
    }
    .whisker-incoming-decline-btn {
      background: #ef4444;
      color: white;
      box-shadow: 0 4px 14px rgba(239, 68, 68, 0.4);
    }
    .whisker-action-col {
      display: flex;
      flex-direction: column;
      align-items: center;
    }
  `;
  document.head.appendChild(styleEl);

  // Inject Widget HTML Structure
  const root = document.createElement('div');
  root.id = 'whisker-widget-root';
  root.innerHTML = `
    <!-- Floating Circular Launcher Button -->
    <div id="whisker-launcher" title="Support Chat & Calling">
      <div id="whisker-launcher-icon">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ffffff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
        </svg>
      </div>
      <div id="whisker-close-icon" style="display: none;">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#ffffff" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <line x1="18" y1="6" x2="6" y2="18"></line>
          <line x1="6" y1="6" x2="18" y2="18"></line>
        </svg>
      </div>
      <div id="whisker-badge">0</div>
    </div>

    <!-- Floating Chat & Call Window Panel -->
    <div id="whisker-panel">
      <!-- Header -->
      <div class="whisker-header">
        <div class="whisker-header-info">
          <div class="whisker-avatar">🐱</div>
          <div class="whisker-header-text">
            <div class="whisker-title" id="whisker-server-title">Whisker Support</div>
            <div class="whisker-subtitle">
              <span class="whisker-status-dot" id="whisker-status-dot"></span>
              <span id="whisker-status-text">Support</span>
            </div>
          </div>
        </div>
        <div class="whisker-actions">
          <button class="whisker-icon-btn whisker-call-btn" id="whisker-btn-call" title="Start WebRTC Call" style="opacity: 0.5; pointer-events: none;">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path>
            </svg>
          </button>
          <button class="whisker-icon-btn" id="whisker-btn-minimize" title="Minimize">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
      </div>

      <!-- Tab Bar for Multi-Device Sessions -->
      <div class="whisker-tab-bar" id="whisker-tab-bar">
        <div id="whisker-tabs-list" style="display: flex; gap: 6px; align-items: center; min-width: 0; overflow-x: auto; scrollbar-width: none;"></div>
        <button class="whisker-tab-add" id="whisker-btn-add-session" title="Contact another mobile app">+</button>
      </div>

      <!-- Body / Messages -->
      <div class="whisker-body" id="whisker-body">
        <!-- New Session Form / Host Selection Screen -->
        <div class="whisker-picker-box" id="whisker-picker-box" style="display: none;">
          <div style="font-size: 28px; text-align: center; margin-bottom: 6px;">📱</div>
          <h3>Select Mobile Device</h3>
          <p>Choose which mobile app on this home server to contact:</p>
          
          <label class="whisker-section-label">Available Mobile Apps</label>
          <div class="whisker-radio-list" id="whisker-hosts-radio-list">
            <div style="text-align: center; color: #94a3b8; font-size: 12px; padding: 12px;">Loading available devices...</div>
          </div>

          <label class="whisker-section-label">Your Name</label>
          <input type="text" id="whisker-new-name-input" class="whisker-input-field" placeholder="Enter your name (e.g. Alex)" maxlength="32">

          <div class="whisker-btn-row">
            <button class="whisker-primary-btn" id="whisker-start-session-btn">Start Chat</button>
            <button class="whisker-secondary-btn" id="whisker-cancel-new-btn" style="display: none;">Cancel</button>
          </div>
        </div>

        <!-- Chat message thread -->
        <div id="whisker-messages" style="display: flex; flex-direction: column; gap: 10px; width: 100%;"></div>
      </div>

      <!-- Footer / Input -->
      <div class="whisker-footer" id="whisker-footer">
        <input type="file" id="whisker-file-input" style="display:none;" />
        <button id="whisker-btn-attach" class="whisker-attach-btn" title="Attach file or photo" style="background:transparent;border:none;cursor:pointer;color:#94a3b8;padding:6px 8px;display:flex;align-items:center;justify-content:center;transition:color 0.2s;">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"></path>
          </svg>
        </button>
        <input type="text" id="whisker-input" class="whisker-chat-input" placeholder="Type a message or drop file..." maxlength="1000">
        <button id="whisker-send" class="whisker-send-btn" title="Send message">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <line x1="22" y1="2" x2="11" y2="13"></line>
            <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
          </svg>
        </button>
      </div>

      <!-- WebRTC Call Overlay -->
      <div id="whisker-call-overlay">
        <div class="whisker-call-video-container">
          <video id="whisker-remote-video" class="whisker-remote-video" autoplay playsinline></video>
          <video id="whisker-local-video" class="whisker-local-video" autoplay playsinline muted></video>
          <div class="whisker-call-status-box">
            <span class="whisker-status-dot whisker-online" style="animation: pulse 1s infinite;"></span>
            <span id="whisker-call-status-label">Dialing mobile app...</span>
          </div>
        </div>
        <div class="whisker-call-controls">
          <button class="whisker-call-btn-circle whisker-control-btn" id="whisker-call-toggle-mic" title="Mute Microphone">🎤</button>
          <button class="whisker-call-btn-circle whisker-control-btn" id="whisker-call-toggle-video" title="Toggle Video">📹</button>
          <button class="whisker-call-btn-circle whisker-hangup-btn" id="whisker-call-hangup" title="End Call">📞</button>
        </div>
      </div>

      <!-- Incoming Call Dialog Overlay -->
      <div id="whisker-incoming-dialog">
        <div class="whisker-incoming-box">
          <div class="whisker-incoming-avatar">📞</div>
          <div class="whisker-incoming-title">Incoming Call</div>
          <div class="whisker-incoming-caller" id="whisker-incoming-caller-name">Mobile App</div>
          <div class="whisker-incoming-type" id="whisker-incoming-type-label">WebRTC Audio / Video Call</div>
          <div class="whisker-incoming-actions">
            <div class="whisker-action-col">
              <button class="whisker-incoming-btn whisker-incoming-decline-btn" id="whisker-incoming-decline" title="Decline Call">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91"></path>
                  <line x1="23" y1="1" x2="1" y2="23"></line>
                </svg>
              </button>
              <span class="whisker-incoming-btn-label">Decline</span>
            </div>
            <div class="whisker-action-col">
              <button class="whisker-incoming-btn whisker-incoming-accept-btn" id="whisker-incoming-accept" title="Accept Call">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path>
                </svg>
              </button>
              <span class="whisker-incoming-btn-label">Accept</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  `;
  document.body.appendChild(root);

  // DOM Elements
  const launcher = document.getElementById('whisker-launcher');
  const panel = document.getElementById('whisker-panel');
  const launcherIcon = document.getElementById('whisker-launcher-icon');
  const closeIcon = document.getElementById('whisker-close-icon');
  const badge = document.getElementById('whisker-badge');
  const btnMinimize = document.getElementById('whisker-btn-minimize');
  const btnCall = document.getElementById('whisker-btn-call');
  const titleEl = document.getElementById('whisker-server-title');
  const statusDot = document.getElementById('whisker-status-dot');
  const statusText = document.getElementById('whisker-status-text');

  const tabBar = document.getElementById('whisker-tab-bar');
  const tabsList = document.getElementById('whisker-tabs-list');
  const btnAddSession = document.getElementById('whisker-btn-add-session');

  const pickerBox = document.getElementById('whisker-picker-box');
  const hostsRadioList = document.getElementById('whisker-hosts-radio-list');
  const newNameInput = document.getElementById('whisker-new-name-input');
  const btnStartSession = document.getElementById('whisker-start-session-btn');
  const btnCancelNew = document.getElementById('whisker-cancel-new-btn');

  const messagesContainer = document.getElementById('whisker-messages');
  const bodyContainer = document.getElementById('whisker-body');
  const chatInput = document.getElementById('whisker-input');
  const btnSend = document.getElementById('whisker-send');
  const btnAttach = document.getElementById('whisker-btn-attach');
  const fileInput = document.getElementById('whisker-file-input');
  const footer = document.getElementById('whisker-footer');

  const callOverlay = document.getElementById('whisker-call-overlay');
  const remoteVideo = document.getElementById('whisker-remote-video');
  const localVideo = document.getElementById('whisker-local-video');
  const callStatusLabel = document.getElementById('whisker-call-status-label');
  const btnToggleMic = document.getElementById('whisker-call-toggle-mic');
  const btnToggleVideo = document.getElementById('whisker-call-toggle-video');
  const btnHangup = document.getElementById('whisker-call-hangup');

  const incomingDialog = document.getElementById('whisker-incoming-dialog');
  const incomingCallerName = document.getElementById('whisker-incoming-caller-name');
  const incomingTypeLabel = document.getElementById('whisker-incoming-type-label');
  const btnIncomingAccept = document.getElementById('whisker-incoming-accept');
  const btnIncomingDecline = document.getElementById('whisker-incoming-decline');

  // Open/Close toggle
  launcher.addEventListener('click', toggleWidget);
  btnMinimize.addEventListener('click', closeWidget);

  function toggleWidget() {
    if (isOpen) {
      closeWidget();
    } else {
      openWidget();
    }
  }

  function openWidget() {
    isOpen = true;
    panel.classList.add('whisker-open');
    launcherIcon.style.display = 'none';
    closeIcon.style.display = 'block';
    unreadCount = 0;
    updateBadge();
    renderTabs();
    if (isNewSessionMode || sessions.length === 0) {
      showNewSessionForm();
    } else {
      showActiveChat();
    }
  }

  function closeWidget() {
    isOpen = false;
    panel.classList.remove('whisker-open');
    launcherIcon.style.display = 'block';
    closeIcon.style.display = 'none';
  }

  function updateBadge() {
    if (unreadCount > 0 && !isOpen) {
      badge.textContent = unreadCount > 9 ? '9+' : unreadCount;
      badge.style.display = 'block';
    } else {
      badge.style.display = 'none';
    }
  }

  function scrollToBottom() {
    setTimeout(() => {
      bodyContainer.scrollTop = bodyContainer.scrollHeight;
    }, 50);
  }

  // Render Tabs in Top Bar
  function renderTabs() {
    tabsList.innerHTML = '';
    if (sessions.length === 0) {
      tabBar.style.display = 'none';
      return;
    }
    tabBar.style.display = 'flex';

    sessions.forEach(s => {
      const pill = document.createElement('div');
      pill.className = `whisker-tab-pill ${s.id === activeSessionId && !isNewSessionMode ? 'whisker-tab-active' : ''}`;
      const label = s.target_host_name || s.client_name || 'App';
      pill.innerHTML = `
        <span>📱 ${escapeHtml(label)}</span>
        <span class="whisker-tab-close" title="Close tab">&times;</span>
      `;

      pill.addEventListener('click', (e) => {
        if (e.target.classList.contains('whisker-tab-close')) {
          e.stopPropagation();
          closeSession(s.id);
        } else {
          switchSession(s.id);
        }
      });
      tabsList.appendChild(pill);
    });
  }

  function switchSession(sessId) {
    activeSessionId = sessId;
    isNewSessionMode = false;
    saveSessions();
    renderTabs();
    showActiveChat();
    connectSse();
  }

  function closeSession(sessId) {
    const idx = sessions.findIndex(s => s.id === sessId);
    if (idx === -1) return;
    sessions.splice(idx, 1);
    if (activeSessionId === sessId) {
      activeSessionId = sessions[0] ? sessions[0].id : null;
    }
    saveSessions();
    if (sessions.length === 0) {
      showNewSessionForm();
    } else {
      renderTabs();
      showActiveChat();
    }
  }

  btnAddSession.addEventListener('click', () => {
    showNewSessionForm();
  });

  btnCancelNew.addEventListener('click', () => {
    if (sessions.length > 0) {
      isNewSessionMode = false;
      renderTabs();
      showActiveChat();
    }
  });

  let currentAvailableHosts = [];

  // Display New Session / Device Picker
  async function showNewSessionForm() {
    isNewSessionMode = true;
    renderTabs();
    pickerBox.style.display = 'block';
    messagesContainer.style.display = 'none';
    footer.style.display = 'none';
    btnCall.style.opacity = '0.5';
    btnCall.style.pointerEvents = 'none';
    statusDot.className = 'whisker-status-dot';
    statusText.textContent = 'Support';
    statusText.style.color = '#94a3b8';
    titleEl.textContent = serverName || 'Whisker Support';

    btnCancelNew.style.display = (sessions.length > 0) ? 'block' : 'none';

    // Disable start button & input while fetching
    newNameInput.disabled = true;
    newNameInput.placeholder = "Searching for mobile devices...";
    btnStartSession.disabled = true;
    btnStartSession.style.opacity = '0.4';
    btnStartSession.style.cursor = 'not-allowed';

    // Prefill username from remembered last username
    const lastUser = localStorage.getItem(STORAGE_LAST_USERNAME) || '';
    newNameInput.value = lastUser;

    // Fetch available hosts from server
    hostsRadioList.innerHTML = `<div style="text-align: center; color: #94a3b8; font-size: 12px; padding: 12px;">Searching for mobile devices...</div>`;

    try {
      const resp = await fetch(`${serverUrl}/api/widget/hosts`);
      if (resp.ok) {
        const data = await resp.json();
        if (data.server_name) {
          serverName = data.server_name;
          titleEl.textContent = serverName;
        }
        const hosts = data.hosts || [];
        renderHostsRadios(hosts);
      } else {
        renderHostsRadios([]);
      }
    } catch (e) {
      renderHostsRadios([]);
    }
  }

  function renderHostsRadios(hosts) {
    currentAvailableHosts = hosts || [];
    hostsRadioList.innerHTML = '';
    if (!hosts || hosts.length === 0) {
      hostsRadioList.innerHTML = `
        <div style="background: rgba(239, 68, 68, 0.1); border: 1px solid rgba(239, 68, 68, 0.3); border-radius: 8px; padding: 14px 12px; text-align: center; font-size: 12px; color: #f87171; line-height: 1.5;">
          <div style="font-size: 22px; margin-bottom: 4px;">📵</div>
          <div style="font-weight: 700; color: #fca5a5; margin-bottom: 4px;">No Mobile Apps Available</div>
          <div>There are currently no mobile apps connected or allowing widget connections on this home server.</div>
        </div>
      `;
      newNameInput.disabled = true;
      newNameInput.placeholder = "No mobile apps available";
      btnStartSession.disabled = true;
      btnStartSession.style.opacity = '0.4';
      btnStartSession.style.cursor = 'not-allowed';
      btnStartSession.title = "No mobile apps available";
      return;
    }

    newNameInput.disabled = false;
    newNameInput.placeholder = "Enter your name (e.g. Alex)";
    btnStartSession.disabled = false;
    btnStartSession.style.opacity = '1';
    btnStartSession.style.cursor = 'pointer';
    btnStartSession.title = "Start Chat";

    hosts.forEach((h, idx) => {
      const card = document.createElement('label');
      card.className = `whisker-radio-card ${idx === 0 ? 'whisker-radio-selected' : ''}`;
      card.innerHTML = `
        <input type="radio" name="whisker_target_host" value="${h.uuid}" ${idx === 0 ? 'checked' : ''}>
        <div class="whisker-radio-text">
          <div class="whisker-radio-title">
            <span>📱 ${escapeHtml(h.username || 'Mobile App')}</span>
            ${h.is_admin ? '<span class="whisker-admin-pill">Admin</span>' : '<span class="whisker-companion-pill">Member</span>'}
          </div>
        </div>
      `;
      card.querySelector('input[type="radio"]').addEventListener('change', () => {
        document.querySelectorAll('.whisker-radio-card').forEach(c => c.classList.remove('whisker-radio-selected'));
        card.classList.add('whisker-radio-selected');
      });
      hostsRadioList.appendChild(card);
    });
  }

  // Handle Starting a New Session
  btnStartSession.addEventListener('click', handleStartNewSession);
  newNameInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      if (newNameInput.disabled || btnStartSession.disabled) return;
      handleStartNewSession();
    }
  });

  async function handleStartNewSession() {
    if (!currentAvailableHosts || currentAvailableHosts.length === 0) {
      alert("Cannot start a chat session: No mobile devices are currently available on this home server.");
      return;
    }

    const selectedRadio = document.querySelector('input[name="whisker_target_host"]:checked');
    if (!selectedRadio) {
      alert("Please select a mobile device to contact.");
      return;
    }

    const rawName = (newNameInput.value || '').trim();
    if (!rawName) {
      alert("Please enter your name to connect.");
      newNameInput.focus();
      return;
    }

    const targetHostUuid = selectedRadio.value;
    localStorage.setItem(STORAGE_LAST_USERNAME, rawName);

    // Resolve target host display name
    let targetHostName = 'Mobile Device';
    const titleSpan = selectedRadio.closest('.whisker-radio-card').querySelector('.whisker-radio-title span');
    if (titleSpan) {
      targetHostName = titleSpan.textContent.replace('📱', '').trim();
    }

    const newUuid = 'emb_' + Date.now().toString(36) + Math.random().toString(36).substr(2, 6);

    btnStartSession.disabled = true;
    btnStartSession.textContent = "Connecting...";

    // Register with Home Server with target_host BEFORE creating the session
    try {
      const resp = await fetch(`${serverUrl}/api/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          uuid: newUuid,
          name: rawName,
          reason: `Website visitor on ${window.location.hostname || 'web'}`,
          client_type: 'EMBED',
          target_host: targetHostUuid
        })
      });

      if (!resp.ok) {
        btnStartSession.disabled = false;
        btnStartSession.textContent = "Start Chat";
        const errData = await resp.json().catch(() => ({}));
        const errMsg = errData.error || "Unable to start session: No mobile apps are available.";
        alert(errMsg);
        return;
      }
    } catch (e) {
      btnStartSession.disabled = false;
      btnStartSession.textContent = "Start Chat";
      console.warn('[Whisker Widget] Registration network error:', e);
      alert("Unable to reach home server. Please check your network or server status.");
      return;
    }

    btnStartSession.disabled = false;
    btnStartSession.textContent = "Start Chat";

    const newSession = {
      id: 'sess_' + newUuid,
      client_uuid: newUuid,
      client_name: rawName,
      client_token: '',
      client_status: 'PENDING',
      target_host_uuid: targetHostUuid,
      target_host_name: targetHostName,
      is_host_online: false,
      messages: []
    };

    sessions.push(newSession);
    activeSessionId = newSession.id;
    isNewSessionMode = false;
    saveSessions();

    renderTabs();
    showActiveChat();
    connectSse();
  }

  // Display Chat for Active Session
  function showActiveChat() {
    const session = getActiveSession();
    if (!session) {
      showNewSessionForm();
      return;
    }

    pickerBox.style.display = 'none';
    messagesContainer.style.display = 'flex';
    footer.style.display = 'flex';

    titleEl.textContent = session.target_host_name ? `Chat: ${session.target_host_name}` : (serverName || 'Whisker Support');
    setOnlineStatus(session.is_host_online && session.client_status === 'APPROVED');

    renderMessages();
    chatInput.focus();
    scrollToBottom();
  }

  // Messages UI Rendering
  btnSend.addEventListener('click', handleSendMessage);
  chatInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  });

  if (btnAttach && fileInput) {
    btnAttach.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => {
      if (e.target.files && e.target.files[0]) {
        handleFileUpload(e.target.files[0]);
        fileInput.value = '';
      }
    });
  }

  // Drag and drop onto chat body
  ['dragenter', 'dragover'].forEach(name => {
    bodyContainer.addEventListener(name, (e) => {
      e.preventDefault();
      bodyContainer.style.background = 'rgba(6, 182, 212, 0.08)';
      bodyContainer.style.outline = '2px dashed #06b6d4';
    }, false);
  });
  ['dragleave', 'drop'].forEach(name => {
    bodyContainer.addEventListener(name, (e) => {
      e.preventDefault();
      bodyContainer.style.background = '';
      bodyContainer.style.outline = '';
    }, false);
  });
  bodyContainer.addEventListener('drop', (e) => {
    e.preventDefault();
    if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      handleFileUpload(e.dataTransfer.files[0]);
    }
  });

  async function handleFileUpload(file) {
    const session = getActiveSession();
    if (!session) return;
    if (!session.client_token || session.client_status !== 'APPROVED') {
      alert("Please wait for your connection request to be approved before transferring files.");
      return;
    }

    const reader = new FileReader();
    reader.onload = async function(evt) {
      const b64Data = evt.target.result;
      const isImg = file.type.startsWith('image/');
      const newMsg = {
        id: 'local_' + Date.now(),
        sender: 'guest',
        text: file.name,
        filePath: b64Data,
        fileName: file.name,
        fileSize: file.size,
        timestamp: Date.now()
      };
      session.messages.push(newMsg);
      saveSessions();
      renderMessages();
      scrollToBottom();

      try {
        const res = await fetch(`${serverUrl}/api/file/upload`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            uuid: session.client_uuid,
            token: session.client_token,
            filename: file.name,
            content: b64Data,
            name: session.client_name,
            contact: session.target_host_uuid,
            text: file.name
          })
        });
        const data = await res.json();
        if (data && data.url) {
          newMsg.filePath = data.url;
          saveSessions();
          renderMessages();
        }
      } catch (err) {
        console.warn('[Whisker Widget] File upload failed:', err);
      }
    };
    reader.readAsDataURL(file);
  }

  async function handleSendMessage() {
    const session = getActiveSession();
    if (!session) return;

    const text = (chatInput.value || '').trim();
    if (!text) return;
    chatInput.value = '';

    const newMsg = {
      id: 'local_' + Date.now(),
      sender: 'guest',
      text: text,
      timestamp: Date.now()
    };
    session.messages.push(newMsg);
    saveSessions();
    renderMessages();
    scrollToBottom();

    // If approved, send message to Home Server
    if (session.client_token && session.client_status === 'APPROVED') {
      try {
        await fetch(`${serverUrl}/api/message`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            uuid: session.client_uuid,
            token: session.client_token,
            name: session.client_name,
            contact: session.target_host_uuid,
            text: text
          })
        });
      } catch (e) {
        console.warn('[Whisker Widget] Send message error:', e);
      }
    }
  }

  function renderMessages() {
    const session = getActiveSession();
    messagesContainer.innerHTML = '';
    if (!session || !session.messages) return;

    session.messages.forEach((m) => {
      const div = document.createElement('div');
      const isSent = (m.sender === 'guest' || m.sender === 'client' || m.sender === 'user');
      div.className = `whisker-msg ${isSent ? 'whisker-msg-sent' : 'whisker-msg-received'}`;
      const timeStr = new Date(m.timestamp || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

      const fileUrl = m.filePath || m.file;
      const isImage = fileUrl && (/\.(jpe?g|png|gif|webp|bmp|svg)($|\?)/i.test(fileUrl) || fileUrl.startsWith('data:image/'));

      let contentHtml = '';
      if (fileUrl) {
        const fullUrl = fileUrl.startsWith('http') || fileUrl.startsWith('data:') ? fileUrl : `${serverUrl}${fileUrl}`;
        const fileName = escapeHtml(m.fileName || m.text || 'Attachment');
        if (isImage) {
          contentHtml = `
            <div>
              <a href="${fullUrl}" target="_blank" rel="noopener">
                <img src="${fullUrl}" alt="${fileName}" style="max-width: 100%; max-height: 180px; border-radius: 8px; display: block; margin-bottom: 4px; border: 1px solid rgba(255,255,255,0.1); object-fit: contain; background: #000;" />
              </a>
              ${m.text && m.text !== fileName ? `<div style="font-size:12px;margin-top:2px;">${escapeHtml(m.text)}</div>` : ''}
            </div>
          `;
        } else {
          contentHtml = `
            <div style="display:flex;align-items:center;gap:8px;padding:4px 0;">
              <span style="font-size:20px;">📎</span>
              <div style="overflow:hidden;flex:1;">
                <div style="font-weight:600;font-size:12px;text-overflow:ellipsis;overflow:hidden;white-space:nowrap;">${fileName}</div>
                <a href="${fullUrl}" download="${fileName}" target="_blank" style="font-size:11px;color:#38bdf8;text-decoration:underline;">Download File</a>
              </div>
            </div>
          `;
        }
      } else {
        contentHtml = `<div>${escapeHtml(m.text || '')}</div>`;
      }

      div.innerHTML = `
        ${contentHtml}
        <div class="whisker-msg-time">${timeStr}</div>
      `;
      messagesContainer.appendChild(div);
    });
  }

  function setOnlineStatus(online) {
    const session = getActiveSession();
    if (session) {
      session.is_host_online = online;
    }
    // Transparent rule: Only show online status AFTER approval!
    if (session && session.client_status === 'APPROVED' && online) {
      statusDot.className = 'whisker-status-dot whisker-online';
      statusText.textContent = 'Online';
      statusText.style.color = '#10b981';
      btnCall.style.opacity = '1';
      btnCall.style.pointerEvents = 'auto';
    } else {
      statusDot.className = 'whisker-status-dot';
      statusText.textContent = 'Support';
      statusText.style.color = '#94a3b8';
      btnCall.style.opacity = '0.5';
      btnCall.style.pointerEvents = 'none';
    }
  }

  function escapeHtml(str) {
    return String(str || '').replace(/[&<>'"]/g, tag => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
    }[tag] || tag));
  }

  // Session Synchronization
  async function syncActiveSession() {
    const session = getActiveSession();
    if (!session || !session.client_uuid) return;

    try {
      const res = await fetch(`${serverUrl}/api/embed/session?uuid=${encodeURIComponent(session.client_uuid)}`);
      if (res.ok) {
        const data = await res.json();
        if (data.server_name) {
          serverName = data.server_name;
        }
        if (data.target_host_name && !session.target_host_name) {
          session.target_host_name = data.target_host_name;
          renderTabs();
        }
        if (data.status === 'APPROVED' && data.token) {
          session.client_status = 'APPROVED';
          session.client_token = data.token;
          session.is_host_online = !!data.host_online;
          saveSessions();
          setOnlineStatus(!!data.host_online);
          syncMessagesFromServer(session);
        } else {
          session.is_host_online = false;
          setOnlineStatus(false);
        }
      }
    } catch (e) {
      setOnlineStatus(false);
    }
  }

  async function syncMessagesFromServer(session) {
    if (!session || !session.client_uuid || !session.client_token) return;
    try {
      const res = await fetch(`${serverUrl}/api/messages?uuid=${encodeURIComponent(session.client_uuid)}&token=${encodeURIComponent(session.client_token)}`);
      if (res.ok) {
        const srvMsgs = await res.json();
        if (Array.isArray(srvMsgs) && srvMsgs.length > 0) {
          let updated = false;
          srvMsgs.forEach(sm => {
            const exists = session.messages.some(m => (m.id && m.id === sm.id) || (m.text === sm.text && Math.abs(m.timestamp - (sm.created_at || sm.timestamp)) < 5000));
            if (!exists) {
              session.messages.push({
                id: sm.id,
                sender: sm.sender === 'phone' || sm.sender === 'host' ? 'host' : 'guest',
                text: sm.text || '',
                timestamp: sm.created_at || sm.timestamp || Date.now()
              });
              updated = true;
            }
          });
          if (updated) {
            session.messages.sort((a, b) => a.timestamp - b.timestamp);
            saveSessions();
            if (session.id === activeSessionId) {
              renderMessages();
              scrollToBottom();
            }
          }
        }
      }
    } catch (e) {}
  }

  // SSE Stream for Real-time Events
  function connectSse() {
    const session = getActiveSession();
    if (!session || !session.client_uuid) return;

    if (sseSource) {
      sseSource.close();
      sseSource = null;
    }

    const sseUrl = `${serverUrl}/api/events?uuid=${encodeURIComponent(session.client_uuid)}${session.client_token ? `&token=${encodeURIComponent(session.client_token)}` : ''}`;
    try {
      sseSource = new EventSource(sseUrl);

      sseSource.onmessage = (event) => {
        if (!event.data || event.data === ': keep-alive') return;
        try {
          const payload = JSON.parse(event.data);
          handleIncomingEvent(payload, session);
        } catch (err) {}
      };

      sseSource.onerror = () => {
        if (!pollInterval) {
          pollInterval = setInterval(pollSessionAndEvents, 8000);
        }
      };
    } catch (e) {}
  }

  async function pollSessionAndEvents() {
    sessions.forEach(async (sess) => {
      try {
        const res = await fetch(`${serverUrl}/api/embed/session?uuid=${encodeURIComponent(sess.client_uuid)}`);
        if (res.ok) {
          const data = await res.json();
          if (data.status === 'APPROVED' && data.token && sess.client_status !== 'APPROVED') {
            handleApproval(data.token, sess);
          }
          if (sess.client_status === 'APPROVED') {
            sess.is_host_online = !!data.host_online;
            if (sess.id === activeSessionId) {
              setOnlineStatus(!!data.host_online);
            }
            syncMessagesFromServer(sess);
          }
        }
      } catch (e) {}
    });
  }

  function handleIncomingEvent(data, session) {
    const eventType = data.event || data.type || '';

    // 1. Host approved registration
    if (eventType === 'approved' || data.status === 'APPROVED' || data.type === 'registration_response') {
      const token = data.token;
      if (token) {
        handleApproval(token, session);
      }
      return;
    }

    // 2. Incoming chat message
    if (eventType === 'message' || eventType === 'chat_message') {
      const text = data.text || '';
      if (text) {
        const msg = {
          id: data.messageId || data.eventId || 'msg_' + Date.now(),
          sender: 'host',
          text: text,
          timestamp: data.timestamp || Date.now()
        };
        const exists = session.messages.some(m => m.id === msg.id || (m.text === text && Math.abs(m.timestamp - msg.timestamp) < 5000));
        if (!exists) {
          session.messages.push(msg);
          saveSessions();
          if (session.id === activeSessionId) {
            renderMessages();
            scrollToBottom();
          }
          if (!isOpen || session.id !== activeSessionId) {
            unreadCount++;
            updateBadge();
          }
        }
      }
      return;
    }

    // 3. WebRTC signaling
    if (eventType === 'call' || data.type === 'webrtc_call_initiate') {
      handleIncomingCall(data, session);
      return;
    }
    if (eventType === 'call_answered' || data.type === 'webrtc_call_answer') {
      handleCallAnswered(data);
      return;
    }
    if (data.type === 'webrtc_ice_candidate') {
      handleRemoteIceCandidate(data.candidate);
      return;
    }
    if (eventType === 'hangup' || eventType === 'call_ended' || data.type === 'webrtc_call_hangup') {
      handleCallEnded();
      return;
    }
  }

  function handleApproval(token, session) {
    session.client_token = token;
    session.client_status = 'APPROVED';
    session.is_host_online = true;
    saveSessions();

    if (session.id === activeSessionId) {
      setOnlineStatus(true);
    }

    // Flush pending local messages
    session.messages.forEach(async (m) => {
      if (m.sender === 'guest' && m.id && m.id.startsWith('local_')) {
        try {
          await fetch(`${serverUrl}/api/message`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              uuid: session.client_uuid,
              token: session.client_token,
              name: session.client_name,
              contact: session.target_host_uuid,
              text: m.text
            })
          });
        } catch (e) {}
      }
    });

    syncMessagesFromServer(session);
  }

  // --- WebRTC Audio/Video Calling ---
  btnCall.addEventListener('click', startOutgoingCall);
  btnHangup.addEventListener('click', endCall);
  btnIncomingAccept.addEventListener('click', handleAcceptIncomingCall);
  btnIncomingDecline.addEventListener('click', handleDeclineIncomingCall);

  btnToggleMic.addEventListener('click', () => {
    isMicMuted = !isMicMuted;
    if (localStream) {
      localStream.getAudioTracks().forEach(t => t.enabled = !isMicMuted);
    }
    btnToggleMic.classList.toggle('active', isMicMuted);
    btnToggleMic.textContent = isMicMuted ? '🔇' : '🎤';
  });

  btnToggleVideo.addEventListener('click', () => {
    isVideoMuted = !isVideoMuted;
    if (localStream) {
      localStream.getVideoTracks().forEach(t => t.enabled = !isVideoMuted);
    }
    btnToggleVideo.classList.toggle('active', isVideoMuted);
    btnToggleVideo.textContent = isVideoMuted ? '🚫' : '📹';
  });

  async function startOutgoingCall() {
    const session = getActiveSession();
    if (!session || !session.client_uuid) return;
    if (session.client_status !== 'APPROVED') {
      alert("Device is not connected for live calls. Please send a message below!");
      return;
    }

    try {
      localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
    } catch (e) {
      try {
        localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      } catch (err) {
        alert("Microphone access is required to place a call.");
        return;
      }
    }

    localVideo.srcObject = localStream;
    callOverlay.style.display = 'flex';
    isInCall = true;
    isCallDialing = true;
    callStatusLabel.textContent = `Calling ${session.target_host_name || 'mobile app'}...`;
    startRingTone();

    currentCallId = `call_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;
    pc = new RTCPeerConnection(rtcConfig);

    pc.onicecandidate = (e) => {
      if (e.candidate) {
        fetch(`${serverUrl}/api/signal`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            event: 'signal',
            type: 'webrtc_ice_candidate',
            uuid: session.client_uuid,
            token: session.client_token,
            call_id: currentCallId,
            candidate: e.candidate.toJSON ? e.candidate.toJSON() : e.candidate
          })
        }).catch(() => {});
      }
    };

    pc.ontrack = (e) => {
      if (e.streams && e.streams[0]) {
        remoteVideo.srcObject = e.streams[0];
      }
    };

    localStream.getTracks().forEach(track => pc.addTrack(track, localStream));

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    try {
      await fetch(`${serverUrl}/api/call`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          event: 'call',
          uuid: session.client_uuid,
          token: session.client_token,
          call_id: currentCallId,
          sdp: offer.sdp,
          video: true,
          contact: session.target_host_uuid
        })
      });
    } catch (e) {
      stopRingTone();
      callStatusLabel.textContent = "Call failed to connect";
      setTimeout(endCall, 2000);
    }
  }

  function handleIncomingCall(data, session) {
    pendingIncomingCall = { data, session };

    // Ensure the widget window is open so the user sees the incoming call
    if (!isOpen) {
      openWidget();
    }
    // Switch to the session receiving the call
    if (session.id !== activeSessionId) {
      switchSession(session.id);
    }

    const callerName = data.name || session.target_host_name || 'Mobile App';
    incomingCallerName.textContent = callerName;
    incomingTypeLabel.textContent = data.video ? 'WebRTC Video Call' : 'WebRTC Audio Call';
    incomingDialog.style.display = 'flex';
    startRingTone();
  }

  async function handleAcceptIncomingCall() {
    if (!pendingIncomingCall) return;
    const { data, session } = pendingIncomingCall;
    pendingIncomingCall = null;

    stopRingTone();
    incomingDialog.style.display = 'none';

    currentCallId = data.call_id;
    callOverlay.style.display = 'flex';
    isInCall = true;
    callStatusLabel.textContent = `Connecting with ${session.target_host_name || 'Mobile App'}...`;

    try {
      localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: !!data.video });
      localVideo.srcObject = localStream;
    } catch (e) {
      try {
        localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
        localVideo.srcObject = localStream;
      } catch (err) {
        alert("Microphone permission is required to accept this call.");
        endCall(true);
        return;
      }
    }

    pc = new RTCPeerConnection(rtcConfig);
    pc.onicecandidate = (e) => {
      if (e.candidate) {
        fetch(`${serverUrl}/api/signal`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            event: 'signal',
            type: 'webrtc_ice_candidate',
            uuid: session.client_uuid,
            token: session.client_token,
            call_id: currentCallId,
            candidate: e.candidate.toJSON ? e.candidate.toJSON() : e.candidate
          })
        }).catch(() => {});
      }
    };

    pc.ontrack = (e) => {
      if (e.streams && e.streams[0]) {
        remoteVideo.srcObject = e.streams[0];
      }
    };

    localStream.getTracks().forEach(track => pc.addTrack(track, localStream));

    if (data.sdp) {
      try {
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: data.sdp }));
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        startCallTimer();

        await fetch(`${serverUrl}/api/signal`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            event: 'signal',
            type: 'webrtc_call_answer',
            uuid: session.client_uuid,
            token: session.client_token,
            call_id: currentCallId,
            sdp: answer.sdp
          })
        });
      } catch (err) {
        endCall(true);
      }
    }
  }

  function handleDeclineIncomingCall() {
    if (!pendingIncomingCall) return;
    const { data, session } = pendingIncomingCall;
    const callIdToDecline = data.call_id;
    pendingIncomingCall = null;

    stopRingTone();
    incomingDialog.style.display = 'none';

    fetch(`${serverUrl}/api/signal`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        event: 'signal',
        type: 'webrtc_call_hangup',
        uuid: session.client_uuid,
        token: session.client_token,
        call_id: callIdToDecline,
        reason: 'Call declined by user'
      })
    }).catch(() => {});
  }

  async function handleCallAnswered(data) {
    stopRingTone();
    isCallDialing = false;
    startCallTimer();
    if (pc && data.sdp) {
      try {
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: data.sdp }));
      } catch (e) {}
    }
  }

  function handleRemoteIceCandidate(candidate) {
    if (pc && candidate) {
      try {
        pc.addIceCandidate(new RTCIceCandidate(candidate));
      } catch (e) {}
    }
  }

  function handleCallEnded() {
    if (pendingIncomingCall) {
      stopRingTone();
      incomingDialog.style.display = 'none';
      pendingIncomingCall = null;
    }
    endCall(false);
  }

  function endCall(sendHangup = true) {
    if (pendingIncomingCall) {
      incomingDialog.style.display = 'none';
      pendingIncomingCall = null;
    }
    stopRingTone();
    stopCallTimer();
    const session = getActiveSession();

    if (sendHangup && currentCallId && session) {
      fetch(`${serverUrl}/api/signal`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          event: 'signal',
          type: 'webrtc_call_hangup',
          uuid: session.client_uuid,
          token: session.client_token,
          call_id: currentCallId
        })
      }).catch(() => {});
    }

    if (pc) {
      try { pc.close(); } catch (e) {}
      pc = null;
    }
    if (localStream) {
      localStream.getTracks().forEach(t => t.stop());
      localStream = null;
    }
    remoteVideo.srcObject = null;
    localVideo.srcObject = null;
    callOverlay.style.display = 'none';
    isInCall = false;
    isCallDialing = false;
    currentCallId = null;
  }

  function startRingTone() {
    try {
      stopRingTone();
      ringAudioContext = new (window.AudioContext || window.webkitAudioContext)();
      
      const playCadence = () => {
        if (!ringAudioContext) return;
        try {
          const osc1 = ringAudioContext.createOscillator();
          const osc2 = ringAudioContext.createOscillator();
          const gain = ringAudioContext.createGain();

          osc1.type = 'sine';
          osc1.frequency.setValueAtTime(440, ringAudioContext.currentTime);
          osc2.type = 'sine';
          osc2.frequency.setValueAtTime(480, ringAudioContext.currentTime);

          gain.gain.setValueAtTime(0.06, ringAudioContext.currentTime);
          gain.gain.exponentialRampToValueAtTime(0.001, ringAudioContext.currentTime + 1.2);

          osc1.connect(gain);
          osc2.connect(gain);
          gain.connect(ringAudioContext.destination);

          osc1.start();
          osc2.start();
          osc1.stop(ringAudioContext.currentTime + 1.2);
          osc2.stop(ringAudioContext.currentTime + 1.2);
        } catch (e) {}
      };

      playCadence();
      ringInterval = setInterval(playCadence, 2500);
    } catch (e) {}
  }

  function stopRingTone() {
    try {
      if (ringInterval) {
        clearInterval(ringInterval);
        ringInterval = null;
      }
      if (ringOscillator) {
        ringOscillator.stop();
        ringOscillator.disconnect();
        ringOscillator = null;
      }
      if (ringAudioContext) {
        ringAudioContext.close();
        ringAudioContext = null;
      }
    } catch (e) {}
  }

  function startCallTimer() {
    callStartTime = Date.now();
    callTimerInterval = setInterval(() => {
      const elapsed = Math.floor((Date.now() - callStartTime) / 1000);
      const mins = String(Math.floor(elapsed / 60)).padStart(2, '0');
      const secs = String(elapsed % 60).padStart(2, '0');
      callStatusLabel.textContent = `In Call (${mins}:${secs})`;
    }, 1000);
  }

  function stopCallTimer() {
    if (callTimerInterval) {
      clearInterval(callTimerInterval);
      callTimerInterval = null;
    }
  }

  // Initial Sync
  if (sessions.length > 0) {
    syncActiveSession();
    connectSse();
  }
})();
