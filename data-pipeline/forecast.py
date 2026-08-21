#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
③ 客流预测基础版：基于 trip_plans 历史按城市/日统计 → 移动平均+趋势 → 预测未来7天客流指数
用法: python3 data-pipeline/forecast.py
"""
import os, re, subprocess, datetime, json, statistics

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {}
for line in open(os.path.join(BASE, ".env"), encoding="utf-8"):
    m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
    if m:
        ENV[m.group(1)] = m.group(2).strip().strip('"').strip("'")
RDS_HOST = ENV.get("RDS_HOST", "127.0.0.1:3306")
RDS_USER = ENV.get("RDS_USER", "root")
RDS_PASS = ENV.get("RDS_PASSWORD", "")

def sql(query, fetch=True):
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    cmd = ["mysql", f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-N", "-B", "-e", query]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"⚠️ SQL错误: {r.stderr.strip()[:150]}"); return []
    if not fetch: return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

def main():
    sql("""CREATE TABLE IF NOT EXISTS forecast_output (
        city VARCHAR(50), day DATE, forecast_idx DECIMAL(6,2), PRIMARY KEY(city, day)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    rows = sql("SELECT city, DATE(create_time) d FROM trip_plans WHERE city IS NOT NULL AND create_time >= DATE_SUB(NOW(), INTERVAL 90 DAY)")
    daily = {}
    for r in rows:
        daily.setdefault(r[0], {}).setdefault(r[1], 0)
        daily[r[0]][r[1]] += 1
    if not daily:
        print("⚠️ 历史数据不足（<90天无行程），无法预测"); return
    today = datetime.date.today()
    out = []
    for city, by_day in daily.items():
        series = sorted(by_day.items())
        vals = [v for _, v in series]
        avg = statistics.mean(vals) if vals else 1
        # 简单线性趋势：最近5天均值 vs 整体均值
        recent = statistics.mean(vals[-5:]) if len(vals) >= 5 else avg
        trend = (recent / avg) if avg > 0 else 1.0
        for i in range(1, 8):
            day = (today + datetime.timedelta(days=i)).isoformat()
            # 周末因子（简化）：周六日 1.3
            wf = 1.3 if datetime.date.fromisoformat(day).weekday() >= 5 else 1.0
            idx = round(avg * trend * wf, 2)
            out.append((city, day, idx))
    for city, day, idx in out:
        sql(f"INSERT INTO forecast_output(city, day, forecast_idx) VALUES ('{city}', '{day}', {idx}) "
            f"ON DUPLICATE KEY UPDATE forecast_idx=VALUES(forecast_idx)", fetch=False)
    cities = sorted(set(c for c, _, _ in out))
    print("📊 未来7天客流指数预测：")
    for city in cities:
        days = [f"{d[5:]}:{i}" for c, d, i in out if c == city]
        print(f"  {city}: {', '.join(days)}")
    print(f"🎉 预测完成，共 {len(out)} 条（覆盖 {len(cities)} 城）")

if __name__ == "__main__":
    main()
