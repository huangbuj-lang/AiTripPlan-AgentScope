#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
② 数据预处理：读取①采集的原始表 → 清洗/特征提取/分类打标 → 写特征表
- 独立脚本，不碰 Java 核心链路
- 输出: attractions_features / foods_features / hotels_features
用法: python3 data-pipeline/preprocess.py
"""
import os, re, subprocess, sys

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
        print(f"⚠️ SQL错误: {r.stderr.strip()[:200]}"); return []
    if not fetch:
        return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

# ---------- 类型分类打标 ----------
ATTR_RULES = [  # (关键词, 标签)
    (["风景名胜;自然", "自然保护区", "森林公园", "海滩", "湖泊", "山岳", "峡谷", "湿地"], "自然风光"),
    (["博物馆", "古迹", "寺庙", "古镇", "遗址", "故居", "纪念馆", "教堂", "陵园", "古城"], "人文历史"),
    (["主题公园", "游乐园", "动物园", "海洋馆", "游乐场"], "主题乐园"),
    (["特色街区", "商业街", "步行街", "广场", "步行商业街"], "特色街区"),
    (["公园", "园林", "植物园", "风景区"], "城市公园"),
]
FOOD_RULES = [
    (["火锅"], "火锅"), (["川菜", "湘菜", "粤菜", "江浙菜", "鲁菜", "东北菜"], "地方菜"),
    (["小吃", "快餐", "面馆", "粉", "饺子", "馄饨"], "小吃快餐"),
    (["咖啡", "茶馆", "甜品", "饮品", "酒吧"], "饮品甜点"),
    (["西餐", "日本", "韩国", "东南亚", "自助餐"], "异国料理"),
]
HOTEL_LEVELS = [(4.5, "豪华"), (4.0, "舒适"), (0, "经济")]  # rating 分档

def classify(types, rules, fallback="其他"):
    for kws, label in rules:
        for kw in kws:
            if kw in types:
                return label
    return fallback

def build(table, out_table, rules, use_rating=False):
    rows = sql(f"SELECT id, name, city, types, rating, cost FROM {table} WHERE lng IS NOT NULL AND lat IS NOT NULL")
    cleaned = 0
    for r in rows:
        if len(r) < 6:
            continue
        _id, name, city, types, rating, cost = r[:6]
        if not name or not city:
            continue
        cleaned += 1
    # 逐行生成 INSERT（直接构造 SQL，避免参数化依赖）
    inserts = []
    for r in rows:
        if len(r) < 6:
            continue
        _id, name, city, types, rating, cost = r[:6]
        if not name or not city:
            continue
        try:
            rating_f = float(rating) if rating and rating != "NULL" else 0.0
        except ValueError:
            rating_f = 0.0
        if use_rating:  # 酒店：按评分分档打标
            label = "豪华" if rating_f >= 4.5 else ("舒适" if rating_f >= 4.0 else "经济")
        else:
            label = classify(types or "", rules)
        level = "豪华" if rating_f >= 4.5 else ("舒适" if rating_f >= 4.0 else "经济")
        name_s = name.replace("'", "''"); city_s = city.replace("'", "''")
        types_s = (types or "").replace("'", "''")[:190]
        inserts.append(f"({_id},'{name_s}','{city_s}','{types_s}',{rating_f},'{label}','{level}','{cost or ''}')")
    # 增量更新：表带UNIQUE(name,city)，ODKU刷新基础字段、保留扩展字段
    sql(f"""CREATE TABLE IF NOT EXISTS {out_table} (
        src_id BIGINT, name VARCHAR(100), city VARCHAR(50), types VARCHAR(200),
        rating DECIMAL(3,1), label VARCHAR(30), level VARCHAR(10), cost VARCHAR(50),
        address VARCHAR(255), ticket_price VARCHAR(50), is_free TINYINT, visit_duration VARCHAR(50),
        scenic_grade VARCHAR(10), intro VARCHAR(500), booking_required VARCHAR(100),
        suitable_tags VARCHAR(100), tips VARCHAR(500), best_time VARCHAR(255), source VARCHAR(20) DEFAULT 'rule',
        UNIQUE KEY uk_name_city (name, city)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    base_cols = "src_id,name,city,types,rating,label,level,cost"
    # 质量固化：label 仅空/'其他'时接受规则新值，LLM精标不回退
    update_cols = ("src_id=VALUES(src_id), types=VALUES(types), rating=VALUES(rating), "
                   "label=IF(label IS NULL OR label='' OR label='其他', VALUES(label), label), "
                   "level=VALUES(level), cost=VALUES(cost)")
    for i in range(0, len(inserts), 200):
        sql(f"INSERT INTO {out_table} ({base_cols}) VALUES " + ",".join(inserts[i:i+200]) +
            f" ON DUPLICATE KEY UPDATE {update_cols}", fetch=False)
    print(f"✅ {out_table}: 增量更新 {len(inserts)} 条（保留扩展字段）")

build("attractions", "attractions_features", ATTR_RULES)
build("foods", "foods_features", FOOD_RULES)
build("hotels", "hotels_features", HOTEL_LEVELS, use_rating=True)
print("🎉 预处理完成")
