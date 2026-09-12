#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
全链路多角度取证脚本（2026-09-11）
用途：E2E 跑完后，用【互相独立的多条证据】判断"完整链路是否全部通畅"，
      而不是只看 E2E 脚本自己的 summary（单一视角，会漏/会误报）。

七个观察角度（1~6 自动，7 交给浏览器/人工）：
  ① E2E 结果（完成/耗时/降级/问题）
  ② 逐阶段耗时（从 events[].ts 时间戳算，找出"谁最慢"）
  ③ 三服务日志交叉核对（主管发出的调用 vs 子Agent 收到的任务）
  ④ 结果回捞（精确 key / sinceMs 兜底 / 未命中）+ Hook 存档
  ⑤ /health 依赖探针（nacos / depsOk）
  ⑥ 产物质量（最终文本是否含 3 天、字段是否齐）
  ⑦ 前端浏览器视角（另用浏览器工具）

用法：python scripts/fullchain_probe.py [e2e_result7.json]
"""
import json
import re
import subprocess
import glob
import os
import sys

ROOT = "E:/gitee/aitripplan-agentscope"


def sh(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
        return r.stdout.decode("utf-8", "replace")
    except Exception as e:
        return f"<err {e}>"


def section(t):
    print("\n" + "=" * 70)
    print(t)
    print("=" * 70)


def main():
    if len(sys.argv) > 1:
        path = sys.argv[1]
    else:
        #★gitee 版：默认取目录下最新的 e2e_*.json（原来写死 e2e_result7.json，那是 v6 的文件名）
        _c = sorted(glob.glob(f"{ROOT}/e2e_*.json"), key=os.path.getmtime)
        path = _c[-1] if _c else f"{ROOT}/e2e_gitee_7d.json"
    try:
        with open(path, encoding="utf-8") as f:
            r = json.load(f)
    except Exception as e:
        print(f"❌ 读不到 {path}: {e}")
        return

    summary = r.get("summary") or {}
    events = r.get("events") or []
    # e2e_test.py 里 events 存的是「原始 JSON 字符串」（不是 list），这里统一成 list
    if isinstance(events, str):
        try:
            events = json.loads(events)
        except Exception:
            events = []
    if not isinstance(events, list):
        events = []
    probs = r.get("problems") or []

    # ---------- ① E2E 结果 ----------
    section("① E2E 结果（脚本视角）")
    print(f"sessionId : {r.get('sessionId')}")
    print(f"耗时      : {r.get('elapsedSec')}s")
    print(f"完成      : {r.get('done')}")
    print(f"事件条数  : {summary.get('事件条数')}")
    print(f"降级标志  : {json.dumps(summary.get('降级标志', []), ensure_ascii=False)}")
    print(f"问题数    : {len(probs)}")
    for p in probs:
        print(f"   ❌ {p[:130]}")

    # ---------- ② 逐阶段耗时 ----------
    section("② 逐阶段耗时（从事件时间戳算，找最慢的一段）")
    seq = summary.get("阶段序列") or []
    print("阶段序列: " + " → ".join(seq[:40]) if seq else "（无）")
    pts = []
    for e in events:
        try:
            ts = int(e.get("ts") or 0)
        except Exception:
            ts = 0
        if ts > 0:
            pts.append((ts, e.get("type") or "?", (e.get("content") or "")[:40]))
    if len(pts) >= 2:
        t0 = pts[0][0]
        total = (pts[-1][0] - t0) / 1000.0
        print(f"\n事件覆盖时长: {total:.1f}s（首事件 → 末事件）")
        gaps = []
        for i in range(1, len(pts)):
            gap = (pts[i][0] - pts[i - 1][0]) / 1000.0
            gaps.append((gap, pts[i - 1][1], pts[i][1], pts[i][2]))
        gaps.sort(reverse=True)
        print("最耗时的 8 个时间间隔（上一个事件 → 下一个事件）:")
        for gap, a, b, c in gaps[:8]:
            bar = "#" * max(1, int(gap / 3))
            print(f"  {gap:>7.1f}s  {a} → {b:<10} {bar}  {c}")
        slow_total = sum(g for g, _, _, _ in gaps if g >= 30)
        print(f"\n≥30s 的间隔合计: {slow_total:.1f}s（占 {100*slow_total/max(total,0.1):.0f}%）")
    else:
        print("（事件里没有可用时间戳）")

    # ---------- ③ 三服务日志交叉核对 ----------
    # 说明：子Agent 的 A2A 服务端不打「收到任务」日志，所以用两个可客观计数的锚点交叉：
    #   主管侧 [A2A] xxx 开始调用  = 发出去的调用数
    #   子Agent 侧 lastResult存档  = 真实跑完并落盘的产出数（Hook 记录）
    section("③ 三服务日志交叉核对（主管发出的调用 vs 子Agent 真实产出）")
    mgr, trip, route = f"{ROOT}/logs/manager_gitee.log", f"{ROOT}/logs/trip_gitee.log", f"{ROOT}/logs/route_gitee.log"
    sent_trip = sh('grep -ac "\\[A2A\\] 行程Agent 开始调用" ' + mgr).strip()
    sent_route = sh('grep -ac "\\[A2A\\] 路线Agent 开始调用" ' + mgr).strip()
    arch_trip = sh('grep -ac "lastResult存档" ' + trip).strip()
    arch_route = sh('grep -ac "lastResult存档" ' + route).strip()
    print(f"主管 → 行程Agent(8085): 发出 {sent_trip} 次 | 8085 落盘产出 {arch_trip} 条")
    print(f"主管 → 路线Agent(8082): 发出 {sent_route} 次 | 8082 落盘产出 {arch_route} 条")
    print("（发出次数 > 产出条数是正常的：被回捞取走的会被 take 掉，且失败的那次本就没有产出）")
    print("\n主管侧调用成败：")
    print(f"  第1次失败(触发重发) : {sh('grep -ac 第1次A2A调用失败 ' + mgr).strip()}")
    print(f"  重试后仍失败        : {sh('grep -ac 调用失败.重试后仍空 ' + mgr).strip()}")
    print(f"  回捞救回            : {sh('grep -ac 已从子Agent回捞到结果 ' + mgr).strip()}")

    # ---------- ④ 回捞 ----------
    section("④ 结果回捞明细（精确 key / sinceMs 兜底 / 未命中）")
    reflog = sh('grep -a "\\[回捞\\]" "' + mgr + '"').strip()
    if reflog:
        for ln in reflog.splitlines()[-8:]:
            print("  " + ln.strip()[:160])
    else:
        print("  （本次未触发回捞 = 没有需要救的调用，是好事）")
    print("\n8085 侧被查记录：")
    for ln in sh('grep -a "\\[lastResult\\]" "' + trip + '"').splitlines()[-5:]:
        print("  " + ln.strip()[:160])
    print("\nHook 存档（子Agent 产出是否落盘）：")
    for ln in sh(f'grep -a "lastResult存档" "{trip}"').splitlines()[-5:]:
        print("  " + ln.strip()[:160])

    # ---------- ⑤ /health ----------
    section("⑤ /health 依赖探针（nacos / depsOk）")
    for p, name in ((8081, "manager"), (8082, "routeMaking"), (8085, "tripPlanner")):
        out = sh(f'curl -s -m 5 http://localhost:{p}/health').strip()
        try:
            j = json.loads(out)
            ok = "✅" if j.get("depsOk") else "❌"
            print(f"  {ok} {name:<12} status={j.get('status')}  nacos={j.get('nacos')}  "
                  f"modelCalls={j.get('modelCalls')}")
        except Exception:
            print(f"  ❓ {name:<12} {out[:110]}")

    # ---------- ⑥ 产物质量 ----------
    section("⑥ 产物质量（最终行程内容）")
    text = ""
    for e in events:
        c = e.get("content") or ""
        if len(c) > len(text) and ("第1天" in c or "第 1 天" in c or "天" in c):
            text = c
    if not text:
        text = max((e.get("content") or "" for e in events), key=len, default="")
    print(f"最长事件文本: {len(text)} 字符")
    days = sorted(set(int(d) for d in re.findall(r"第\s*([1-9])\s*天", text)))
    print(f"出现『第N天』: {days if days else '（无）'}")
    for field in ("景点", "酒店", "餐饮", "交通", "预算", "自驾"):
        print(f"  含『{field}』: {'是' if field in text else '否'}")
    names = re.findall(r"(?:第\s*\d\s*天[^\n]{0,60})", text)
    if names:
        print("\n各天标题片段：")
        for n in names[:6]:
            print("  " + n.strip()[:80])

    section("判读：①③④⑤ 无 ❌ 且 ⑥ 有 3 天，才算『完整链路通畅』")


if __name__ == "__main__":
    main()
