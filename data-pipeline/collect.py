#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
① 数据采集：调用高德 Web API 批量采集 景点/美食/酒店 → 落库 MySQL 新表
- 独立脚本，不碰 Java 核心链路
- 幂等：name+city 唯一键，可重复执行
- 限速：每请求 sleep 0.5s（高德 QPS≈3）
用法: python3 data-pipeline/collect.py [城市1,城市2,...]
"""
import json, os, re, subprocess, sys, time, urllib.request, urllib.parse

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {}
for line in open(os.path.join(BASE, ".env"), encoding="utf-8"):
    m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
    if m:
        ENV[m.group(1)] = m.group(2).strip().strip('"').strip("'")

KEY = ENV.get("AMAP_KEY", "")
RDS_HOST = ENV.get("RDS_HOST", "127.0.0.1:3306")
RDS_USER = ENV.get("RDS_USER", "root")
RDS_PASS = ENV.get("RDS_PASSWORD", "")

CITIES = ["成都", "重庆", "西安", "长沙", "武汉", "杭州", "南京", "广州", "深圳", "北京", "上海",
          "昆明", "大理", "丽江", "三亚", "厦门", "青岛", "苏州", "桂林", "贵阳"]
# 高德 types：110000 风景名胜 / 050000 餐饮 / 100000 住宿
KINDS = [("attractions", "景点", "110000"), ("foods", "美食", "050000"), ("hotels", "酒店", "100000")]

def amap_place(city, keywords, types, page=1, offset=25):
    """高德 place/text 关键词搜索"""
    url = "https://restapi.amap.com/v3/place/text?" + urllib.parse.urlencode({
        "key": KEY, "keywords": keywords, "city": city, "types": types,
        "offset": offset, "page": page, "extensions": "all"})
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"))

def run_sql(sql):
    """通过 mysql CLI 执行 SQL（避免装 pymysql）"""
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    cmd = ["mysql", f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-e", sql]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if r.returncode != 0 and r.stderr.strip():
        print(f"  ⚠️ SQL错误: {r.stderr.strip()[:150]}")

def main(cities=None):
    if not KEY:
        print("❌ .env 缺少 AMAP_KEY"); sys.exit(1)
    targets = cities or CITIES
    for table, label, types in KINDS:
        run_sql(f"""CREATE TABLE IF NOT EXISTS {table} (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            name VARCHAR(100) NOT NULL,
            city VARCHAR(50),
            address VARCHAR(255),
            lng DECIMAL(10,6), lat DECIMAL(10,6),
            types VARCHAR(200),
            rating DECIMAL(3,1),
            cost VARCHAR(50),
            source VARCHAR(20) DEFAULT 'amap',
            UNIQUE KEY uk_name_city (name, city)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""")
    total = 0
    for city in targets:
        for table, label, types in KINDS:
            for page in range(1, 4):  # 每类每城最多 3 页 × 25 = 75 条
                try:
                    d = amap_place(city, label, types, page)
                except Exception as e:
                    print(f"  ⚠️ {city}/{label} 第{page}页失败: {e}")
                    break
                if d.get("status") != "1":
                    print(f"  ⚠️ {city}/{label} 第{page}页: {d.get('info')}")
                    break
                pois = d.get("pois") or []
                if not pois:
                    break
                rows = []
                for p in pois:
                    name = (p.get("name") or "").replace("'", "''")
                    if not name:
                        continue
                    loc = (p.get("location") or "").split(",")
                    lng = loc[0] if len(loc) > 0 else ""
                    lat = loc[1] if len(loc) > 1 else ""
                    addr = (p.get("address") or "").replace("'", "''")
                    tp = (p.get("type") or "").replace("'", "''")[:190]
                    rating = p.get("biz_ext", {}).get("rating") or "NULL"
                    cost = (p.get("biz_ext", {}).get("cost") or "")[:50].replace("'", "''")
                    try:
                        rating = float(rating)
                    except (TypeError, ValueError):
                        rating = "NULL"
                    rows.append(f"('{name}','{city}','{addr}',{lng},{lat},'{tp}',{rating},'{cost}')")
                if rows:
                    sql = f"INSERT IGNORE INTO {table}(name,city,address,lng,lat,types,rating,cost) VALUES " + ",".join(rows)
                    run_sql(sql)
                    total += len(rows)
                print(f"  ✅ {city}/{label} 第{page}页 +{len(rows)}（累计 {total}）")
                time.sleep(0.5)  # 限速
    print(f"🎉 采集完成，共写入 {total} 条")

if __name__ == "__main__":
    main(sys.argv[1:] or None)
