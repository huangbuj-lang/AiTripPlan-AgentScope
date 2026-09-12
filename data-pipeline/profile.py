#!/usr/bin/env python3
#-*- coding: utf-8 -*-
"""
⑥ 用户画像：全量 LLM 提取（不再用关键词词典/计数规则）
- 汇总该用户原始数据：行程(trip_plans)/发帖(posts)/点赞(post_likes)/主动偏好(user_preferences)
- 单次调用 py 侧统一模型（llm_config，管理端可热更）直接生成画像标签
- 写 user_profiles_tags，格式不变 {"类别-标签": 强度1~5}，前端弹窗与主管注入无需改动
- 类别前缀约定：常去-/偏好-/风格-（与前端配色映射一致）
- LLM 失败或返回为空 → 保留旧画像不清空；--user 模式下无任何源数据才清空
用法: python3 data-pipeline/profile.py            # 全部用户
      python3 data-pipeline/profile.py --user 12  # 单用户（ProfileHook 触发）
"""
import os, re, sys, json, subprocess, time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {}
for line in open(os.path.join(BASE, ".env"), encoding="utf-8"):
    m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
    if m:
        ENV[m.group(1)] = m.group(2).strip().strip('"').strip("'")

from llm_config import chat as _llm_chat  # 统一模型配置（管理端可热更）
from pipeline_config import get_profile_config  # 数据管线配置（Nacos 可热更）

RDS_HOST = ENV.get("RDS_HOST", "127.0.0.1:3306")
RDS_USER = ENV.get("RDS_USER", "root")
RDS_PASS = ENV.get("RDS_PASSWORD", "")

def _allow_prefixes():
    prefixes = get_profile_config().get("allowPrefixes", ["常去", "偏好", "风格"])
    return tuple(prefixes) if isinstance(prefixes, list) else ("常去", "偏好", "风格")


def sql(query, fetch=True):
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    cmd = ["mysql", f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-N", "-B", "-e", query]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        print(f"⚠️ SQL错误: {r.stderr.strip()[:150]}")
        return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()] if r.stdout.strip() else []


def _clean(s, limit=160):
    """压成单行摘要（去HTML标签），供拼进 LLM 提示词。"""
    s = re.sub(r"<[^>]+>", " ", (s or ""))  # 行程内容可能是HTML，先剥标签
    s = re.sub(r"\s+", " ", s).strip()  # 压成单行
    return s[:limit]


def _profile_limit():
    return int(get_profile_config().get("maxRecords", 30))


def collect_digest(user_id):
    """汇总该用户全部原始数据为一段文本；返回 (digest, 是否有数据)。"""
    n = _profile_limit()
    lines, has = [], False
    trips = sql(f"SELECT city, days, plan_content FROM trip_plans WHERE user_id={user_id} ORDER BY id LIMIT {n}")
    if trips:
        has = True
        lines.append(f"【保存的行程】共{len(trips)}条：")
        for i, (city, days, content) in enumerate(trips, 1):
            lines.append(f"{i}. {_clean(city, 20)}·{_clean(days, 4)}日 | {_clean(content)}")
    posts = sql(f"SELECT title, content FROM posts WHERE user_id={user_id} ORDER BY id DESC LIMIT {n}")
    likes = sql(f"SELECT p.title, p.content FROM post_likes l JOIN posts p ON p.id=l.post_id "
                f"WHERE l.user_id={user_id} ORDER BY l.created_at DESC LIMIT {n}")
    if posts:
        has = True
        lines.append(f"【发布的帖子】共{len(posts)}篇：")
        for i, (title, content) in enumerate(posts, 1):
            lines.append(f"{i}. {_clean(title, 40)} | {_clean(content)}")
    if likes:
        has = True
        lines.append(f"【点赞的文章】共{len(likes)}篇：")
        for i, (title, content) in enumerate(likes, 1):
            lines.append(f"{i}. {_clean(title, 40)} | {_clean(content)}")
    prefs = sql(f"SELECT travel_style, preferred_style, budget_level, pace, museum_ratio, "
                f"walk_tolerance, chronotype, avoid_list FROM user_preferences WHERE user_id={user_id}")
    if prefs and any(v and v != "NULL" for v in prefs[0]):
        has = True
        keys = ["旅行风格", "偏好类型", "预算级", "节奏", "博物馆占比", "步行容忍", "作息", "避讳"]
        kv = "；".join(f"{k}={_clean(v, 60)}" for k, v in zip(keys, prefs[0]) if v and v != "NULL")
        lines.append(f"【个人中心主动设置】{kv}")
    return "\n".join(lines), has


