#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
待办①："其他"类别数据 LLM 二次分类（混合两步）。
背景：preprocess.py 用关键词规则打标(types列)，未命中的落"其他"（约1800条）。
做法：
  第1步 确定性规则：按【名称】强信号直接打标（公园→城市公园、博物馆→人文历史、火锅→火锅等，
        高精度词才命中，不依赖LLM，零成本零幻觉）
  第2步 LLM兜底：剩余模糊条目分批调 py 侧统一模型（llm_config.chat()，管理端可热更），
        提示词带全类别均衡示例；解析失败自动重试1次
- 标签体系与 preprocess.py 完全一致：
  景点: 自然风光/人文历史/主题乐园/特色街区/城市公园   美食: 火锅/地方菜/小吃快餐/饮品甜点/异国料理
- 幂等：只处理 label='其他' 的行
用法: python3 data-pipeline/reclassify.py [--batch 50]
"""
import json, os, re, subprocess, sys, time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from llm_config import chat  # ★py侧统一模型配置（与管理端热更同源）

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
        print(f"[WARN] SQL错误: {r.stderr.strip()[:150]}"); return []
    if not fetch: return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []

LABELS = {
    "attractions": ["自然风光", "人文历史", "主题乐园", "特色街区", "城市公园"],
    "foods":       ["火锅", "地方菜", "小吃快餐", "饮品甜点", "异国料理"],
}

# ---------- 第1步：名称强信号确定性规则（顺序即优先级，只收高精度词） ----------
ATTR_NAME_RULES = [
    (r"森林公园|地质公园|国家公园|自然保护区", "自然风光"),      # 自然类公园优先于城市公园
    (r"山|峡谷|瀑布|温泉|草原|雪场|滑雪|海滨|海滩|海湾|半岛|洞穴|溶洞|冰川|沙漠|梯田", "自然风光"),
    (r"游乐场|游乐园|乐园|海洋馆|动物园|水世界|欢乐世界|主题公园", "主题乐园"),
    (r"博物馆|美术馆|科技馆|纪念馆|展览馆|祠|寺庙|寺|庙|庵|塔|陵|故居|旧址|遗址|古城|古镇|书院|道观|清真寺|教堂", "人文历史"),
    (r"公园|园林|植物园|苑", "城市公园"),
    (r"街|巷|坊|夜市|商圈|步行街|广场|集市|市场", "特色街区"),
]
FOOD_NAME_RULES = [
    (r"火锅|串串", "火锅"),
    (r"咖啡|咖啡厅|茶馆|茶楼|奶茶|甜品|蛋糕|烘焙|面包|冰淇淋|果汁", "饮品甜点"),
    (r"寿司|日料|日本料理|刺身|韩式|韩国料理|泰式|东南亚|西餐|牛排|披萨|意大利菜|法国菜|自助", "异国料理"),
    (r"面馆|小面|米粉|米线|饺子|馄饨|抄手|小吃|快餐|汉堡|炸鸡|烧烤|卤味|麻辣烫", "小吃快餐"),
]

def pre_rule(name, table):
    pats = ATTR_NAME_RULES if table == "attractions" else FOOD_NAME_RULES
    for pat, lab in pats:
        if re.search(pat, name):
            return lab
    return None

# ---------- 第2步：LLM 兜底（模糊名称） ----------
def build_prompt(table, items_json):
    labs = " / ".join(LABELS[table])
    kind = "景点" if table == "attractions" else "美食店铺"
    demo = ('"交子公园"→城市公园；"青城山"→自然风光；"武侯祠"→人文历史；'
            '"欢乐谷"→主题乐园；"宽窄巷子"→特色街区；"双子塔夜景打卡点"→特色街区'
            if table == "attractions" else
            '"小龙坎火锅"→火锅；"烤匠麻辣烤鱼"→地方菜；"军屯锅盔"→小吃快餐；'
            '"喜茶"→饮品甜点；"萨莉亚意式餐厅"→异国料理')
    return (
        f"你是旅游数据标注员。下面是{kind}数据(JSON数组)，请给每条打一个类别标签。\n"
        f"可选标签（只能从中选一个）：{labs}\n"
        f"判断依据：名称含义 > types类型词 > 城市常识。注意各类都要有，不要集中归到某一类。\n"
        f"示例：{demo}。\n"
        f"要求：确实无法判断才可放弃该条（跳过不输出）；"
        f'只输出JSON数组不要其他文字，格式：[{{"id":<src_id>,"label":"<标签>"}},...]。\n\n'
        f"{items_json}"
    )

def _parse_labels(out, valid):
    """解析LLM输出为 {src_id: label}；数组截断时正则逐对象兜底提取"""
    got = {}
    m = re.search(r"\[.*\]", out, re.S)
    if m:
        try:
            arr = json.loads(m.group())
            for it in arr:
                try:
                    lid, lab = int(it["id"]), str(it["label"]).strip()
                except Exception:
                    continue
                if lab in valid:
                    got[lid] = lab
        except json.JSONDecodeError:
            pass  # 落入下方正则兜底
    if not got:
        # ★推理模型长输出可能截断数组：逐个 {"id":..,"label":".."} 对象正则抢救
        for pm in re.finditer(r'\{\s*"id"\s*:\s*(\d+)\s*,\s*"label"\s*:\s*"([^"]+)"', out):
            lid, lab = int(pm.group(1)), pm.group(2).strip()
            if lab in valid:
                got[lid] = lab
    return got

def classify_batch(table, items, depth=0):
    """一批调LLM返回 {src_id: label}；失败重试1次；仍败且批>5条则对半拆批递归；
    单批单标签>80%判为偷懒，丢弃本批结果"""
    items_min = [{"id": int(r[0]), "name": r[1], "city": r[2], "types": r[3]} for r in items]
    payload = json.dumps(items_min, ensure_ascii=False)
    valid = set(LABELS[table])
    last_err = None
    for attempt in (1, 2):
        try:
            # ★max_tokens 8000：推理模型会把 token 花在思考上，太小会 content 为空；
            #   单次 timeout 90s：正常批<60s，超时快速失败进重试/拆批，避免长阻塞
            out = chat([{"role": "user", "content": build_prompt(table, payload)}],
                       temperature=0.3, max_tokens=8000, timeout=90)
            got = _parse_labels(out, valid)
            if not got:
                raise ValueError(f"无可用JSON: {out[:80]}")
            # 防偷懒检查：≥15条的批若单一标签占比>80% → 视为模式坍缩，弃用
            if len(items_min) >= 15 and got:
                from collections import Counter
                top_lab, top_n = Counter(got.values()).most_common(1)[0]
                if top_n > len(got) * 0.8:
                    raise ValueError(f"标签坍缩: {top_lab} 占 {top_n}/{len(got)}")
            return got
        except Exception as e:
            last_err = e
            print(f"  [WARN] 批次第{attempt}次失败: {str(e)[:100]}" + ("，重试..." if attempt == 1 else ""))
            time.sleep(3)
    # ★对半拆批递归：大批推理超限时空返回，拆小后成功率高
    if len(items) > 5:
        mid = len(items) // 2
        print(f"  [SPLIT] 对半拆批 {len(items)} → {mid}+{len(items)-mid}")
        left = classify_batch(table, items[:mid], depth+1)
        right = classify_batch(table, items[mid:], depth+1)
        return {**left, **right}
    print(f"  [FAIL] 本批放弃(≤5条): {last_err}")
    return {}

def main():
    batch_size = 20  # ★批20：50条长输出易被 max_tokens 截断（实测），小批更稳
    argv = sys.argv[1:]
    if "--batch" in argv:
        batch_size = max(5, min(100, int(argv[argv.index("--batch") + 1])))
    total_moved = 0
    for table in ("attractions", "foods"):
        rows = sql(f"SELECT src_id, name, IFNULL(city,''), IFNULL(types,'') FROM {table}_features "
                   f"WHERE label='其他' AND name IS NOT NULL AND name!=''")
        print(f"\n=== {table}: 待二次分类 {len(rows)} 条 ===")
        moved = 0
        # 第1步：确定性规则
        llm_rows = []
        for r in rows:
            lab = pre_rule(r[1], table)
            if lab:
                safe = lab.replace("'", "''")
                sql(f"UPDATE {table}_features SET label='{safe}' WHERE src_id={r[0]}", fetch=False)
                moved += 1
            else:
                llm_rows.append(r)
        print(f"  规则打标 {moved} 条，剩模糊项 {len(llm_rows)} 条交LLM")
        # 第2步：LLM 兜底
        done = set()
        batches = [llm_rows[i:i+batch_size] for i in range(0, len(llm_rows), batch_size)]
        for bi, bat in enumerate(batches, 1):
            got = classify_batch(table, bat)
            for sid, lab in got.items():
                if sid in done:
                    continue
                sql(f"UPDATE {table}_features SET label='{lab}' WHERE src_id={sid}", fetch=False)
                done.add(sid); moved += 1
            print(f"  LLM批 {bi}/{len(batches)}: 本批 {len(got)}/{len(bat)} 成功，累计 {moved}")
            time.sleep(1.5)
        left = sql(f"SELECT COUNT(*) FROM {table}_features WHERE label='其他'")
        print(f"--- {table}: 共打标 {moved} 条，剩余'其他' {left[0][0] if left else '?'} 条 ---")
        total_moved += moved
    print(f"\n🎉 二次分类完成，共打标 {total_moved} 条")

if __name__ == "__main__":
    main()
