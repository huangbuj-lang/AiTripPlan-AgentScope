#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
③ 动态定价基础版：热度因子(trip_plans) × 节假日因子(holidays) × 天气因子(weather_history)
   → 输出各城市酒店/门票定价系数（真实价格数据源可接入后替换基准价）
用法: python3 data-pipeline/pricing.py
"""
import os, re, subprocess, datetime, json

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

# 2026 主要节假日（可扩展外部数据源）
HOLIDAYS = {"2026-01-01": "元旦", "2026-02-16": "春节", "2026-02-17": "春节", "2026-02-18": "春节",
            "2026-04-05": "清明", "2026-05-01": "劳动节", "2026-06-19": "端午",
            "2026-09-25": "中秋", "2026-10-01": "国庆", "2026-10-02": "国庆", "2026-10-03": "国庆"}

def holiday_factor(date_str):
    return 1.3 if date_str in HOLIDAYS else 1.0

def main():
    sql("""CREATE TABLE IF NOT EXISTS holidays (
        day DATE PRIMARY KEY, name VARCHAR(20)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    for d, n in HOLIDAYS.items():
        sql(f"INSERT IGNORE INTO holidays(day, name) VALUES ('{d}', '{n}')", fetch=False)
    sql("""CREATE TABLE IF NOT EXISTS pricing_output (
        city VARCHAR(50) PRIMARY KEY, hotel_base DECIMAL(8,1), hotel_price DECIMAL(8,1),
        ticket_base DECIMAL(8,1), ticket_price DECIMAL(8,1), factors JSON,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    # 热度：各城市行程生成数（近90天）
    heat = {}
    for r in sql("SELECT city, COUNT(*) FROM trip_plans WHERE city IS NOT NULL AND create_time >= DATE_SUB(NOW(), INTERVAL 90 DAY) GROUP BY city"):
        heat[r[0]] = int(r[1])
    # 天气因子（weather_history 缺数据时=1.0）
    weather = {r[0]: r[1] for r in sql("SELECT city, weather FROM weather_history WHERE day = CURDATE()")}
    today = datetime.date.today().isoformat()
    hf = holiday_factor(today)
    cities = set(heat) | {r[0] for r in sql("SELECT DISTINCT city FROM attractions_features")}
    out = []
    for city in sorted(cities):
        h = heat.get(city, 0)
        heat_f = 1.0 + min(h, 50) * 0.01  # 热度系数 1.0~1.5
        wf = 1.15 if "雨" in (weather.get(city) or "") else 1.0  # 雨天室内需求微升（简化）
        factor = round(heat_f * hf * wf, 2)
        hotel_base, ticket_base = 300.0, 60.0  # ★基准价占位，可接真实价格数据源
        out.append((city, hotel_base, round(hotel_base * factor, 1), ticket_base,
                    round(ticket_base * factor, 1), json.dumps({"heat": round(heat_f,2), "holiday": hf, "weather": wf}, ensure_ascii=False)))
    for row in out:
        sql(f"INSERT INTO pricing_output(city, hotel_base, hotel_price, ticket_base, ticket_price, factors) "
            f"VALUES ('{row[0]}', {row[1]}, {row[2]}, {row[3]}, {row[4]}, '{row[5].replace(chr(39), chr(39)+chr(39))}') "
            f"ON DUPLICATE KEY UPDATE hotel_price=VALUES(hotel_price), ticket_price=VALUES(ticket_price), factors=VALUES(factors)", fetch=False)
        print(f"  ✅ {row[0]}: 酒店 {row[2]} / 门票 {row[4]} (factor={row[5]})")
    print(f"🎉 定价计算完成，共 {len(out)} 城（今日={today} 节假日因子={hf}）")

if __name__ == "__main__":
    main()
