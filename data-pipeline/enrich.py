#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第二步：为特征表补"真实评价/开放时间"字段（来自高德 v5 place API business 字段）
- rectag(游客评价标签) -> tips        例: "成都最具特色的街道"
- opentime_week(真实开放时间) -> best_time  例: "周一至周日 07:30-18:00"
- source='amap_v5'（真实、较新、可再生）
用法: python3 data-pipeline/enrich.py [城市...] [--force]
"""
import json, os, re, subprocess, sys, time, urllib.request, urllib.parse, gzip

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

def sql(query, fetch=True):
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    cmd = ["mysql", f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-N", "-B", "-e", query]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"[WARN] SQL错误: {r.stderr.strip()[:150]}"); return []
    if not fetch: return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=10) as r:
        raw = r.read()
        if raw[:2] == b'\x1f\x8b': raw = gzip.decompress(raw)
        return raw.decode("utf-8", "ignore")

def esc(s):
    return (s or "").replace("\\", "").replace("'", "''")[:490]

def main():
    force = "--force" in sys.argv
    cities = [a for a in sys.argv[1:] if not a.startswith("--")]
    where_city = f" AND city IN ({','.join(chr(39)+c+chr(39) for c in cities)})" if cities else ""
    cond = "" if force else " AND (tips IS NULL OR tips='')"
    rows = sql(f"SELECT src_id, name, city FROM attractions_features WHERE name IS NOT NULL AND name!=''{cond}{where_city}")
    print(f"待补景点: {len(rows)} 条")
    ok, miss = 0, 0
    for idx, (fid, name, city) in enumerate(rows):
        try:
            h = get("https://restapi.amap.com/v5/place/text?" + urllib.parse.urlencode(
                {"keywords": name, "city": city, "key": KEY, "show_fields": "business"}))
            d = json.loads(h)
            pois = d.get("pois") or []
            if not pois:
                miss += 1; continue
            biz = pois[0].get("business") or {}
            tips = esc(biz.get("rectag"))[:490]
            open_time = esc(biz.get("opentime_week") or biz.get("opentime_today"))[:250]
            rating = biz.get("rating")
            sets = [f"tips='{tips}'", f"best_time='{open_time}'", "source='amap_v5'"]
            if rating:
                try: sets.append(f"rating={float(rating)}")
                except ValueError: pass
            sql(f"UPDATE attractions_features SET {','.join(s for s in sets if s != chr(39)+chr(39))} WHERE src_id={fid}", fetch=False)
            ok += 1
        except Exception as e:
            print(f"  [WARN] {name}: {str(e)[:60]}")
        time.sleep(0.35)  # 高德QPS限速
        if (idx+1) % 50 == 0:
            print(f"  进度 {idx+1}/{len(rows)} (成功{ok} 未匹配{miss})")
    # 更新统计到表备注
    print(f"完成: 成功 {ok}, 未匹配 {miss}, 共 {len(rows)}")

if __name__ == "__main__":
    main()
