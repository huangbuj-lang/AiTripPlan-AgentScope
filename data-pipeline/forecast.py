#!/usr/bin/env python3
#-*- coding: utf-8 -*-
"""
③ 客流预测基础版：基于 trip_plans 历史按城市/日统计 → 移动平均+趋势 → 预测未来7天客流指数
用法: python3 data-pipeline/forecast.py
"""
import os, re, subprocess, datetime, json, statistics

from pipeline_config import get_forecast_config

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
    r = subprocess.run(cmd, check=False, capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        print(f"⚠️ SQL错误: {r.stderr.strip()[:150]}"); return []
    if not fetch: return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

#城市归一化：①标准库前缀最长匹配 ②剥数字/中文数字+日天周与旅行词尾 ③仍无则保留原名
import re as _re
def normalize_city(raw, std_cities):
    if not raw:
        return None
    raw = str(raw).strip()
    if raw in std_cities:
        return raw
    cands = [c for c in std_cities if raw.startswith(c)]
    if cands:
        return max(cands, key=len)
    stripped = _re.sub(r'^\d+(\.\d+)?(日|天|周)?', '', raw)  # 剥数字前缀"14日"
    stripped = _re.sub(r'\d+(\.\d+)?(日|天|周)', '', stripped)  # 剥中间数字日/周："西藏14日"
    stripped = _re.sub(r'[两三四五六七八九十]+(日|天|周)', '', stripped)  # 中文数字："西藏两周"
    stripped = _re.sub(r'(自行车|自驾|自驾车|游|行|旅|旅行|美食|蜜月|漫游|朝圣|深度游|周末|亲子游|行程|计划)$', '', stripped)  # 剥旅行词尾
    if stripped:
        #优先标准库；
        if stripped in std_cities:
            return stripped
        cands2 = [c for c in std_cities if stripped.startswith(c)]
        if cands2:
            return max(cands2, key=len)
        return stripped if len(stripped) >= 2 else raw
    return raw

def main():
    fc_days = int(get_forecast_config().get("days", 7))
    sql("""CREATE TABLE IF NOT EXISTS forecast_output (
        city VARCHAR(50), day DATE, forecast_idx DECIMAL(6,2), PRIMARY KEY(city, day)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    rows = sql("SELECT city, DATE(create_time) d FROM trip_plans WHERE city IS NOT NULL AND create_time >= DATE_SUB(NOW(), INTERVAL 90 DAY)")
    #城市归一化：用户输入("成都三日")合并到标准城市("成都")，与定价表口径一致
    std_cities = {r[0] for r in sql("SELECT DISTINCT city FROM attractions WHERE city IS NOT NULL AND city!=''")}
    daily = {}
    for r in rows:
        c = normalize_city(r[0], std_cities)
        if not c:
            continue
        daily.setdefault(c, {}).setdefault(r[1], 0)
        daily[c][r[1]] += 1
    if not daily:
        print("⚠️ 历史数据不足（<90天无行程），无法预测"); return
    today = datetime.date.today()
    out = []
    for city, by_day in daily.items():
        series = sorted(by_day.items())
        vals = [v for _, v in series]
        avg = statistics.mean(vals) if vals else 1
        #简单线性趋势：最近5天均值 vs 整体均值
        recent = statistics.mean(vals[-5:]) if len(vals) >= 5 else avg
        trend = (recent / avg) if avg > 0 else 1.0
        for i in range(1, fc_days + 1):
            day = (today + datetime.timedelta(days=i)).isoformat()
            #周末因子（简化）：周六日 1.3
            wf = 1.3 if datetime.date.fromisoformat(day).weekday() >= 5 else 1.0
            idx = round(avg * trend * wf, 2)
            out.append((city, day, idx))
    #清理过期行：只留未来7天；
    sql("DELETE FROM forecast_output WHERE day < DATE_SUB(CURDATE(), INTERVAL -1 DAY)", fetch=False)
    for r in sql("SELECT DISTINCT city FROM forecast_output"):
        if r[0] not in std_cities:
            sql(f"DELETE FROM forecast_output WHERE city='{r[0].replace(chr(39), chr(39)*2)}'", fetch=False)
            print(f"  🧹 清理历史脏行: {r[0]}")
    #写入未来7天预测（存在则更新指数）
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
