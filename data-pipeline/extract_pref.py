#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
保存行程 → LLM 提取偏好标签 → 追加写入用户画像（用户无感）
- 由 PlanController.savePlan 异步调用：python extract_pref.py --user <id> --content <行程文本>
- 提取结果为标准标签（与 PrefSynonyms 标签集一致），以 "内容-标签" 形式并入画像
- 失败静默（不影响保存主流程）
"""
import os, re, sys, json, subprocess, urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {}
for line in open(os.path.join(BASE, ".env"), encoding="utf-8"):
    m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
    if m:
        ENV[m.group(1)] = m.group(2).strip().strip('"').strip("'")

from llm_config import chat as _llm_chat  # ★统一模型配置（管理端可热更）
RDS_HOST = ENV.get("RDS_HOST", "127.0.0.1:3306")
RDS_USER = ENV.get("RDS_USER", "root")
RDS_PASS = ENV.get("RDS_PASSWORD", "")

STANDARD_TAGS = ["亲子", "老人", "情侣", "拍照", "美食", "自然风光", "人文历史", "主题乐园",
                 "特色街区", "城市公园", "小众", "悠闲", "徒步", "温泉", "夜生活", "购物",
                 "经济型", "豪华型", "火锅", "地方菜", "小吃快餐", "异国料理"]

def sql(query, fetch=True):
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    mysql_bin = ENV.get("MYSQL_BIN") or os.environ.get("MYSQL_BIN") or "mysql"
    cmd = [mysql_bin, f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-N", "-B", "-e", query]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if r.returncode != 0:
        return []
    if not fetch:
        return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

def ask_llm(content):
    sys = ("从旅行行程文本中提取反映用户偏好的标准标签。只输出 JSON 数组的字符串，"
           "标签只能从这个集合选：" + ",".join(STANDARD_TAGS) +
           "。最多 3 个，按出现强度排序；无法判断输出 []。只输出 JSON 数组。")
    try:
        out = _llm_chat([{"role": "system", "content": sys},
                         {"role": "user", "content": content[:3000]}],
                        temperature=0.2, max_tokens=2000, timeout=90)
    except Exception as e:
        print("LLM call failed:", e)
        return []
    if not out:
        print("LLM empty response")
        return []
    m = re.search(r"\[.*\]", out, re.S)
    try:
        return json.loads(m.group()) if m else []
    except Exception:
        return []

def esc(s):
    return (s or "").replace("\\", "").replace("'", "''")

def main():
    args = sys.argv[1:]
    uid = int(args[args.index("--user") + 1])
    content = args[args.index("--content") + 1]
    tags = ask_llm(content)
    tags = [t for t in tags if t in STANDARD_TAGS][:3]
    if not tags:
        print("no tags extracted")
        return
    # 读现有画像
    rows = sql(f"SELECT tags FROM user_profiles_tags WHERE user_id={uid}")
    cur = {}
    if rows and rows[0][0] and rows[0][0] != "NULL":
        try:
            cur = json.loads(rows[0][0])
        except Exception:
            cur = {}
    # 追加 内容-标签（带时间序权重，重复提取则+1），上限压缩：每键最大5，总键数最多20
    for tg in tags:
        key = f"内容-{tg}"
        cur[key] = min(cur.get(key, 0) + 1, 5)
    if len(cur) > 20:
        items = sorted(cur.items(), key=lambda kv: kv[1])[:len(cur) - 20]
        for k, _ in items:
            cur.pop(k)
    tj = json.dumps(cur, ensure_ascii=False).replace("'", "''")
    sql(f"INSERT INTO user_profiles_tags(user_id, tags) VALUES ({uid}, '{tj}') "
        f"ON DUPLICATE KEY UPDATE tags=VALUES(tags)", fetch=False)
    print(f"user {uid}: +{tags}")

if __name__ == "__main__":
    main()
