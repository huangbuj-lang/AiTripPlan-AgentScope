#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
⑤ 智能客服：独立 FastAPI 服务（端口 8090），RAG(特征表检索) + DeepSeek LLM 生成
- 不碰 Java 核心链路
- 参考开源模式：知识检索增强 + LLM 生成
用法: python3 data-pipeline/chat_server.py   (启动后 POST /chat)
"""
import json, os, re, subprocess, urllib.request
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = {}
for line in open(os.path.join(BASE, ".env"), encoding="utf-8"):
    m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
    if m:
        ENV[m.group(1)] = m.group(2).strip().strip('"').strip("'")

from llm_config import get_llm_config  # ★统一模型配置（管理端保存后自动重启本服务生效）
_CFG = get_llm_config()
DEEPSEEK_KEY = _CFG["key"]
MODEL = _CFG["name"]
RDS_HOST = ENV.get("RDS_HOST", "127.0.0.1:3306")
RDS_USER = ENV.get("RDS_USER", "root")
RDS_PASS = ENV.get("RDS_PASSWORD", "")

app = FastAPI(title="AiTripPlan 智能客服")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

class ChatReq(BaseModel):
    message: str

def sql_fetch(query):
    host, port = (RDS_HOST.split(":") + ["3306"])[:2]
    cmd = ["mysql", f"-h{host}", f"-P{port}", f"-u{RDS_USER}", f"-p{RDS_PASS}",
           "--default-character-set=utf8mb4", "-D", "aitripplan", "-N", "-B", "-e", query]
    r = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout.strip():
        return []
    return [line.split("\t") for line in r.stdout.strip().splitlines()]

def retrieve(message):
    """简单 RAG：从特征表按 城市+意图 检索 top 结果"""
    cities = [c[0] for c in sql_fetch("SELECT DISTINCT city FROM attractions_features")]
    city = next((c for c in cities if c in message), "")
    intent = "景点"
    if any(k in message for k in ["美食", "吃", "餐厅", "火锅", "小吃"]):
        intent = "美食"
    elif any(k in message for k in ["酒店", "住", "住宿", "宾馆"]):
        intent = "酒店"
    table = {"景点": "attractions_features", "美食": "foods_features", "酒店": "hotels_features"}[intent]
    where = f"WHERE city='{city}'" if city else ""
    rows = sql_fetch(f"SELECT name, city, label, rating, level FROM {table} {where} ORDER BY rating DESC LIMIT 6")
    lines = [f"- {r[0]}（{r[1]}，{r[2]}，评分{r[3]}，{r[4]}档）" for r in rows if len(r) >= 5]
    return intent, city, "\n".join(lines) if lines else "（暂无该城市数据，请换一个城市）"

def chat_llm(message, intent, city, context):
    """调 DeepSeek OpenAI 兼容接口生成回答"""
    sys_p = ("你是智能旅游客服，基于真实数据回答，不要编造。"
             f"用户问的是[{intent}]{'，城市'+city if city else ''}。检索到的真实数据：\n" + context)
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "system", "content": sys_p},
                     {"role": "user", "content": message}],
        "temperature": 0.6, "max_tokens": 800}).encode()
    req = urllib.request.Request(
        "https://api.deepseek.com/chat/completions", data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {DEEPSEEK_KEY}"})
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.loads(r.read().decode())
    return d["choices"][0]["message"]["content"]

@app.get("/health")
def health():
    return {"ok": True, "service": "chat", "model": MODEL}

@app.get("/local-knowledge")
def local_knowledge(city: str = "", preference: str = "", limit: int = 6):
    """本地知识库查询：从特征表按 城市+偏好 检索景点/美食/酒店（行程Agent并行调用）。"""
    if not city:
        return {"ok": False, "error": "city 必填"}
    out = {"city": city, "preference": preference or "", "items": []}
    tables = {"景点": "attractions_features", "美食": "foods_features", "酒店": "hotels_features"}
    # 用 preference 关键词决定查哪几类；无偏好则三类都给
    p = preference or ""
    intents = []
    if any(k in p for k in ["美食", "吃", "餐厅", "火锅", "小吃"]):
        intents.append("美食")
    if any(k in p for k in ["酒店", "住", "住宿", "宾馆"]):
        intents.append("酒店")
    if any(k in p for k in ["景点", "景区", "亲子", "自然", "人文", "儿童"]):
        intents.append("景点")
    if not intents:
        intents = ["景点", "美食", "酒店"]
    for name in intents:
        table = tables[name]
        rows = sql_fetch(
            f"SELECT name, label, rating, level FROM {table} "
            f"WHERE city='{city}' ORDER BY rating DESC LIMIT {limit}")
        items = [{"name": r[0], "label": r[1], "rating": r[2], "level": r[3]} for r in rows if len(r) >= 4]
        if items:
            out["items"].extend(items)
    if not out["items"]:
        return {"ok": True, "city": city, "items": [], "note": "该城市暂无本地数据，请依赖高德实时查询"}
    return {"ok": True, "city": city, "items": out["items"]}

@app.post("/chat")
def chat(req: ChatReq):
    if not DEEPSEEK_KEY:
        return {"ok": False, "error": "未配置 DEEPSEEK_API_KEY"}
    try:
        intent, city, context = retrieve(req.message)
        answer = chat_llm(req.message, intent, city, context)
        return {"ok": True, "intent": intent, "city": city, "answer": answer}
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8090)
