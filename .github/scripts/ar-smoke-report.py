#!/usr/bin/env python3
"""Summarises ar-smoke-out/ (see ar-smoke.sh) as workflow annotations and check-run payloads.

The raw job log and artifacts are not always reachable, so the crash buffer, the app's warnings and
small screenshots are published through the Checks API (output.summary / output.text, 65 535 chars
each), which is readable with `gh api repos/<repo>/commits/<sha>/check-runs`.
"""
import base64
import glob
import json
import os
import re
import subprocess
import sys

OUT = "ar-smoke-out"
LIMIT = 65000
TAGS = ("AndroidRuntime", "ArSkyGlView", "ArAssets", "ArCameraBinder", "ArGlyphCache", "System.err",
        "CameraX", "Camera2CameraImpl", "PreviewView", "OpenGLRenderer", "libEGL", "EGL_emulation",
        "TestRunner", "ActivityManager", "art", "DEBUG", "Compose", "ArSky")


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def read(path: str) -> str:
    try:
        with open(path, errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def app_pid_lines(logcat: str) -> list:
    pids = set(re.findall(r"Start proc (\d+):com\.alijafari\.red\.astronomy/", logcat))
    pids |= set(re.findall(r"\s(\d+)\s+\d+\s+[A-Z]\s+AndroidRuntime: Process: com\.alijafari", logcat))
    keep = []
    for line in logcat.splitlines():
        parts = line.split()
        if len(parts) < 6:
            continue
        pid, level = parts[2], parts[4]
        tag = parts[5].rstrip(":")
        mine = pid in pids
        if mine and level in ("E", "W", "F"):
            keep.append(line)
        elif tag in TAGS and level in ("E", "F"):
            keep.append(line)
        elif "FATAL" in line or "com.alijafari" in line and level in ("E", "W", "F"):
            keep.append(line)
    return keep


def main() -> int:
    labels = sorted({os.path.basename(p).split(".")[0] for p in glob.glob(f"{OUT}/*.instrument.txt")})
    device = read(f"{OUT}/device.txt").strip()
    sha = os.environ.get("GITHUB_SHA", "")
    summary = [f"Device: {device}", ""]
    text_parts = []
    crashed_any = False
    for label in labels:
        inst = read(f"{OUT}/{label}.instrument.txt")
        crash = read(f"{OUT}/{label}.crash.txt")
        logcat = read(f"{OUT}/{label}.logcat.txt")
        crashed = "FATAL EXCEPTION" in crash or "Process crashed" in inst
        if crashed:
            status = "CRASH"
        elif "OK (1 test)" in inst:
            status = "OK"
        elif "FAILURES!!!" in inst:
            status = "FAILED"
        else:
            status = "UNKNOWN"
        crashed_any |= crashed
        summary.append(f"- {label}: **{status}**")
        print(f"::notice title=AR smoke {label}::{status}")
        if crashed:
            m = re.search(r"FATAL EXCEPTION.*?(?=\n\S|\Z)", crash, re.S)
            block = crash[crash.find("FATAL EXCEPTION"):][:3500] if "FATAL EXCEPTION" in crash else inst[-3500:]
            print(f"::error title=AR smoke crash {label}::{esc(block)}")
        # Thread dumps printed after a crash (THREAD_STATE), the emulator's CameraX front-camera retries and
        # the ANR CPU tables would crowd out the lines that matter.
        noise = (" THREAD_STATE", "CameraValidator", "CameraX : \tat ", "CameraX : Caused by", "CameraX : Retry init",
                 "CameraX : androidx.camera", "CameraX : \t...", "ActivityManager:   ", "ActivityManager: CPU usage")
        lines = [ln for ln in app_pid_lines(logcat) if not any(n in ln for n in noise)]
        # ANR: the system's summary (reason) and the main thread's stack from /data/anr.
        anr_summary = []
        all_lines = logcat.splitlines()
        for idx, ln in enumerate(all_lines):
            if "ANR in" in ln:
                anr_summary.extend(all_lines[idx:idx + 8])
        anr_trace = read(f"{OUT}/{label}.anr.txt")
        main_stack = ""
        m = re.search(r'"main".*?(?=\n\n|\Z)', anr_trace, re.S)
        if m:
            main_stack = m.group(0)[:6000]
        if anr_summary or main_stack:
            status = "ANR" if not crashed else status
            summary[-1] = f"- {label}: **{status}**"
            print(f"::error title=AR smoke ANR {label}::{esc((chr(10).join(anr_summary) + chr(10) + main_stack)[:3500])}")
        gfx = read(f"{OUT}/{label}.gfxinfo.txt")
        gfx_lines = [ln for ln in gfx.splitlines() if any(k in ln for k in ("Total frames", "Janky frames", "50th percentile", "90th percentile", "95th percentile", "99th percentile", "Slow UI thread", "Slow bitmap", "Slow issue draw", "Frame deadline missed"))][:12]
        text_parts.append(
            f"\n\n## {label}: {status}\n\n### instrumentation\n```\n{inst[-4000:]}\n```\n"
            f"### crash buffer\n```\n{crash[:20000]}\n```\n"
            f"### ANR\n```\n" + "\n".join(anr_summary)[:4000] + "\n" + main_stack + "\n```\n"
            f"### frames (gfxinfo)\n```\n" + "\n".join(gfx_lines) + "\n```\n"
            f"### app warnings/errors ({len(lines)} lines)\n```\n" + "\n".join(lines[-250:])[-20000:] + "\n```\n"
        )

    os.makedirs(f"{OUT}/checks", exist_ok=True)
    payloads = []
    text = "".join(text_parts)
    chunks = [text[i:i + LIMIT] for i in range(0, max(len(text), 1), LIMIT)] or [""]
    for i, chunk in enumerate(chunks):
        payloads.append({
            "name": f"AR smoke logs {i + 1}/{len(chunks)}",
            "head_sha": sha,
            "status": "completed",
            "conclusion": "failure" if crashed_any else "success",
            "output": {"title": "AR screen emulator smoke run", "summary": "\n".join(summary)[:LIMIT], "text": chunk},
        })

    for label in labels:
        png = f"{OUT}/{label}.png"
        if not os.path.exists(png) or os.path.getsize(png) == 0:
            continue
        jpg = f"{OUT}/{label}.jpg"
        for tool in (["convert"], ["magick"]):
            try:
                subprocess.run(tool + [png, "-resize", "432x", "-quality", "72", jpg], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                break
            except Exception:
                continue
        if not os.path.exists(jpg):
            continue
        b64 = base64.b64encode(open(jpg, "rb").read()).decode()
        parts = [b64[i:i + LIMIT] for i in range(0, len(b64), LIMIT)]
        for i, part in enumerate(parts):
            payloads.append({
                "name": f"AR smoke screenshot {label} {i + 1}/{len(parts)}",
                "head_sha": sha,
                "status": "completed",
                "conclusion": "neutral",
                "output": {"title": f"{label} screenshot (base64 JPEG part {i + 1}/{len(parts)})",
                           "summary": part},
            })

    # Moon renders from MoonShowcaseRenderTest (already JPEG), one check run per image part.
    renders = "app/build/moon-renders"
    if os.path.isdir(renders):
        scenes = read(f"{renders}/scenes.txt")
        payloads.append({
            "name": "Moon renders index",
            "head_sha": sha,
            "status": "completed",
            "conclusion": "neutral",
            "output": {"title": "Moon showcase scenes", "summary": scenes[:LIMIT] or "(no scenes.txt)"},
        })
        for name in sorted(os.listdir(renders)):
            if not name.endswith(".jpg"):
                continue
            b64 = base64.b64encode(open(f"{renders}/{name}", "rb").read()).decode()
            parts = [b64[i:i + LIMIT] for i in range(0, len(b64), LIMIT)]
            for i, part in enumerate(parts):
                payloads.append({
                    "name": f"Moon render {name} {i + 1}/{len(parts)}",
                    "head_sha": sha,
                    "status": "completed",
                    "conclusion": "neutral",
                    "output": {"title": f"{name} (base64 JPEG part {i + 1}/{len(parts)})", "summary": part},
                })

    for i, p in enumerate(payloads):
        with open(f"{OUT}/checks/{i:02d}.json", "w") as f:
            json.dump(p, f)
    print(f"{len(payloads)} check-run payloads written; crashed_any={crashed_any}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
