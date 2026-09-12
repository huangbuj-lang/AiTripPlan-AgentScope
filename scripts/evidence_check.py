#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
证据全览工具（2026-09-11）——防止"只看两个样本就下结论"。

来历：我曾把"A2A 两次失败恰好落在 39s/40s"当成"框架有 40 秒上限"，据此改了两版代码
（其中一版把系统卡死 10 分钟）。真相是成功的调用里 76s/80s 都有 —— 我只挑了失败样本看。

这个工具的作用：把**全部样本**摊开，成功/失败分组列出，并自动做"反证检验"。
结论必须由输出说话，不允许再用"我看到两次…所以…"这种挑样本的推理。

用法：
  python scripts/evidence_check.py                     # 自动找 logs_*.log
  python scripts/evidence_check.py logs/manager_gitee.log         # 指定文件
  python scripts/evidence_check.py --hypothesis 40     # 额外检验"是否存在 N 秒上限"
"""
import glob
import os
import re
import sys
from collections import defaultdict

ROOT = "E:/gitee/aitripplan-agentscope"
PROBE = re.compile(r"\[A2A探针\]\s*(?P<label>\S+)\s+attempt=(?P<att>\d+)\s+"
                   r"耗时=(?P<ms>\d+)ms\s+任务长度=(?P<task>\d+)\s+"
                   r"返回长度=(?P<ret>-?\d+)\s+结果=(?P<res>\S+)")


def collect(paths):
    ok, fail = [], []
    for p in paths:
        try:
            with open(p, encoding="utf-8", errors="replace") as f:
                for line in f:
                    m = PROBE.search(line)
                    if not m:
                        continue
                    rec = {
                        "sec": int(m.group("ms")) / 1000.0,
                        "task": int(m.group("task")),
                        "ret": int(m.group("ret")),
                        "label": m.group("label"),
                        "att": int(m.group("att")),
                        "file": os.path.basename(p),
                    }
                    (fail if "空返回" in m.group("res") or "失败" in m.group("res") else ok).append(rec)
        except OSError:
            continue
    return ok, fail


def stats(items, key):
    if not items:
        return "（无样本）"
    vals = sorted(x[key] for x in items)
    n = len(vals)
    mid = vals[n // 2] if n % 2 else (vals[n // 2 - 1] + vals[n // 2]) / 2
    return (f"n={n}  min={vals[0]:.1f}  中位={mid:.1f}  max={vals[-1]:.1f}  全部={[round(v,1) for v in vals]}")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    hyp = None
    for a in sys.argv[1:]:
        if a.startswith("--hypothesis"):
            hyp = float(a.split("=", 1)[1]) if "=" in a else 40.0

    if args:
        paths = args
    else:
        paths = sorted(glob.glob(f"{ROOT}/logs/*.log"))
    paths = [p for p in paths if os.path.exists(p)]
    if not paths:
        print("❌ 没找到日志文件（A2A 探针需已启用）")
        return

    print("=" * 78)
    print("样本来源: " + ", ".join(os.path.basename(p) for p in paths))
    print("=" * 78)
    ok, fail = collect(paths)

    print(f"\n【成功样本】耗时(秒)  {stats(ok, 'sec')}")
    print(f"            任务长度  {stats(ok, 'task')}")
    print(f"\n【空返回样本】耗时(秒)  {stats(fail, 'sec')}")
    print(f"              任务长度  {stats(fail, 'task')}")

    # ---- 反证检验：绝不允许"只看两条" ----
    print("\n" + "-" * 78)
    print("反证检验（下结论前必须看这里）")
    print("-" * 78)
    if hyp:
        slower = [x for x in ok if x["sec"] > hyp]
        if slower:
            print(f"❌ 假设『耗时上限 = {hyp:.0f}s』被证伪：有 {len(slower)} 次成功超过了它 →")
            for x in sorted(slower, key=lambda v: -v["sec"])[:5]:
                print(f"     成功 {x['sec']:.1f}s（任务 {x['task']} 字符，{x['file']}）")
            print("   → 结论：不存在该上限。不要基于它改代码。")
        else:
            print(f"⚪ 本批数据中暂无超过 {hyp:.0f}s 的成功样本 —— 注意：这**不能证明**存在上限，只是没观察到反例。")
    if ok:
        mx = max(x["sec"] for x in ok)
        print(f"· 成功样本最大耗时 {mx:.1f}s —— 任何『小于它』的上限假设都必须先解释这个反例。")
    if fail:
        tasks_fail = sorted(x["task"] for x in fail)
        tasks_ok = sorted(x["task"] for x in ok)
        print(f"· 空返回样本的任务长度: {tasks_fail}")
        print(f"· 成功样本的任务长度区间: {tasks_ok[0]}~{tasks_ok[-1]}")
        if tasks_ok[-1] > tasks_fail[-1]:
            print("  → 有比失败样本更大的任务成功了 ⇒『任务越大越易失败』不成立（至少不充分）。")
    print("\n【纪律】结论必须能解释**全部**样本；只解释挑出来的一两条 = 猜。")


if __name__ == "__main__":
    main()
