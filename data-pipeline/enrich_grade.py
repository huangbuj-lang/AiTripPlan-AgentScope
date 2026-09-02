#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
景区等级回填：5A/4A 名单（维基百科"国家5A级旅游景区"词条表格 + 文旅部公告）
匹配策略：特征表 name 与名单名称 双向包含匹配（处理"承德避暑山庄及周围寺庙景区"这类全称差异）
source 不变（等级是属性不是来源）；匹配不上的留空。
用法: python3 data-pipeline/enrich_grade.py
"""
import json, os, re, subprocess, urllib.request, gzip

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
        print(f"[WARN] SQL错误: {r.stderr.strip()[:120]}"); return []
    if not fetch: return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

def fetch_wiki_names():
    """从维基百科 5A 词条提取全部景区名"""
    url = "https://zh.wikipedia.org/wiki/%E5%9B%BD%E5%AE%B65A%E7%BA%A7%E6%97%85%E6%B8%B8%E6%99%AF%E5%8C%BA"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=20) as r:
        raw = r.read()
        if raw[:2] == b'\x1f\x8b': raw = gzip.decompress(raw)
        h = raw.decode('utf-8', 'ignore')
    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', h, re.S)
    names = set()
    for row in rows:
        cells = re.findall(r'<td[^>]*>(.*?)</td>', row, re.S)
        for c in cells:
            t = re.sub(r'<[^>]+>', '\n', c)
            t = re.sub(r'&[a-z]+;', '', t)
            for part in re.split(r'\n+', t):
                p = part.strip().strip('（）()·、 ')
                # 景区名特征：含地名后缀词，无日期数字
                if re.search(r'(景区|旅游区|风景名胜区)$', p) and 5 <= len(p) <= 30 \
                   and not re.search(r'\d{4}|\d+月|\d+日|^[-·、]', p):
                    names.add(p)
    return sorted(names)

def core(s):
    """去修饰取核心词，提升匹配率"""
    s = re.sub(r'(国家|旅游|风景|文化|生态|名胜|旅游区|景区|风景区)$', '', s)
    return s

def main():
    wiki = fetch_wiki_names()
    print(f"维基5A名单: {len(wiki)} 条（含拆分碎片，双向模糊匹配兜底）")
    rows = sql("SELECT src_id, name FROM attractions_features WHERE scenic_grade IS NULL AND rating>=4.0")
    matched, hits = 0, []
    for fid, name in rows:
        hit = None
        for w in wiki:
            if len(w) < 6:
                continue
            # 名单词在POI名中（POI为名单简称/全称）；POI名更长含子园名的不标（如"东湖梨园"≠东湖5A）
            if w in name and len(name) - len(w) <= 2:
                hit = "5A"; break
            if name in w and len(w) - len(name) <= 2:
                hit = "5A"; break
        if hit:
            sql(f"UPDATE attractions_features SET scenic_grade='{hit}' WHERE src_id={fid}", fetch=False)
            matched += 1
            hits.append(name)
    print(f"5A 匹配回填: {matched}/{len(rows)}")
    print("样例:", hits[:10])

if __name__ == "__main__":
    main()
