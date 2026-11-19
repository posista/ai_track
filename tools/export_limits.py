#!/usr/bin/env python3
"""Convert documented Codex or Claude Code windows to the Android import format."""

import argparse
from datetime import datetime, timedelta, timezone
import json
from queue import Empty, Full, Queue
import subprocess
import sys
from threading import Thread
import time


def codex_limits():
    """Ask the signed-in Codex CLI for its account limits over local stdio."""
    with subprocess.Popen(
        ["codex", "app-server"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        encoding="utf-8",
    ) as process:
        messages = Queue(maxsize=256)

        def read_messages():
            for line in process.stdout:
                try:
                    message = json.loads(line)
                    if isinstance(message, dict):
                        messages.put_nowait(message)
                except json.JSONDecodeError:
                    continue
                except Full:
                    break

        reader = Thread(target=read_messages, daemon=True)
        reader.start()
        deadline = time.monotonic() + 25

        def send(method, request_id=None, params=None):
            message = {"method": method}
            if request_id is not None:
                message["id"] = request_id
            if params is not None:
                message["params"] = params
            process.stdin.write(json.dumps(message) + "\n")
            process.stdin.flush()

        def result(request_id):
            while time.monotonic() < deadline:
                try:
                    message = messages.get(timeout=min(0.5, max(0, deadline - time.monotonic())))
                except Empty:
                    if not reader.is_alive():
                        break
                    continue
                if message.get("id") == request_id and "method" not in message:
                    if isinstance(message.get("result"), dict):
                        return message["result"]
                    break
            raise ValueError("Codex App Server hat keine nutzbare Antwort geliefert.")

        try:
            send("initialize", 0, {"clientInfo": {"name": "ai_limit_notifier", "version": "1.0.0"}})
            result(0)
            send("initialized", params={})
            send("account/rateLimits/read", 1)
            return result(1)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            reader.join(timeout=1)


def reset_line(provider, window, reset, used, now):
    if type(reset) is not int:
        return None
    try:
        when = datetime.fromtimestamp(reset, timezone.utc)
    except (ValueError, OSError, OverflowError):
        return None
    if not now < when <= now + timedelta(days=30):
        return None
    timestamp = when.isoformat(timespec="seconds").replace("+00:00", "Z")
    status = "erreicht" if type(used) in (int, float) and used >= 100 else "geplant"
    return f"{provider} | {window} | {timestamp} | {status}"


def export_lines(source, data, now):
    if not isinstance(data, dict):
        return []
    if source == "codex":
        snapshots = data.get("rateLimitsByLimitId") or {}
        main = snapshots.get("codex") or data.get("rateLimits") or {}
        if main.get("limitId") not in (None, "codex"):
            return []
        candidates = []
        for key in ("primary", "secondary"):
            window = main.get(key) or {}
            duration = window.get("windowDurationMins")
            if type(duration) is int and 0 < duration <= 10080:
                label = "wöchentlich" if duration == 10080 else "kurzfristig" if duration <= 1440 else "unbekannt"
                line = reset_line("Codex", label, window.get("resetsAt"), window.get("usedPercent"), now)
                if line:
                    candidates.append((label, line))
        return [
            line for label, line in candidates
            if sum(other == label for other, _ in candidates) == 1
        ]

    result = []
    windows = data.get("rate_limits") or {}
    for key, label in (("five_hour", "kurzfristig"), ("seven_day", "wöchentlich")):
        window = windows.get(key) or {}
        line = reset_line("Claude Code", label, window.get("resets_at"), window.get("used_percentage"), now)
        if line:
            result.append(line)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", choices=("codex", "claude-status"))
    args = parser.parse_args()
    try:
        data = codex_limits() if args.source == "codex" else json.load(sys.stdin)
        lines = export_lines(args.source, data, datetime.now(timezone.utc))
    except (OSError, ValueError, AttributeError):
        print("Quelle nicht verfügbar oder Format unbekannt.", file=sys.stderr)
        return 1
    if not lines:
        print("Keine eindeutig zuordenbare zukünftige Rücksetzzeit vorhanden.", file=sys.stderr)
        return 1
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
