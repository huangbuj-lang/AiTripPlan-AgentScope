#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
③ 动态定价基础版：热度因子(trip_plans) × 节假日因子(holidays) × 天气因子(weather_history)
   → 输出各城市酒店/餐饮/门票定价系数与基准价、现价
   基准价来源：
   - 酒店 hotel_base=300 占位（OTA无公开API，真实房价拿不到）
   - 餐饮 food_base=foods.cost 真实人均均值（高德POI爬取，99%覆盖）
   - 门票 ticket_base=attractions_features.ticket_price 真实挂牌价均值（高德v5 enrich，数字票价）
   用法: python3 data-pipeline/pricing.py
"""
import os, re, subprocess, datetime, json

from pipeline_config import get_pricing_config

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
    mysql_bin = ENV.get("MYSQL_BIN") or os.environ.get("MYSQL_BIN") or "mysql"
    cmd = [mysql_bin, f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-N", "-B", "-e", query]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        print(f"⚠️ SQL错误: {r.stderr.strip()[:150]}"); return []
    if not fetch: return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

# 2026 主要节假日
HOLIDAYS = {"2026-01-01": "元旦", "2026-02-16": "春节", "2026-02-17": "春节", "2026-02-18": "春节",
            "2026-04-05": "清明", "2026-05-01": "劳动节", "2026-06-19": "端午",
            "2026-09-25": "中秋", "2026-10-01": "国庆", "2026-10-02": "国庆", "2026-10-03": "国庆"}

def holiday_factor(date_str):
    return get_pricing_config().get("holidayFactor", 1.3) if date_str in HOLIDAYS else 1.0

# 用户输入城市归一化："成都三日"/"成都七日自行车"→"成都"；匹配标准城市库前缀优先，无匹配返回None
def normalize_city(raw, std_cities):
    if not raw:
        return None
    raw = str(raw).strip()
    if raw in std_cities:
        return raw
    cands = [c for c in std_cities if raw.startswith(c)]
    if cands:
        return max(cands, key=len)
    # 剥离 "14日"/"0.5周" 前缀 + 旅行词尾 → "香港"/"西藏"；不在标准库也保留（用户测过就显示）
    import re as _re
    stripped = _re.sub(r'^\d+(\.\d+)?(日|天|周)?', '', raw)
    stripped = _re.sub(r'\d+(\.\d+)?(日|天|周)', '', stripped)  # 中间的数字日/周：西藏14日→西藏
    stripped = _re.sub(r'[两三四五六七八九十]+(日|天|周)', '', stripped)  # 中文数字：西藏两周→西藏
    stripped = _re.sub(r'(自行车|自驾|自驾车|游|行|旅|旅行|美食|蜜月|漫游|朝圣|深度游|周末|亲子游|行程|计划)$', '', stripped)
    if stripped:
        if stripped in std_cities:
            return stripped
        cands2 = [c for c in std_cities if stripped.startswith(c)]
        if cands2:
            return max(cands2, key=len)
        return stripped if len(stripped) >= 2 else raw
    return raw

# 提取价格文本里的数字（"54元"→54.0，"约20元"→20.0，非数字→None）
def parse_price(text):
    if not text: return None
    m = re.search(r"\d+(\.\d+)?", str(text))
    return float(m.group()) if m else None

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
    # 三行定价：餐饮两列平滑迁移（已存在则跳过）
    sql("ALTER TABLE pricing_output ADD COLUMN food_base DECIMAL(8,1) NULL AFTER ticket_price", fetch=False)
    sql("ALTER TABLE pricing_output ADD COLUMN food_price DECIMAL(8,1) NULL AFTER food_base", fetch=False)
    # 热度：各城市近90天行程数
    std_cities = {r[0] for r in sql("SELECT DISTINCT city FROM attractions WHERE city IS NOT NULL AND city!=''")}
    heat = {}
    for r in sql("SELECT city, COUNT(*) FROM trip_plans WHERE city IS NOT NULL AND create_time >= DATE_SUB(NOW(), INTERVAL 90 DAY) GROUP BY city"):
        std = normalize_city(r[0], std_cities)
        if std:
            heat[std] = heat.get(std, 0) + int(r[1])
    # 天气因子，缺数据=1.0
    weather = {r[0]: r[1] for r in sql("SELECT city, weather FROM weather_history WHERE day = CURDATE()")}
    today = datetime.date.today().isoformat()
    hf = holiday_factor(today)
    cities = set(heat) | {r[0] for r in sql("SELECT DISTINCT city FROM attractions_features")}
    # 餐饮基准：foods.cost 真实人均城市均值，无数据回退50
    food_avg = {r[0]: float(r[1]) for r in sql(
        "SELECT city, AVG(CAST(cost AS DECIMAL(10,2))) FROM foods WHERE cost REGEXP '^[0-9.]+$' GROUP BY city")}
    # 门票基准：ticket_price 真实挂牌价城市均值（只取数字），无数据回退60
    ticket_avg = {}
    for r in sql("SELECT city, ticket_price FROM attractions_features WHERE ticket_price IS NOT NULL AND ticket_price != ''"):
        p = parse_price(r[1])
        if p is not None and p > 0:
            ticket_avg.setdefault(r[0], []).append(p)
    # 清历史脏行：city 不在标准库的旧记录
    std_all = std_cities | {r[0] for r in sql("SELECT DISTINCT city FROM foods WHERE city IS NOT NULL")}
    for r in sql("SELECT city FROM pricing_output"):
        if r[0] not in std_all:
            sql(f"DELETE FROM pricing_output WHERE city='{r[0].replace(chr(39), chr(39)*2)}'", fetch=False)
            print(f"  🧹 清理历史脏行: {r[0]}")
    out = []
    for city in sorted(cities):
        h = heat.get(city, 0)
        heat_f = 1.0 + min(h, 50) * 0.01  # 热度系数 1.0~1.5
        wf = 1.15 if "雨" in (weather.get(city) or "") else 1.0  # 雨天室内需求微升（简化）
        factor = round(heat_f * hf * wf, 2)
        hotel_base = get_pricing_config().get("hotelBase", 300.0)  # 酒店基准（Nacos 可热更）
        ticket_list = ticket_avg.get(city) or []
        ticket_base = round(sum(ticket_list) / len(ticket_list), 1) if ticket_list else 60.0
        food_base = round(food_avg.get(city, 50.0), 1)
        out.append((city, hotel_base, round(hotel_base * factor, 1), food_base,
                    round(food_base * factor, 1), ticket_base,
                    round(ticket_base * factor, 1), json.dumps({"heat": round(heat_f,2), "holiday": hf, "weather": wf}, ensure_ascii=False)))
    for row in out:
        sql(f"INSERT INTO pricing_output(city, hotel_base, hotel_price, food_base, food_price, ticket_base, ticket_price, factors) "
            f"VALUES ('{row[0]}', {row[1]}, {row[2]}, {row[3]}, {row[4]}, {row[5]}, {row[6]}, '{row[7].replace(chr(39), chr(39)+chr(39))}') "
            f"ON DUPLICATE KEY UPDATE hotel_base=VALUES(hotel_base), hotel_price=VALUES(hotel_price), "
            f"food_base=VALUES(food_base), food_price=VALUES(food_price), "
            f"ticket_base=VALUES(ticket_base), ticket_price=VALUES(ticket_price), factors=VALUES(factors)", fetch=False)
        print(f"  ✅ {row[0]}: 酒店 {row[2]} / 餐饮 {row[4]} / 门票 {row[6]} (factor={row[7]})")
    print(f"🎉 定价计算完成，共 {len(out)} 城（今日={today} 节假日因子={hf}）")

if __name__ == "__main__":
    main()
