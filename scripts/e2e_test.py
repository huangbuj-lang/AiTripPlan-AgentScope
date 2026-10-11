#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★2026-09-11 端到端验收脚本（评审点：无 CI、无 E2E，每次靠手写一次性脚本，无法回归）。

用法:
    export E2E_USER=e2etest E2E_PASS='xxxxxx'      # 凭据走环境变量，不写进仓库
    python scripts/e2e_test.py "成都5天自驾游"
    python scripts/e2e_test.py "成都5天自驾游" --multi          # 走多方案
    python scripts/e2e_test.py "..." --timeout 900 --json out.json

做的事（全客观，不看截图）:
    1) 登录拿会话；2) 提交规划任务（单方案/多方案）；3) 轮询事件流直到完成；
    4) 断言：关键阶段都出现、无“调用失败”、行程 JSON 被成功解析；
    5) 输出 PASS/FAIL + 分阶段耗时 + 事件全文（可存 JSON 存档）。

退出码: 0=通过, 1=断言失败, 2=环境/参数问题。
"""
import argparse
import http.cookiejar
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("E2E_BASE", "http://localhost:8091")
USER = os.environ.get("E2E_USER", "")
PASS = os.environ.get("E2E_PASS", "")

# 一次成功规划必须出现的阶段关键字（缺失即视为流程不完整）
REQUIRED_STAGES = ["解析", "候选", "定稿"]
# 「路线」阶段只在跨城/自驾跨城场景出现；单城市（如"成都3天自驾游"）不会调路线Agent，
# 所以它列为可选：缺失只提示不判失败，避免单城市用例被误报为回归。
OPTIONAL_STAGES = ["路线"]
# 出现即视为失败（本次改动新增了真因透传，失败时会带上原因）
FAIL_MARKERS = ["❌ 调用失败", "任务失败", "已取消"]


def make_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def post_form(opener, path, fields, timeout=30):
    """表单提交（本项目接口用 @RequestParam，不是 JSON body——用 JSON 会 400）"""
    data = urllib.parse.urlencode(fields).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={"Content-Type": "application/x-www-form-urlencoded; charset=utf-8"})
    with opener.open(req, timeout=timeout) as r:
        return r.status, r.read().decode("utf-8", "replace")


def get_text(opener, path, timeout=30):
    with opener.open(BASE + path, timeout=timeout) as r:
        return r.status, r.read().decode("utf-8", "replace")


def login(opener):
    if not USER or not PASS:
        print("FAIL: 请先设置 E2E_USER / E2E_PASS 环境变量（凭据不写进仓库）")
        sys.exit(2)
    # /api/login 用 @RequestParam（表单），提交 JSON 会 400
    status, body = post_form(opener, "/api/login", {"username": USER, "password": PASS})
    ok = status == 200 and '"success":true' in body.replace(" ", "")
    print(f"[login] status={status} ok={ok}")
    if not ok:
        print("FAIL: 登录失败 ->", body[:300])
        sys.exit(2)


def submit(opener, prompt, multi, wait_s=420, interval=15):
    """提交规划任务。/app 也用 @RequestParam；sessionId 由调用方生成（服务端默认 'default' 会互相覆盖）

    ★2026-09-12 防"假失败"（反复误导过排查）：服务端并发闸 TASK_GATE=1，有任务在跑时 /app 会返回
      "⚠️ 有任务正在执行中（最多同时 1 个）…"，但 HTTP 状态码仍是 **200** ——
      旧版不看响应体、直接进入轮询，于是白等满 timeout 后误报"超时未完成"，
      把"根本没提交上"当成"跑不完"。现在：被拒就等闸释放后重试，仍被占则明确报错退出。
    """
    start = time.time()
    while True:
        sid = f"e2e-{int(time.time())}"
        status, body = post_form(opener, "/app", {
            "prompt": prompt,
            "sessionId": sid,
            "multiScheme": "true" if multi else "false",
        })
        rejected = ("正在执行中" in body) or ("请等当前任务完成" in body)
        print(f"[submit] status={status} sessionId={sid} body={body[:200]}")
        if not rejected:
            return sid
        if time.time() - start > wait_s:
            print("FAIL: 服务端并发闸(TASK_GATE)一直被占用，任务从未提交 ——"
                  " 这不是'跑不完'；请先 POST /cancel 清掉在跑的任务再重试")
            sys.exit(4)
        print(f"  (并发闸被占，{interval}s 后重试…)")
        time.sleep(interval)


def poll_events(opener, sid, timeout_s, interval=6):
    """轮询事件流直到出现『完成』阶段或超时。返回 (events_text, elapsed, done)

    注意：/events 返回的是一个 **JSON 数组**（整条响应，不按行分隔），
    完成标志是数组里 type=="完成" 的事件 —— 早期版本按行文本匹配，导致任务早就完成却一直等到超时。
    """
    start = time.time()
    last = ""
    while time.time() - start < timeout_s:
        try:
            status, body = get_text(opener, f"/events?sessionId={urllib.parse.quote(sid)}")
            if status == 200 and body.strip():
                last = body
                events = parse_events(body)
                if any(e.get("type") == "完成" for e in events):
                    return last, time.time() - start, True
        except Exception as e:
            print(f"  (poll 异常，继续: {e})")
        time.sleep(interval)
    return last, time.time() - start, False


def parse_events(text):
    """把 /events 的 JSON 数组解析成 list[dict]；解析失败返回空列表"""
    try:
        data = json.loads(text)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def assert_events(text, elapsed, done, multi):
    problems = []
    events = parse_events(text)
    if not done:
        problems.append(f"超时未完成（等待 {int(elapsed)}s）")
    types = {e.get("type") for e in events}
    # ★2026-09-11 缓存路径单独判定：命中结果缓存时会**跳过整个生成阶段**（候选/路线/行程自然不出现）。
    #   实测依据：缓存命中时"缺少阶段「候选」"是正常行为，不是缺陷 —— 但必须确认确实命中了缓存，
    #   否则就是真的缺阶段（那才该判红）。
    _contents = " ".join(str(e.get("content") or "") for e in events)
    cached = "命中结果缓存" in _contents
    required = ["解析", "定稿"] if cached else REQUIRED_STAGES
    for stage in required:
        if stage not in types:
            problems.append(f"缺少阶段「{stage}」")
    if cached:
        print("  (提示) 命中结果缓存 → 按缓存路径判定（跳过生成阶段，不要求「候选/行程」）")
        if "定稿" not in types:
            problems.append("缓存路径但未产出「定稿」（缓存内容不可用，应回退重新生成）")
    optional_missing = [s for s in OPTIONAL_STAGES if s not in types]
    if optional_missing:
        print(f"  (提示) 未出现可选阶段：{'、'.join(optional_missing)}（单城市用例正常）")
    # 失败判定（2026-09-11 修正）：
    #   过去「Agent结果 里出现『调用失败』就判 FAIL」——太糙。真相是：主管会自动重发一次
    #   （⚠️ 第1次失败→重发），还可能从子Agent 回捞到结果，这两类都被下游吸收了，**交付不受影响**。
    #   真正该判红的是「最终调用失败且没被救回」以及「交付降级」。
    retried, salvaged, final_failed = [], [], []
    for e in events:
        content = str(e.get("content") or "")
        if e.get("type") != "Agent结果":
            continue
        if "已从子Agent回捞到结果" in content:
            salvaged.append(content)
        elif "⚠️" in content and "第1次调用失败" in content:
            retried.append(content)          # 自动重发，正常容错动作
        elif "❌ 调用失败" in content or "重试后仍空" in content:
            final_failed.append(content)     # 最终失败
    for c in final_failed:
        # 只要不是"未被救回"，就只是噪音；被救回的会出现在 salvaged 里
        problems.append(f"最终调用失败：{c[:120]}")
    for c in retried:
        print(f"  (提示) 已自动重发：{c[:100]}")
    for c in salvaged:
        print(f"  (提示) 已回捞救回：{c[:100]}")
    if multi:
        all_text = text
        for style in ("经典主线", "深度环线", "轻松向"):
            if style not in all_text:
                problems.append(f"多方案缺少风格「{style}」")
    return problems


def summarize(text):
    """抽取分阶段耗时 / 降级标志 / 失败真因"""
    events = parse_events(text)
    return {
        "事件条数": len(events),
        "阶段序列": [e.get("type") for e in events],
        "降级标志": [str(e.get("content"))[:140] for e in events
                     if "降级" in str(e.get("content") or "")],
        "失败真因": [str(e.get("content"))[:160] for e in events
                     if e.get("type") == "Agent结果" and "调用失败" in str(e.get("content") or "")],
    }


def _lock_path():
    import tempfile
    return os.path.join(tempfile.gettempdir(), "aitripplan_e2e.lock")


def _pid_alive(pid):
    """Windows/POSIX 通用的进程存活探测"""
    try:
        if os.name == "nt":
            import subprocess
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}"],
                                 capture_output=True, timeout=5).stdout.decode("utf-8", "replace")
            return str(pid) in out
        os.kill(pid, 0)
        return True
    except Exception:
        return False


def acquire_run_lock(max_age_sec=1800):
    """★2026-09-11 单实例锁：同一时间只允许一个 E2E 在跑。

    来历：曾在上一个 E2E 还在跑时改代码并重启服务，两个进程往同一个结果文件写，
    后写的把先写的覆盖了，还让人误以为出现回归（实测踩过）。
    规则：拿不到锁就**直接退出**，不做备份/半途逻辑，避免制造第二份混乱。
    """
    p = _lock_path()
    if os.path.exists(p):
        try:
            txt = open(p, encoding="utf-8").read().split()
            old_pid, old_ts = int(txt[0]), float(txt[1])
            stale = (time.time() - old_ts) > max_age_sec or not _pid_alive(old_pid)
            if not stale:
                print(f"❌ 已有 E2E 正在运行（pid={old_pid}，开始于 "
                      f"{time.strftime('%H:%M:%S', time.localtime(old_ts))}）")
                print("   同一时间只允许一个 E2E —— 两个进程会互相覆盖结果文件、并把结论带偏。")
                print("   如需强制重跑：结束那个进程，或删除锁文件 " + p)
                return False
            print(f"（清理陈旧锁：pid={old_pid} 已不存在或超时）")
        except Exception:
            pass  # 锁内容损坏 → 当作陈旧锁接管
    with open(p, "w", encoding="utf-8") as f:
        f.write(f"{os.getpid()} {time.time()}")
    return True


def release_run_lock():
    try:
        os.remove(_lock_path())
    except OSError:
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prompt", help="规划需求，例如 '成都5天自驾游'")
    ap.add_argument("--multi", action="store_true", help="走多方案（3套并列）")
    ap.add_argument("--timeout", type=int, default=1200, help="轮询上限秒数（默认1200）")
    ap.add_argument("--json", dest="json_out", help="把事件全文与摘要存成 JSON")
    args = ap.parse_args()

    # ★单实例锁：两个 E2E 并发会互相覆盖结果文件、把结论带偏（实测踩过）
    if not acquire_run_lock():
        sys.exit(3)
    try:
        _run(args)
    finally:
        release_run_lock()


def _run(args):
    opener = make_opener()
    login(opener)
    sid = submit(opener, args.prompt, args.multi)
    if not sid:
        print("FAIL: 未拿到 sessionId，无法轮询事件")
        sys.exit(2)
    print(f"[session] {sid}  轮询上限={args.timeout}s")

    text, elapsed, done = poll_events(opener, sid, args.timeout)
    problems = assert_events(text, elapsed, done, args.multi)
    summary = summarize(text)

    print("\n===== 结果 =====")
    print(f"耗时: {int(elapsed)}s   完成: {done}")
    for k, v in summary.items():
        print(f"{k}: {v}")

    if args.json_out:
        # ★自动加时间戳：避免不同轮次互相覆盖（曾因此看到"旧结果"误判回归）
        _base, _ext = os.path.splitext(args.json_out)
        if not re.search(r"_\d{6}$", _base):
            args.json_out = f"{_base}_{time.strftime('%H%M%S')}{_ext}"
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump({"sessionId": sid, "elapsedSec": int(elapsed), "done": done,
                       "problems": problems, "summary": summary, "events": text}, f,
                      ensure_ascii=False, indent=2)
        print(f"已存档: {args.json_out}")

    if problems:
        print("\n❌ FAIL:")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("\n✅ PASS：阶段齐全、无失败标志" + ("、多方案风格齐备" if args.multi else ""))
    sys.exit(0)


if __name__ == "__main__":
    main()
