#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第二步：高德v5为特征表补 tips(评价标签)/best_time(开放时间)/rating。
tips 取值优先级：rectag合格→rectag，否则keytag回退；keytag带3A/4A/5A顺带回填scenic_grade。
用法: python3 data-pipeline/enrich.py [城市...] [--force] [--baidu-ak <AK>]
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

# 类型词黑名单：rectag 若只是这类泛化词，对游客无信息量，不算有效 tips
_TIP_TYPE_WORDS = [
    "文物古迹", "景区", "广场", "公园", "寺庙", "城市公园", "滨江公园", "红色地标",
    "生态公园", "自然风光", "特色景点", "湖泊河流", "古建筑", "特色街区", "休闲度假",
    "人文景观", "纪念馆", "湿地公园", "观景点", "博物馆", "教堂", "清真寺", "园林",
    "名胜古迹", "旅游景点", "游乐场", "动物园", "植物园", "海洋馆", "度假村",
    "森林公园", "地质公园", "主题公园", "历史遗迹", "文化古迹",
    # 高德 rectag 实际出现的泛化词
    "风景名胜", "红色景点", "步道", "绿道", "湿地", "名山丘陵", "道观", "古镇古村",
    "特色商业街", "名人故居", "古城镇", "江河湖泊", "山岳", "陵园", "碑塔",
    # 入库抽查补漏
    "古镇", "古街", "山峰", "景点附属设施", "旅游码头",
]
# 时效噪音（如"疫情轨迹点"，过时且无旅行价值）
_TIP_NOISE_RE = re.compile(r"疫情")
# 节令推广词（如"春节周边/春节游园"，过季即失效）
_TIP_SEASONAL_RE = re.compile(r"^(春节|五一|十一|国庆|端午|中秋|清明|暑期|寒假|周末)")
# 场馆类名词后缀："市中心公园"这类=限定词+类型名词，仍判类型词（刻意保守防误杀好标签）
_TIP_NOUN_SUFFIX = (
    "公园", "景区", "广场", "寺庙", "博物馆", "纪念馆", "教堂", "清真寺", "园林",
    "古建筑", "度假区", "度假村", "游乐场", "动物园", "植物园", "海洋馆",
)
_GRADE_RE = re.compile(r"(?:[345]A)")

def _is_type_word(tag):
    """rectag 是否为低价值类型词：整词命中 / 节令词 / 组合拆分全命中 / 场馆名词结尾"""
    t = (tag or "").strip()
    if not t:
        return True
    if t in _TIP_TYPE_WORDS:
        return True
    if _TIP_NOISE_RE.search(t):
        return True
    if _TIP_SEASONAL_RE.match(t):
        return True
    # 组合型如"公园/景区"、"风景名胜,风景名胜相关"：拆分后剥"相关"后缀再比对
    parts = re.split(r"[/、\s,，]+", t)
    parts = [re.sub(r"相关$", "", p) for p in parts if p]
    if parts and all(p in _TIP_TYPE_WORDS for p in parts):
        return True
    # 名词后缀型："市中心公园"以场馆类名词结尾 → 类型词
    if any(t.endswith(s) for s in _TIP_NOUN_SUFFIX):
        return True
    return False

def sql(query, fetch=True):
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    mysql_bin = ENV.get("MYSQL_BIN") or os.environ.get("MYSQL_BIN") or "mysql"
    cmd = [mysql_bin, f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
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

def pick_tips_and_grade(biz):
    """返回 (tips, grade_or_None)：rectag 合格用 rectag，否则回退 keytag；keytag 带等级则顺带回填"""
    rectag = (biz.get("rectag") or "").strip()
    keytag = (biz.get("keytag") or "").strip()
    grade = None
    m = _GRADE_RE.search(keytag)
    if m:
        grade = m.group()
    if not _is_type_word(rectag):
        return rectag, grade          # rectag 是真评价标签 → 首选
    if keytag and not _is_type_word(keytag):
        return keytag, grade          # rectag 是类型词 → 回退 keytag("4A景区/人文景观"等)
    return "", grade                  # 都不合格 → 置空（宁缺勿滥）

def _baidu_fill(fid, name, city, ak):
    """百度 Place API(scope=2) 兜底：只补 best_time 和空值 rating，成功返回True"""
    try:
        u = "https://api.map.baidu.com/place/v2/search?" + urllib.parse.urlencode(
            {"query": name, "region": city or "全国", "city_limit": "true",
             "output": "json", "scope": "2", "ak": ak})
        d = json.loads(get(u))
        if str(d.get("status")) != "0":
            return False
        res = d.get("results") or []
        if not res:
            return False
        di = res[0].get("detail_info") or {}
        sets = []
        hours = (di.get("shop_hours") or "").strip()
        if hours:
            sets.append(f"best_time='{esc(hours)[:250]}'")
        rating = (di.get("overall_rating") or "").strip()
        if rating:
            try:
                rv = float(rating)
                if rv > 0:
                    sets.append(f"rating=IF(rating IS NULL,{rv},rating)")
            except ValueError:
                pass
        if not sets:
            return False
        sets.append("source='baidu_v2'")
        sql(f"UPDATE attractions_features SET {','.join(sets)} WHERE src_id={fid}", fetch=False)
        return True
    except Exception:
        return False

def main():
    force = "--force" in sys.argv
    # --baidu-ak 可选：高德失败/未匹配的行自动用百度Place API兜底
    baidu_ak = ""
    if "--baidu-ak" in sys.argv:
        i = sys.argv.index("--baidu-ak")
        baidu_ak = sys.argv[i + 1] if i + 1 < len(sys.argv) else ""
    cities = [a for a in sys.argv[1:] if not a.startswith("--") and a != baidu_ak]
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
                miss += 1
                # ★高德未匹配 → 百度兜底（补 best_time/空rating）
                if baidu_ak and _baidu_fill(fid, name, city, baidu_ak):
                    miss -= 1; ok += 1
                continue
            biz = pois[0].get("business") or {}
            tips, grade = pick_tips_and_grade(biz)
            open_time = esc(biz.get("opentime_week") or biz.get("opentime_today"))[:250]
            rating = biz.get("rating")
            sets = []
            if tips:
                sets.append(f"tips='{esc(tips)[:490]}'")
            else:
                sets.append("tips=''")   # 黑名单类型词不再入库，清空待后续 LLM/UGC 补充
            sets.append(f"best_time='{open_time}'")
            sets.append("source='amap_v5'")
            if rating:
                try: sets.append(f"rating={float(rating)}")
                except ValueError: pass
            if grade:
                # ★keytag 等级回填：列内条件赋值（不覆盖已有等级），不影响同语句其他列
                sets.append(f"scenic_grade=IF(scenic_grade IS NULL OR scenic_grade='', '{grade}', scenic_grade)")
            sql(f"UPDATE attractions_features SET {','.join(sets)} WHERE src_id={fid}", fetch=False)
            ok += 1
        except Exception as e:
            print(f"  [WARN] {name}: {str(e)[:60]}")
            # ★高德异常(如配额耗尽) → 百度兜底
            if baidu_ak and _baidu_fill(fid, name, city, baidu_ak):
                ok += 1
        time.sleep(0.35)  # 高德QPS限速
        if (idx+1) % 50 == 0:
            print(f"  进度 {idx+1}/{len(rows)} (成功{ok} 未匹配{miss})")
    # 更新统计到表备注
    print(f"完成: 成功 {ok}, 未匹配 {miss}, 共 {len(rows)}")

if __name__ == "__main__":
    main()
