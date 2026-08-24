#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
整体数据质量检查（只读，不改任何数据）。
检查面：三张特征表(景点/美食/酒店)标签分布、关键字段覆盖率、tips类型词泄漏、
trip_plans城市标准化、pricing/forecast输出健康度、画像覆盖、跨表一致性。
用法: python3 data-pipeline/qa_report.py
"""
import os, re, subprocess

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {}
for line in open(os.path.join(BASE, ".env"), encoding="utf-8"):
    m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
    if m:
        ENV[m.group(1)] = m.group(2).strip().strip('"').strip("'")
RDS_HOST = ENV.get("RDS_HOST", "127.0.0.1:3306")
RDS_USER = ENV.get("RDS_USER", "root")
RDS_PASS = ENV.get("RDS_PASSWORD", "")

def sql(query):
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    cmd = ["mysql", f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-N", "-B", "-e", query]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if r.returncode != 0:
        return [["SQL错误", r.stderr.strip()[:120]]]
    return [l.split("\t") for l in r.stdout.strip().splitlines() if l.strip()]

def one(q):
    r = sql(q)
    try:
        return int(float(r[0][0]))
    except Exception:
        return -1

W = 62
def sec(title):
    print(f"\n{'='*W}\n【{title}】\n{'='*W}")

def pct(a, b):
    return f"{a}/{b} ({a*100//b}%)" if b else "0/0"

# ---------- ① 景点特征表 ----------
sec("① attractions_features 景点（1497条）")
total_a = one("SELECT COUNT(*) FROM attractions_features")
print(f"总量: {total_a}")
print("label 分布:")
for r in sql("SELECT label, COUNT(*) FROM attractions_features GROUP BY label ORDER BY 2 DESC"):
    print(f"  {r[0]}: {r[1]}")
tip_ok = one("SELECT COUNT(*) FROM attractions_features WHERE tips IS NOT NULL AND tips!=''")
print(f"tips 非空: {pct(tip_ok, total_a)}")
grade = one("SELECT COUNT(*) FROM attractions_features WHERE scenic_grade IS NOT NULL AND scenic_grade!=''")
print(f"scenic_grade 等级回填: {grade}")
tp = one("SELECT COUNT(*) FROM attractions_features WHERE ticket_price IS NOT NULL AND ticket_price NOT IN ('','未知')")
print(f"ticket_price 有效值(定价门票基准来源): {pct(tp, total_a)}")
rt = one("SELECT COUNT(*) FROM attractions_features WHERE rating IS NOT NULL")
print(f"rating 非空: {pct(rt, total_a)}")
addr = one("SELECT COUNT(*) FROM attractions_features WHERE address IS NOT NULL AND address!=''")
print(f"address 非空: {pct(addr, total_a)}")

# ---------- ② 美食/酒店 ----------
sec("② foods_features 美食 / hotels_features 酒店")
total_f = one("SELECT COUNT(*) FROM foods_features")
print(f"美食总量: {total_f}")
print("label 分布:")
for r in sql("SELECT label, COUNT(*) FROM foods_features GROUP BY label ORDER BY 2 DESC"):
    print(f"  {r[0]}: {r[1]}")
cost_f = one("SELECT COUNT(*) FROM foods_features WHERE cost IS NOT NULL AND cost NOT IN ('','未知')")
print(f"cost 有效值(定价餐饮基准来源): {pct(cost_f, total_f)}")
total_h = one("SELECT COUNT(*) FROM hotels_features")
cost_h = one("SELECT COUNT(*) FROM hotels_features WHERE cost IS NOT NULL AND cost NOT IN ('','未知')")
rt_h = one("SELECT COUNT(*) FROM hotels_features WHERE rating IS NOT NULL")
print(f"酒店总量: {total_h} | cost有效: {pct(cost_h,total_h)} | rating非空: {pct(rt_h,total_h)}")

# ---------- ③ 行程表城市标准化 ----------
sec("③ trip_plans 城市标准化（待办②验证）")
sys_path = os.path.join(BASE, "data-pipeline")
if sys_path not in __import__("sys").path:
    __import__("sys").path.insert(0, sys_path)
from forecast import normalize_city  # 与管线同款归一化，口径一致
std_cities = [r[0] for r in sql("SELECT DISTINCT city FROM attractions WHERE city IS NOT NULL AND city!=''")]
rows_all = sql("SELECT DISTINCT city FROM trip_plans WHERE city IS NOT NULL AND city!=''")
dirty, extended = [], []
for (c,) in [(r[0],) for r in rows_all]:
    if c in std_cities:
        continue
    # 归一化稳定值（如"西藏/香港"不在20城景点库但自身已是干净城市）= 合法扩展；否则为脏值
    (extended if normalize_city(c, std_cities) == c else dirty).append(c)
print(f"标准城市库({len(std_cities)}城) + 归一化扩展城({'、'.join(sorted(extended)) if extended else '无'})")
print("⚠️ 脏值残留: " + str(dirty) if dirty else "✓ 全部行程 city 已标准化")

# ---------- ④ 定价输出 ----------
sec("④ pricing_output 动态定价（20城）")
pr = sql("SELECT city, hotel_base, food_base, ticket_base, food_price, ticket_price FROM pricing_output ORDER BY city")
print(f"共 {len(pr)} 城 | 列: 城市 酒店/餐饮/门票基准 现价")
bad = []
for c, hb, fb, tb, fp, tprice in pr:
    issues = []
    try:
        if float(tb) <= 0: issues.append("门票基准≤0")
    except Exception: issues.append("门票基准非数")
    try:
        if float(fb) <= 0: issues.append("餐饮基准≤0")
    except Exception:
        if fb in (None, '', 'None'): issues.append("餐饮基准缺失")
    if issues:
        bad.append(f"{c}: {'、'.join(issues)} (门票={tb}, 餐饮={fb})")
print("✓ 全城基准价正常" if not bad else "⚠️ 异常:\n  " + "\n  ".join(bad))

# ---------- ⑤ 客流预测输出 ----------
sec("⑤ forecast_output 客流预测")
fc_cities = sorted(set(r[0] for r in sql("SELECT DISTINCT city FROM forecast_output")))
nonstd_fc = [c for c in fc_cities if normalize_city(c, std_cities) != c]
past = one("SELECT COUNT(*) FROM forecast_output WHERE day < CURDATE()")
print(f"城市({len(fc_cities)}): {'、'.join(fc_cities)}")
print(f"⚠️ 脏城市行(重跑forecast.py可清): {nonstd_fc}" if nonstd_fc else "✓ 城市全部干净")
print(f"⚠️ 过期行(day<今天): {past}" if past else "✓ 无过期行")

# ---------- ⑥ 画像覆盖 & 跨表一致性 ----------
sec("⑥ 画像覆盖 & 跨表一致性 & 索引")
up = sql("SELECT user_id FROM user_profiles_tags")
users = {r[0] for r in sql("SELECT id FROM users")}
missing = [u for u, in [(x[0],) for x in up] if u not in users]
print(f"画像用户: {[r[0] for r in up]} | users表存在性: {'✓' if not missing else '⚠️ 孤儿'+str(missing)}")
orphan_t = one("SELECT COUNT(*) FROM trip_plans p LEFT JOIN users u ON p.user_id=u.id WHERE u.id IS NULL")
print(f"行程孤儿(user不存在): {orphan_t}" if orphan_t else "✓ 行程无孤儿记录")
feat_only = one("SELECT COUNT(*) FROM attractions_features f LEFT JOIN attractions a ON f.src_id=a.id WHERE a.id IS NULL")
print(f"特征表孤儿src_id: {feat_only}")
# ★唯一键存在性：没有它 preprocess 的 ODKU 增量更新会变成追加重复行
uk = sql("SELECT TABLE_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='aitripplan' "
         "AND INDEX_NAME='uk_name_city' AND TABLE_NAME LIKE '%_features'")
have = {r[0] for r in uk}
need = {"attractions_features", "foods_features", "hotels_features"}
miss_uk = need - have
print(f"特征表唯一键 uk_name_city: {'✓ 三表齐全' if not miss_uk else '⚠️ 缺失'+str(miss_uk)+'（重跑会追加重复行！）'}")

print(f"\n{'='*W}\n检查完成（只读，未改任何数据）\n{'='*W}")
