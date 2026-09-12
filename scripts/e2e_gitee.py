#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gitee 版端到端测试：登录 → 提交任务 → SSE 收进度 → 取结果。

重点观察本轮新增能力（这些是验证目标）：
  · 交付前校验事件（阶段名「校验」）
  · 硬违反 → 打回重做（"发现 N 条硬违反，正在按修正清单重做"）
  · 重复仍不过 → 降级交付标注
  · 子Agent 回传【真实地点清单】→ "已获得真实地点清单 N 条"
  · 多方案按实际路线差异（主路线/环线换序/替代路线）

用法：
  python scripts/e2e_gitee.py "成都3天自驾游" --timeout 900 --json out.json
  python scripts/e2e_gitee.py "深圳到川西14日自驾游" --multi --timeout 1500
"""
import argparse
import http.cookiejar
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"


def post_form(opener, path, data, timeout=120):
    body = urllib.parse.urlencode(data).encode()
    req = urllib.request.Request(BASE + path, data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    with opener.open(req, timeout=timeout) as r:
        return r.status, r.read().decode("utf-8", "replace")


def get(opener, path, timeout=60):
    with opener.open(BASE + path, timeout=timeout) as r:
        return r.status, r.read().decode("utf-8", "replace")


def submit(opener, prompt, multi, timeout_s=420, interval=15.0):
    """提交任务；被并发闸拒绝时退避重试（gitee 同 v6：返回 200 + 提示文案）。"""
    deadline = time.time() + timeout_s
    while True:
        sid = f"e2e-{int(time.time())}"
        st, body = post_form(opener, "/app", {
            "prompt": prompt, "sessionId": sid,
            "multiScheme": "true" if multi else "false"})
        if "有任务正在执行中" not in body:
            print(f"[submit] status={st} sessionId={sid} body={body[:150]}")
            return sid
        if time.time() > deadline:
            print("[submit] ❌ 并发闸一直被占用，放弃")
            sys.exit(2)
        print(f"[submit] 并发闸占用中，{interval:.0f}s 后重试…")
        time.sleep(interval)


def watch(opener, sid, timeout_s):
    """读 SSE 事件流，收集事件直到出现「完成」或超时。"""
    events, t0 = [], time.time()
    url = f"{BASE}/events/stream?sessionId={urllib.parse.quote(sid)}"
    req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    try:
        with opener.open(req, timeout=timeout_s + 60) as r:
            for raw in r:
                if time.time() - t0 > timeout_s:
                    print(f"[watch] 到达超时上限 {timeout_s}s，停止监听")
                    break
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if not line.startswith("data:"):
                    continue
                try:
                    ev = json.loads(line[5:].strip())
                except Exception:
                    continue
                events.append(ev)
                t = ev.get("type", "")
                c = ev.get("content", "")
                mark = ""
                if "校验" in t or "校验" in c:
                    mark = "  ★"
                print(f"  [{time.time()-t0:6.0f}s] {t}: {c[:150]}{mark}")
                if t == "完成":
                    break
    except Exception as e:
        print(f"[watch] 流结束/异常: {type(e).__name__}: {e}")
    return events, time.time() - t0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prompt")
    ap.add_argument("--user", default="e2etest")
    ap.add_argument("--password", default="e2e123456")
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--multi", action="store_true")
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

    try:
        st, body = post_form(opener, "/api/login", {"username": args.user, "password": args.password})
        ok = '"success":true' in body.replace(" ", "")
        print(f"[login] status={st} ok={ok}")
        if not ok:
            print("  登录失败：", body[:200])
            sys.exit(1)
    except Exception as e:
        print(f"[login] 异常: {e}（服务没起？）")
        sys.exit(1)

    sid = submit(opener, args.prompt, args.multi)
    print(f"[session] {sid}  监听上限={args.timeout}s")
    events, elapsed = watch(opener, sid, args.timeout)

    # 统计本轮新增能力的落地情况
    types = [e.get("type", "") for e in events]
    texts = "\n".join(e.get("content", "") for e in events)
    checks = {
        "有校验事件（新）": "校验" in types,
        "有打回重做": "正在按修正清单重做" in texts,
        "校验判定出结果(非全unknown)": "已判定 0 天" not in texts,
        "有降级交付标注": "降级交付" in texts,
        "有真实地点清单回传": "真实地点清单" in texts,
        "有可行性预检提示": "需约" in texts and "超过用户要求的" in texts,
        "完成": "完成" in types,
    }
    st, result = get(opener, f"/result?sessionId={urllib.parse.quote(sid)}")
    print("\n===== 结果 =====")
    print(f"耗时: {elapsed:.0f}s   事件: {len(events)} 条   结果长度: {len(result)}")
    print("阶段序列:", [t for t in types if t not in ("校验",)][:20])
    print("\n----- 本轮新增能力落地情况 -----")
    for k, v in checks.items():
        print(f"  {'✅' if v else '❌'} {k}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"sessionId": sid, "elapsedSec": elapsed, "checks": checks,
                       "events": events, "result": result}, f, ensure_ascii=False, indent=1)
        print(f"\n已存档: {args.json}")
    sys.exit(0 if checks["完成"] else 1)


if __name__ == "__main__":
    main()
