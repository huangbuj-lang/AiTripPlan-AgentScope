#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AiTripPlan Evals 自动评测脚本（PDF §二十二）

对 tests/evals/cases.json 中的用例逐个调用 8081 主管服务（POST /app → 轮询 /events → GET /result），
解析结构化行程 JSON，计算以下指标并输出 Markdown 报告：

  - destination_match 目的地符合率：行程目的地/文本包含期望目的地
  - days_match        天数符合率：plan.days == 期望天数
  - budget_compliance 预算符合率：行程费用明细累加 ≤ 预算上限（用例给了预算才计）
  - P0_satisfaction   需求满足率：must_contain 全部出现 且 avoid_contain 全不出现
  - structure_complete 结构完整率：有 timeline 与 stay 区块
  - duration          平均/最大耗时（秒）

用法：
  python tests/evals/run_evals.py                     # 全量跑（默认 8081）
  python tests/evals/run_evals.py --limit 10          # 只跑前10条（快速回归）
  python tests/evals/run_evals.py --category 亲子     # 只跑某类
  python tests/evals/run_evals.py --base http://127.0.0.1:8081 --timeout 420

前置：主管(8081)/路线(8082)/行程(8085)/Nacos 已启动；结果为旧版自由文本时按降级口径统计。
"""
import argparse
import http.cookiejar
import json
import re
import sys
import time
import urllib.request
import urllib.parse
from datetime import datetime
from pathlib import Path

CASES_FILE = Path(__file__).parent / "cases.json"
MONEY_RE = re.compile(r"(\d+(?:\.\d+)?)")


def make_session(base: str):
    """注册+登录拿会话 Cookie（/app /result 需登录态），返回带 Cookie 的 opener。"""
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    user = f"eval_{int(time.time())}"
    r = opener.open(urllib.request.Request(
        f"{base}/api/register?username={urllib.parse.quote(user)}&password=123456",
        data=b"", method="POST"), timeout=15).read().decode("utf-8", "replace")
    if '"success":true' not in r:
        print(f"⚠️ 注册返回: {r[:80]}（可能已存在，继续尝试登录）")
    r = opener.open(urllib.request.Request(
        f"{base}/api/login?username={urllib.parse.quote(user)}&password=123456",
        data=b"", method="POST"), timeout=15).read().decode("utf-8", "replace")
    if '"success":true' not in r:
        raise RuntimeError(f"评测用户登录失败: {r[:80]}")
    return opener


def http_post(opener, url: str) -> str:
    req = urllib.request.Request(url, data=b"", method="POST")
    with opener.open(req, timeout=30) as resp:
        return resp.read().decode("utf-8", "replace")


def http_get(opener, url: str, timeout: int = 30) -> str:
    with opener.open(url, timeout=timeout) as resp:
        return resp.read().decode("utf-8", "replace")


def plan_costs(plan: dict) -> float:
    """累加 timeline.cost 与 stay.price 可解析金额（与 ConstraintEngine 同口径的简化版）"""
    total = 0.0
    for sec in plan.get("sections") or []:
        for it in sec.get("items") or []:
            raw = it.get("cost") if sec.get("type") == "timeline" else it.get("price")
            if not raw:
                continue
            raw = str(raw)
            if "免费" in raw or "不含" in raw:
                continue
            m = MONEY_RE.search(raw.replace(",", ""))
            if m:
                v = float(m.group(1))
                if "万" in raw:
                    v *= 10000
                total += v
    return total


def eval_case(plan_raw: str, expect: dict) -> dict:
    """单用例指标计算（结构化优先，降级文本按原文口径）"""
    out = {
        "structured": False, "destination": None, "days": None,
        "destination_match": None, "days_match": None,
        "budget_compliance": None, "P0_satisfaction": None,
        "structure_complete": None, "cost_total": None,
    }
    plan = None
    try:
        node = json.loads(plan_raw)
        if isinstance(node, dict):
            if node.get("plans") and isinstance(node["plans"], list):
                idx = node.get("recommendedIndex") or 0
                plan = node["plans"][idx] if idx < len(node["plans"]) else node["plans"][0]
            elif node.get("sections") or node.get("title"):
                plan = node
    except (json.JSONDecodeError, KeyError, IndexError, TypeError):
        plan = None

    if plan:
        out["structured"] = True
        dest = str(plan.get("destination") or "")
        out["destination"] = dest
        out["days"] = plan.get("days")
        sections = plan.get("sections") or []
        text = json.dumps(plan, ensure_ascii=False)

        exp_dest = expect.get("destination")
        if exp_dest:
            out["destination_match"] = exp_dest in dest or exp_dest in text
        exp_days = expect.get("days")
        if exp_days:
            out["days_match"] = (plan.get("days") == exp_days)
        has_timeline = any(s.get("type") == "timeline" and s.get("items") for s in sections)
        has_stay = any(s.get("type") == "stay" and s.get("items") for s in sections)
        out["structure_complete"] = has_timeline and has_stay
        out["cost_total"] = round(plan_costs(plan), 2)
    else:
        text = plan_raw

    # P0 需求满足（结构化与降级口径统一按文本）
    must = expect.get("must_contain") or []
    avoid = expect.get("avoid_contain") or []
    if must or avoid:
        ok = all(k in text for k in must) and all(k not in text for k in avoid)
        out["P0_satisfaction"] = ok

    # 预算（含降级文本粗提取）
    bmax = expect.get("budget_max")
    if bmax:
        cost = out.get("cost_total")
        if cost is None:
            # 降级：把原文所有金额粗累加会严重虚高，只对结构化结果判预算
            out["budget_compliance"] = None
        else:
            out["budget_compliance"] = cost <= bmax
    return out


def rate(values) -> float:
    vals = [v for v in values if v is not None]
    return round(100.0 * sum(1 for v in vals if v) / len(vals), 1) if vals else 0.0


def main() -> int:
    ap = argparse.ArgumentParser(description="AiTripPlan evals runner")
    ap.add_argument("--base", default="http://127.0.0.1:8081")
    ap.add_argument("--timeout", type=int, default=420, help="单用例完成等待上限(秒)")
    ap.add_argument("--limit", type=int, default=0, help="只跑前N条(0=全量)")
    ap.add_argument("--category", default="", help="只跑指定 category")
    args = ap.parse_args()

    data = json.loads(CASES_FILE.read_text(encoding="utf-8"))
    cases = data["cases"]
    if args.category:
        cases = [c for c in cases if c["category"] == args.category]
    if args.limit:
        cases = cases[: args.limit]
    if not cases:
        print("无匹配用例")
        return 1

    # 服务健康预检 + 登录会话
    try:
        http_get(urllib.request.build_opener(), args.base + "/stats", timeout=5)
    except Exception as e:
        print(f"❌ 主管服务({args.base})不可达: {e}\n请先启动 Nacos + 三个 Agent（start-local.sh）")
        return 2
    try:
        opener = make_session(args.base)
    except Exception as e:
        print(f"❌ 评测会话建立失败: {e}")
        return 2

    sid = f"eval-{int(time.time())}"
    rows = []
    for i, case in enumerate(cases, 1):
        expect = case["expect"]
        row = {"id": case["id"], "category": case["category"], "status": "OK"}
        t0 = time.time()
        try:
            q = urllib.parse.urlencode({"prompt": case["prompt"], "sessionId": sid, "skipPrefs": "true"})
            ack = http_post(opener, f"{args.base}/app?{q}")
            if "任务已接收" not in ack:
                row["status"] = "REJECTED: " + ack[:40]
                rows.append(row)
                continue
            deadline = time.time() + args.timeout
            result = ""
            while time.time() < deadline:
                time.sleep(3)
                result = http_get(opener, f"{args.base}/result?sessionId={urllib.parse.quote(sid)}")
                if "还在执行中" not in result:
                    break
            row["duration_s"] = round(time.time() - t0, 1)
            if "还在执行中" in result:
                row["status"] = "TIMEOUT"
            elif result.startswith("❌") or result.startswith("⏹"):
                row["status"] = result[:40]
            else:
                row.update(eval_case(result, expect))
        except Exception as e:
            row["status"] = f"ERROR: {e}"[:60]
        rows.append(row)
        done = {k: row.get(k) for k in ("destination_match", "days_match", "budget_compliance", "P0_satisfaction", "structure_complete")}
        print(f"[{i}/{len(cases)}] {row['id']} {row['status']} {done} {row.get('duration_s', '-')}s")

    # 汇总
    ok_rows = [r for r in rows if r["status"] == "OK"]
    def rate2(key):
        vals = [r.get(key) for r in ok_rows if r.get(key) is not None]
        return (round(100.0 * sum(1 for v in vals if v) / len(vals), 1), len(vals))
    d_rate, d_n = rate2("destination_match")
    dy_rate, dy_n = rate2("days_match")
    b_rate, b_n = rate2("budget_compliance")
    p_rate, p_n = rate2("P0_satisfaction")
    s_rate, s_n = rate2("structure_complete")
    summary = {
        "运行时间": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "用例数": len(rows),
        "成功": len(ok_rows),
        "失败/超时": len(rows) - len(ok_rows),
        "目的地符合率": f"{d_rate}% (n={d_n})",
        "天数符合率": f"{dy_rate}% (n={dy_n})",
        "预算符合率": (f"{b_rate}% (n={b_n})" if b_n else "无预算断言用例"),
        "需求满足率(P0)": f"{p_rate}% (n={p_n})",
        "结构完整率": f"{s_rate}% (n={s_n})",
        "平均耗时(s)": round(sum(r["duration_s"] for r in ok_rows) / len(ok_rows), 1) if ok_rows else 0,
        "最大耗时(s)": max((r["duration_s"] for r in ok_rows), default=0),
    }

    def fmt(v):
        return v if isinstance(v, str) else f"{v}%"

    report = Path(__file__).parent / f"report_{datetime.now().strftime('%Y%m%d_%H%M')}.md"
    lines = [
        "# AiTripPlan Evals 报告",
        "",
        f"- 时间：{summary['运行时间']} ｜ 用例：{summary['用例数']} ｜ 成功 {summary['成功']} ｜ 失败/超时 {summary['失败/超时']}",
        "",
        "| 指标 | 值 |", "|---|---|",
        f"| 目的地符合率 | {fmt(summary['目的地符合率'])} |",
        f"| 天数符合率 | {fmt(summary['天数符合率'])} |",
        f"| 预算符合率 | {fmt(summary['预算符合率'])} |",
        f"| 需求满足率(P0) | {fmt(summary['需求满足率(P0)'])} |",
        f"| 结构完整率 | {fmt(summary['结构完整率'])} |",
        f"| 平均/最大耗时 | {summary['平均耗时(s)']}s / {summary['最大耗时(s)']}s |",
        "",
        "## 明细", "",
        "| 用例 | 类别 | 状态 | 目的地 | 天数 | 预算合规 | P0满足 | 结构完整 | 耗时(s) |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append("| {id} | {cat} | {st} | {d} | {dy} | {b} | {p} | {sc} | {du} |".format(
            id=r["id"], cat=r["category"], st=r["status"],
            d=str(r.get("destination", "-")), dy=str(r.get("days_match", "-")),
            b=str(r.get("budget_compliance", "-")), p=str(r.get("P0_satisfaction", "-")),
            sc=str(r.get("structure_complete", "-")), du=str(r.get("duration_s", "-"))))
    report.write_text("\n".join(lines), encoding="utf-8")
    print("\n===== 汇总 =====")
    for k, v in summary.items():
        print(f"{k}: {v}")
    print(f"\n报告已写入: {report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
