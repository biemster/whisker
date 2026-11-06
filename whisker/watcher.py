#!/usr/bin/env python3
"""
Whisker Event-Driven Agent Watcher (`whisker-watch`)
Blocks on the server SSE event stream and exits as soon as a new message from the
host mobile device arrives.

Writing an atomic trigger file (whisker.attn) and immediately exiting turns the
watcher into an event trigger for AI agent harnesses (like DSH, Claude, OpenClaw).
When the background watcher process finishes, the harness notifies the agent session,
triggering exactly ONE agent turn to process the host message with ZERO token cost
while idle.
"""

import sys
import os
import json
import time
import argparse
from whisker.mcp import handle_wait_new_messages, resolve_client_db

DEFAULT_STATE_FILE = ".whisker_watermark.json"
DEFAULT_ATTN_FILE = "whisker.attn"
DEFAULT_INBOX_LOG = "whisker_inbox.log"
DEFAULT_WATCHER_LOG = "whisker_watcher.log"


def now_str():
    return time.strftime("%Y-%m-%d %H:%M:%S")


def log_watcher(msg, log_file=DEFAULT_WATCHER_LOG):
    try:
        with open(log_file, "a") as f:
            f.write(f"{now_str()} {msg}\n")
    except Exception:
        pass


def load_state(state_file):
    try:
        with open(state_file, "r") as f:
            data = json.load(f)
            return set(data.get("seen", {}).keys())
    except Exception:
        return None


def save_state(seen_ids, state_file):
    tmp_path = f"{state_file}.tmp"
    with open(tmp_path, "w") as f:
        json.dump({"seen": {str(k): 0 for k in seen_ids}}, f)
    os.replace(tmp_path, state_file)


def write_attn_trigger(messages, target_info, attn_file=DEFAULT_ATTN_FILE, inbox_log=DEFAULT_INBOX_LOG):
    host_label = target_info.get("host_name") or target_info.get("host_uuid") or "host"
    username = target_info.get("username") or "client"
    server_name = target_info.get("server_name") or "server"
    host_contact = target_info.get("host_uuid") or target_info.get("host_name") or ""

    lines = [
        f"# whisker new instructions at {now_str()}",
        f"# count={len(messages)} host={host_label}",
        ""
    ]
    for m in messages:
        sender_label = m.get("sender") or "phone"
        text = m.get("text") or ""
        lines.append(f"FROM HOST {host_label}: {text}")
        if m.get("file_path"):
            lines.append(f"  attachment: {m['file_path']}")
        lines.append("")

    lines.extend([
        "# RUNBOOK FOR AGENT / HANDLER:",
        f"# 1. Reply to user via Whisker MCP tool 'whisker_send_message' (username '{username}', server_name '{server_name}', contact '{host_contact}').",
        f"# 2. Delete this trigger file: rm {attn_file}",
        f"# 3. Restart the watcher process in the background: whisker-watch & (or your harness background task).",
        ""
    ])

    tmp_path = f"{attn_file}.tmp"
    with open(tmp_path, "w") as f:
        f.write("\n".join(lines))
    os.replace(tmp_path, attn_file)

    # Append to inbox log
    try:
        with open(inbox_log, "a") as f:
            for m in messages:
                m_id = m.get("id")
                m_text = m.get("text")
                f.write(f"[NEW] {now_str()} from=phone id={m_id} text={m_text!r}\n")
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser(
        description="Whisker Event-Driven Agent Watcher - blocks on SSE and exits on new host messages to wake AI agent harnesses."
    )
    parser.add_argument("--username", "-u", help="Client profile username")
    parser.add_argument("--server", "-s", dest="server_name", help="Home server name")
    parser.add_argument("--contact", "-c", help="Filter by host contact UUID or name")
    parser.add_argument("--db", dest="db_file", help="Direct path to client database SQLite file")
    parser.add_argument("--directory", "-d", default=".", help="Directory to search for client DB profiles (default: .)")
    parser.add_argument("--timeout", "-t", type=int, default=3600, help="Max wait duration in seconds before recycling watcher (default: 3600)")
    parser.add_argument("--attn-file", default=DEFAULT_ATTN_FILE, help="Atomic trigger file path (default: whisker.attn)")
    parser.add_argument("--state-file", default=DEFAULT_STATE_FILE, help="Persisted seen message IDs file (default: .whisker_watermark.json)")
    parser.add_argument("--inbox-log", default=DEFAULT_INBOX_LOG, help="Inbox log path (default: whisker_inbox.log)")
    parser.add_argument("--watcher-log", default=DEFAULT_WATCHER_LOG, help="Watcher diagnostic log path (default: whisker_watcher.log)")
    args = parser.parse_args()

    # Change to target directory if needed
    if args.directory and args.directory != ".":
        os.chdir(args.directory)

    # Resolve target DB info
    target = resolve_client_db(
        username=args.username,
        server_name=args.server_name,
        custom_db=args.db_file
    )

    log_watcher(f"Starting watcher for client '{target['username']}' on '{target['server_name']}'", args.watcher_log)

    seen = load_state(args.state_file)
    first_run = (seen is None)
    if first_run:
        seen = set()

    wait_args = {
        "username": target["username"],
        "server_name": target["server_name"],
        "db_file": target.get("filepath") or target.get("file"),
        "contact": args.contact,
        "timeout_seconds": args.timeout,
        "known_message_ids": list(seen) if not first_run else []
    }

    res = None
    retry_delay = 5
    while res is None:
        try:
            res = handle_wait_new_messages(wait_args)
        except Exception as e:
            log_watcher(f"Server unreachable ({e.__class__.__name__}); retrying in {retry_delay}s", args.watcher_log)
            time.sleep(retry_delay)

    new_messages = res.get("messages", [])

    if first_run:
        # Query message history to persist full baseline seen set regardless of whether
        # a new message arrived during the first wait
        try:
            from whisker.mcp import handle_get_messages
            hist = handle_get_messages({
                "username": target["username"],
                "server_name": target["server_name"],
                "db_file": target.get("filepath") or target.get("file"),
                "limit": 200
            })
            for m in hist.get("messages", []):
                if m.get("id"):
                    seen.add(m["id"])
            save_state(seen, args.state_file)
            log_watcher(f"First-run seeded {len(seen)} existing message IDs into {args.state_file}", args.watcher_log)
        except Exception as e:
            log_watcher(f"Warning: failed to fetch first-run message history for baseline: {e}", args.watcher_log)

    if new_messages:
        # Write atomic trigger file and update watermark state
        write_attn_trigger(new_messages, target, attn_file=args.attn_file, inbox_log=args.inbox_log)
        for m in new_messages:
            if m.get("id"):
                seen.add(m["id"])
        save_state(seen, args.state_file)
        log_watcher(f"NEW host message(s) received ({len(new_messages)}). Trigger written to {args.attn_file}. Exiting.", args.watcher_log)
        print(f"[{now_str()}] Received {len(new_messages)} host message(s) -> {args.attn_file}")
        sys.exit(0)
    else:
        # Timeout reached with no new messages
        log_watcher("Watcher timeout reached without new messages. Exiting.", args.watcher_log)
        sys.exit(0)


if __name__ == "__main__":
    main()
