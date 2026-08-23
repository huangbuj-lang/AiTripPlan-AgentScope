#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键数据更新管线：采集 → 预处理 → 高德v5经验 → 景区等级 → LLM补充
- 幂等可重复跑；LLM 步骤只补缺失字段（不覆盖已有）
用法: python3 data-pipeline/update_all.py [--skip-collect] [--skip-llm]
"""
import subprocess, sys, os, time

BASE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable or "python3"

STEPS = [
    ("采集(高德Web API→原始表)", "collect.py", "--skip-collect" not in sys.argv),
    ("预处理(清洗打标→特征表,增量UPSERT)", "preprocess.py", True),
    ("高德v5经验(rectag/开放时间)", "enrich.py", True),
    ("景区等级(5A名录)", "enrich_grade.py", True),
    ("LLM补充(门票/时长/简介等)", "enrich_llm.py", "--skip-llm" not in sys.argv),
    # ★数据源变动后自动刷新看板派生数据（无参批量脚本；extract_pref.py 是单用户按需脚本，
    #   已由 PlanController.savePlan 异步调用，不在此列）
    ("动态定价(三行基准:酒店/餐饮/门票)", "pricing.py", True),
    ("客流预测(未来7天指数)", "forecast.py", True),
    ("用户画像聚合(行为→标签)", "profile.py", True),
]

def run(script):
    print(f"\n▶ {script} ...", flush=True)
    t0 = time.time()
    r = subprocess.run([PY, os.path.join(BASE, script)], capture_output=True, text=True, encoding="utf-8", errors="ignore")
    dt = time.time() - t0
    tail = "\n".join((r.stdout or "").strip().splitlines()[-4:])
    print(tail, flush=True)
    if r.returncode != 0:
        err = (r.stderr or "")[-300:]
        print(f"⚠️ {script} 退出码 {r.returncode}: {err}", flush=True)
        return False
    print(f"✔ {script} 完成 ({dt:.0f}s)", flush=True)
    return True

if __name__ == "__main__":
    print("=== AiTripPlan 数据管线一键更新 ===", flush=True)
    all_ok = True
    for name, script, enabled in STEPS:
        if not enabled:
            print(f"\n⏭ 跳过: {name}")
            continue
        print(f"\n=== {name} ===", flush=True)
        if not run(script):
            all_ok = False
    print("\n" + ("🎉 全部步骤完成" if all_ok else "⚠️ 部分步骤失败，见上方日志"), flush=True)
