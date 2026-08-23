#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
⑥ 用户画像：聚合 行为数据(trip_plans/posts/post_likes/user_preferences)
   → 生成画像标签 → 写 user_profiles_tags（独立表，不动现有代码）
用法: python3 data-pipeline/profile.py
"""
import os, re, subprocess, json, collections

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

STYLE_KW = {"美食": ["美食", "吃", "火锅", "餐厅", "小吃", "泡馍", "三大炮"], "亲子": ["亲子", "带娃", "孩子", "儿童乐园"],
            "摄影": ["摄影", "拍照", "出片", "打卡", "夜景"], "自然": ["自然", "山", "湖", "海", "森林", "公园"],
            "人文": ["人文", "历史", "博物馆", "古镇", "寺庙", "遗迹"], "经济": ["省钱", "性价比", "穷游"],
            "徒步": ["徒步", "登山", "爬山", "远足"], "温泉": ["温泉", "泡汤"],
            "夜生活": ["夜生活", "夜景", "酒吧", "夜市"], "悠闲": ["悠闲", "喝茶", "慢生活", "度假"]}

def build_tags(user_id):
    tags = {}
    # 1) 主动偏好（user_preferences）
    rows = sql(f"SELECT travel_style FROM user_preferences WHERE user_id={user_id}")
    if rows and rows[0][0] and rows[0][0] != "NULL":
        try:
            for s in json.loads(rows[0][0]):
                tags[f"偏好-{s}"] = 3
        except Exception:
            pass
    # 2) 生成城市偏好（trip_plans）
    cities = collections.Counter(r[0] for r in sql(f"SELECT city FROM trip_plans WHERE user_id={user_id} AND city IS NOT NULL"))
    for city, cnt in cities.most_common(5):
        tags[f"常去-{city}"] = min(cnt, 5)
    # 4) 发帖/收藏内容风格（posts + post_likes）
    texts = [r[0] or "" for r in sql(f"SELECT title FROM posts WHERE user_id={user_id}")]
    texts += [r[0] or "" for r in sql(f"SELECT content FROM posts WHERE user_id={user_id}")]
    joined = " ".join(texts)
    for style, kws in STYLE_KW.items():
        hit = sum(1 for kw in kws if kw in joined)
        if hit > 0:
            tags[f"风格-{style}"] = min(hit + 1, 5)
    return tags

def rebuild_user(user_id):
    """★单用户画像全量重算：删除行程/反馈变化后由主管Java触发。
    重算=从该用户剩余的全部数据重新聚合，天然处理共同偏好（不会因删一条行程误删标签）。"""
    sql("""CREATE TABLE IF NOT EXISTS user_profiles_tags (
        user_id BIGINT PRIMARY KEY, tags JSON, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    tags = build_tags(user_id)
    if not tags:
        sql(f"DELETE FROM user_profiles_tags WHERE user_id={user_id}", fetch=False)
        print(f"user {user_id}: no tags, cleared")
        return
    tj = json.dumps(tags, ensure_ascii=False).replace("'", "''")
    sql(f"INSERT INTO user_profiles_tags(user_id, tags) VALUES ({user_id}, '{tj}') "
        f"ON DUPLICATE KEY UPDATE tags=VALUES(tags)", fetch=False)
    print(f"user {user_id}: {tags}")

def main():
    sql("""CREATE TABLE IF NOT EXISTS user_profiles_tags (
        user_id BIGINT PRIMARY KEY, tags JSON, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    users = [r[0] for r in sql("SELECT id FROM users")]
    for uid in users:
        tags = build_tags(uid)
        if not tags:
            continue
        tj = json.dumps(tags, ensure_ascii=False).replace("'", "''")
        sql(f"INSERT INTO user_profiles_tags(user_id, tags) VALUES ({uid}, '{tj}') "
            f"ON DUPLICATE KEY UPDATE tags=VALUES(tags)", fetch=False)
        print(f"  ✅ 用户{uid}: {tags}")
    print(f"🎉 画像完成，共 {len(users)} 个用户")

if __name__ == "__main__":
    import sys
    if len(sys.argv) >= 3 and sys.argv[1] == "--user":
        rebuild_user(int(sys.argv[2]))  # ★单用户全量重算（删除行程后由Java调用）
    else:
        main()

