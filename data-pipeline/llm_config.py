#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Python 侧统一模型配置读取（唯一出口）：
- 配置文件 data-pipeline/llm_config.json（管理端保存时由 AdminController 写入）
- 无配置文件时回退 .env 的 MODEL_* / OPENROUTER 兜底
用法:
    from llm_config import get_llm_config
    cfg = get_llm_config()  # {"url":..., "key":..., "name":...}
"""
import json
import os
import re

BASE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE, "llm_config.json")
ENV_PATH = os.path.join(os.path.dirname(BASE), ".env")

DEFAULTS = {
    "url": "https://openrouter.ai/api/v1",
    "key": "",
    "name": "stealth/ox-alpha",
}


def _load_env():
    env = {}
    if os.path.exists(ENV_PATH):
        for line in open(ENV_PATH, encoding="utf-8"):
            m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
            if m:
                env[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    return env


def get_llm_config():
    """返回 Python 侧统一 LLM 配置：llm_config.json 优先，缺省回退 .env/默认值。"""
    cfg = dict(DEFAULTS)
    try:
        if os.path.exists(CONFIG_PATH):
            data = json.load(open(CONFIG_PATH, encoding="utf-8"))
            for k in ("url", "key", "name"):
                v = data.get(k)
                if v:
                    cfg[k] = v
            return cfg
    except Exception:
        pass
    env = _load_env()
    if env.get("MODEL_API_KEY"):
        cfg["url"] = env.get("MODEL_BASE_URL", cfg["url"])
        cfg["key"] = env["MODEL_API_KEY"]
        cfg["name"] = env.get("MODEL_NAME", cfg["name"])
    elif env.get("DEEPSEEK_API_KEY"):
        cfg["url"] = "https://api.deepseek.com/v1"
        cfg["key"] = env["DEEPSEEK_API_KEY"]
        cfg["name"] = "deepseek-chat"
    return cfg


def chat(messages, temperature=0.3, max_tokens=2000, timeout=120):
    """统一 chat 调用（OpenAI 兼容 /chat/completions）。返回 content 字符串，失败抛异常。"""
    import urllib.request
    cfg = get_llm_config()
    body = json.dumps({
        "model": cfg["name"],
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }).encode()
    req = urllib.request.Request(
        cfg["url"].rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {cfg['key']}"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.loads(r.read().decode())
    msg = (resp.get("choices") or [{}])[0].get("message") or {}
    return msg.get("content") or ""


if __name__ == "__main__":
    print(json.dumps(get_llm_config(), ensure_ascii=False, indent=1))
