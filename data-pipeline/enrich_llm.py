#!/usr/bin/env python3
#-*- coding: utf-8 -*-
"""
LLM 补数据：用 py 侧统一模型（llm_config）生成
  景点: ticket_price门票/is_free免费/visit_duration时长/intro简介/booking_required预约/suitable_tags适合人群
  美食: price_range人均/intro/suitable_tags
  酒店: price_range/intro
- source 标 'ai_general'（与 amap_v5/moc 区分）；UGC 反馈未来可覆盖
- 幂等：已有值的字段不覆盖；--force 全刷
用法: python3 data-pipeline/enrich_llm.py [城市...] [--force]
"""
import json, os, re, subprocess, sys, time, urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {}
for line in open(os.path.join(BASE, ".env"), encoding="utf-8"):
    m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
    if m:
        ENV[m.group(1)] = m.group(2).strip().strip('"').strip("'")
RDS_HOST = ENV.get("RDS_HOST", "127.0.0.1:3306")
RDS_USER = ENV.get("RDS_USER", "root")
RDS_PASS = ENV.get("RDS_PASSWORD", "")

#模型走 llm_config 统一配置（管理端可热更）
from llm_config import get_llm_config  # ★统一模型配置（管理端可热更）
_CFG = get_llm_config()
OR_KEY, OR_URL, OR_MODEL = _CFG["key"], _CFG["url"].rstrip("/") + "/chat/completions", _CFG["name"]

def sql(query, fetch=True):
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    cmd = ["mysql", f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-N", "-B", "-e", query]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"[WARN] SQL错误: {r.stderr.strip()[:120]}"); return []
    if not fetch: return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

def ask_llm(items_json):
    """items: [{'name','city','types'}...] → 返回 JSON 数组"""
    sys_p = (
        "你是旅游景点信息数据库。根据给定的景点/美食/酒店名称、城市、类型，输出 JSON 数组，每项：\n"
        '{"name":"原名","ticket_price":"门票如 60元/免费/未知","is_free":true或false或null,'
        '"visit_duration":"建议游玩时长如 2-3小时 或 未知","intro":"一句话简介(40字内)",'
        '"booking_required":"预约要求 如 需提前3天实名预约/无需预约/未知",'
        '"suitable_tags":"适合人群标签 用|分隔 如 亲子|老人|拍照"}\n'
        "要求：只输出JSON数组不要其他文字；不确定写\"未知\"，禁止编造精确数字价格；"
        "知名景点用常识（如故宫60元需预约）；is_free 仅当确定免费为true。"
    )
    body = json.dumps({"model": OR_MODEL, "temperature": 0.2,
                       "messages": [{"role": "system", "content": sys_p},
                                    {"role": "user", "content": items_json}],
                       "max_tokens": 3000}).encode()
    req = urllib.request.Request(OR_URL, data=body, headers={
        "Content-Type": "application/json", "Authorization": f"Bearer {OR_KEY}"})
    with urllib.request.urlopen(req, timeout=180) as r:
        out = json.loads(r.read().decode())["choices"][0]["message"]["content"]
    m = re.search(r"\[.*\]", out, re.S)
    return json.loads(m.group()) if m else []

def esc(s):
    return (str(s) if s is not None else "").replace("\\", "").replace("'", "''")[:480]

def main():
    force = "--force" in sys.argv
    cities = [a for a in sys.argv[1:] if not a.startswith("--")]
    where_city = f" AND city IN ({','.join(chr(39)+c+chr(39) for c in cities)})" if cities else ""
    #只补头部：景点 rating>=4.0 且缺 intro；
    cond = "" if force else " AND (intro IS NULL OR intro='')"
    spots = sql(f"SELECT src_id, name, city, types FROM attractions_features WHERE rating>=4.0{cond}{where_city} ORDER BY rating DESC LIMIT 400")
    print(f"待补景点: {len(spots)}")
    BATCH = 12
    ok = fail = 0
    for i in range(0, len(spots), BATCH):
        batch = spots[i:i+BATCH]
        items = [{"name": n, "city": c, "types": (t or "")[:40]} for _, n, c, t in batch]
        try:
            results = ask_llm(json.dumps(items, ensure_ascii=False))
        except Exception as e:
            print(f"  [WARN] 批次{i//BATCH+1}: {str(e)[:100]}"); time.sleep(5); continue
        by_name = {r_.get("name"): r_ for r_ in results if isinstance(r_, dict)}
        for fid, name, city, _ in batch:
            it = by_name.get(name)
            if not it:
                continue
            sets = []
            tp = it.get("ticket_price")
            if tp and tp not in ("未知", ""):
                sets.append(f"ticket_price='{esc(tp)}'")
            free = it.get("is_free")
            if free in (True, False):
                sets.append(f"is_free={1 if free else 0}")
            dur = it.get("visit_duration")
            if dur and dur != "未知":
                sets.append(f"visit_duration='{esc(dur)}'")
            intro = it.get("intro")
            if intro and intro != "未知":
                sets.append(f"intro='{esc(intro)}'")
            bk = it.get("booking_required")
            if bk and bk != "未知":
                sets.append(f"booking_required='{esc(bk)}'")
            tags = it.get("suitable_tags")
            if tags and tags != "未知":
                sets.append(f"suitable_tags='{esc(tags)[:95]}'")
            if sets:
                sets.append("source=IF(source='rule' OR source IS NULL, 'ai_general', source)")
                sql(f"UPDATE attractions_features SET {','.join(sets)} WHERE src_id={fid}", fetch=False)
                ok += 1
            else:
                fail += 1
        print(f"  批次{i//BATCH+1}/{(len(spots)+BATCH-1)//BATCH}: 更新{ok} 跳过{fail}")
        time.sleep(2)

if __name__ == "__main__":
    main()