def ask_llm(digest):
    """单次 LLM 调用生成画像标签；失败抛异常。返回 {标签: 强度}。"""
    sys_p = ("你是旅游平台用户画像分析器。根据该用户的行程记录、发帖、点赞与主动设置，"
             "推断其旅行画像标签。\n"
             '输出一个JSON对象：key 为 "类别-名称"，value 为强度整数1~5（证据越多越高）。\n'
             "类别只能选：常去（目的地）、偏好（旅行方式/兴趣）、风格（内容兴趣）。\n"
             "要求：最多10个标签；必须有真实依据，禁止编造；目的地用城市名本身"
             "（如多次去成都→\"常去-成都\"，不要带\"三日\"等后缀）；"
             "只输出 JSON 对象，不要任何其他文字。")
    msgs = [{"role": "system", "content": sys_p},
            {"role": "user", "content": digest[:6000]}]
    out = ""
    for attempt in range(3):  # 限流/网络抖动/空响应 重试3次（指数退避）
        try:
            out = _llm_chat(msgs, temperature=0.2, max_tokens=3000, timeout=180)
        except Exception as e:
            if attempt == 2:
                raise
            wait = 15 * (attempt + 1)
            print(f"    LLM调用失败({str(e)[:60]})，{wait}s后重试...")
            time.sleep(wait)
            continue
        if out and out.strip():
            break
        if attempt < 2:
            print("    LLM空响应，15s后重试...")
            time.sleep(15)
    m = re.search(r"\{.*\}", out, re.S)
    if not m:
        raise ValueError("LLM 未返回 JSON 对象")
    raw = json.loads(m.group())
    tags = {}
    for k, v in raw.items():
        if not isinstance(k, str) or not k.startswith(_allow_prefixes()):
            continue
        try:
            w = int(v)
        except Exception:
            continue
        tags[k] = max(1, min(w, 5))
    return dict(sorted(tags.items(), key=lambda kv: -kv[1])[:15])


def esc(s):
    return (s or "").replace("\\", "").replace("'", "''")


def _write_tags(user_id, tags):
    tj = json.dumps(tags, ensure_ascii=False).replace("'", "''")
    sql(f"INSERT INTO user_profiles_tags(user_id, tags) VALUES ({user_id}, '{tj}') "
        f"ON DUPLICATE KEY UPDATE tags=VALUES(tags)", fetch=False)


def rebuild_user(user_id):
    """单用户画像全量重算（ProfileHook 触发）：LLM 失败保留旧值，无数据清空。"""
    sql("""CREATE TABLE IF NOT EXISTS user_profiles_tags (
        user_id BIGINT PRIMARY KEY, tags JSON, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    try:
        digest, has_data = collect_digest(user_id)
    except Exception as e:
        print(f"user {user_id}: 数据汇总失败 {e}")
        return
    if not has_data:
        sql(f"DELETE FROM user_profiles_tags WHERE user_id={user_id}", fetch=False)
        print(f"user {user_id}: 无源数据, cleared")
        return
    try:
        tags = ask_llm(digest)
    except Exception as e:
        print(f"⚠️ user {user_id}: LLM失败({str(e)[:100]})，保留旧画像")
        return
    if not tags:
        print(f"⚠️ user {user_id}: LLM未给出有效标签，保留旧画像")
        return
    _write_tags(user_id, tags)
    print(f"user {user_id}: {tags}")


def main():
    sql("""CREATE TABLE IF NOT EXISTS user_profiles_tags (
        user_id BIGINT PRIMARY KEY, tags JSON, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;""", fetch=False)
    users = [r[0] for r in sql("SELECT id FROM users")]
    done = 0
    for uid in users:
        digest, has_data = collect_digest(uid)
        if not has_data:
            continue  # 无行程/发帖/偏好的用户跳过
        try:
            tags = ask_llm(digest)
        except Exception as e:
            print(f"⚠️ user {uid}: LLM失败({str(e)[:100]})，跳过")
            continue
        if tags:
            _write_tags(uid, tags)
            print(f"  ✅ 用户{uid}: {tags}")
            done += 1
        time.sleep(5)  # 用户间间隔，防API限流
    print(f"🎉 画像完成（LLM提取），更新 {done}/{len(users)} 个用户")


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--user":
        rebuild_user(int(sys.argv[2]))
    else:
        main()
