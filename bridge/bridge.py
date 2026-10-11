#!/usr/bin/env python3
"""
Sahayak bridge: lets the phone app use Claude Code on this computer as its brain, so no API key
is stored on the phone. It uses whatever account `claude` is already logged in with.

    python3 bridge/bridge.py
    adb reverse tcp:8765 tcp:8765     # phone/emulator -> this computer, over USB/adb only

The app POSTs the current screen to /step; we ask `claude -p` for the next actions and send them back.
Each help session is one Claude Code session (continued with --resume), so it remembers earlier steps.
"""
import json
import subprocess
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST, PORT = "127.0.0.1", 8765
WORKDIR = tempfile.mkdtemp(prefix="sahayak-bridge-")  # neutral cwd: no project CLAUDE.md gets loaded

TOOLS = ["tap", "tap_at", "long_press", "type_text", "scroll", "press", "open_app",
         "wait", "say", "ask_user", "finish"]
SCHEMA = {
    "type": "object",
    "properties": {
        "actions": {
            "type": "array",
            "minItems": 1,
            "items": {
                "type": "object",
                "properties": {
                    "tool": {"type": "string", "enum": TOOLS},
                    "element_id": {"type": "integer"},
                    "x": {"type": "integer"},
                    "y": {"type": "integer"},
                    "text": {"type": "string"},
                    "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
                    "key": {"type": "string", "enum": ["back", "home", "recents", "notifications"]},
                    "name": {"type": "string"},
                    "seconds": {"type": "integer"},
                    "question": {"type": "string"},
                    "say_to_person": {"type": "string"},
                    "report_for_child": {"type": "string"},
                },
                "required": ["tool"],
            },
        }
    },
    "required": ["actions"],
}


def ask_claude_code(body):
    parts = []
    if body.get("request"):
        parts.append(f'The person said: "{body["request"]}"')
    if body.get("results"):
        parts.append("Results of your last actions:\n" + "\n".join(body["results"]))
    parts.append("Current screen elements:\n" + body["elements"])
    content = [{"type": "text", "text": "\n\n".join(parts)}]
    if body.get("screenshot"):
        content.append({"type": "image",
                        "source": {"type": "base64", "media_type": "image/jpeg", "data": body["screenshot"]}})
    else:
        content[0]["text"] += "\n(Screenshot blocked by this app.)"

    cmd = ["claude", "-p",
           "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
           "--json-schema", json.dumps(SCHEMA),
           "--system-prompt", body["system_prompt"],
           "--tools", "",               # Claude Code only decides; the phone executes
           "--strict-mcp-config"]       # don't load this computer's MCP servers
    if body.get("session"):
        cmd += ["--resume", body["session"]]

    message = {"type": "user", "message": {"role": "user", "content": content}}
    proc = subprocess.run(cmd, input=json.dumps(message) + "\n", capture_output=True,
                          text=True, timeout=170, cwd=WORKDIR)
    for line in proc.stdout.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") == "result":
            out = event.get("structured_output") or {}
            return {"session": event.get("session_id"), "actions": out.get("actions", []),
                    "error": event.get("result") if event.get("is_error") else None}
    return {"session": body.get("session"), "actions": [], "error": proc.stderr[-500:] or "no result"}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/step":
            self.send_error(404)
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        started = time.time()
        try:
            reply = ask_claude_code(body)
        except Exception as e:  # noqa: BLE001 - report anything back to the phone
            reply = {"session": body.get("session"), "actions": [], "error": str(e)}
        if body.get("request"):
            print(f'\n🎤 "{body["request"]}"')
        for a in reply["actions"]:
            args = {k: v for k, v in a.items() if k != "tool"}
            print(f"  → {a['tool']} {json.dumps(args, ensure_ascii=False)}")
        if reply.get("error"):
            print(f"  ! {reply['error']}")
        print(f"    ({time.time() - started:.1f}s)")
        sys.stdout.flush()
        data = json.dumps(reply).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"Sahayak bridge on http://{HOST}:{PORT}  (run: adb reverse tcp:{PORT} tcp:{PORT})")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
