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
import time
import urllib.parse
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE, "llm_config.json")
ENV_PATH = os.path.join(os.path.dirname(BASE), ".env")

DEFAULTS = {
    "url": "https://openrouter.ai/api/v1",
    "key": "",
    "name": "stealth/ox-alpha",
}

DATA_ID = "aitripplan-model-config.json"
GROUP = os.environ.get("NACOS_MODEL_CONFIG_GROUP", "DEFAULT_GROUP")
_CACHE = {"at": 0.0, "cfg": None}


def _load_env():
    env = {}
    if os.path.exists(ENV_PATH):
        for line in open(ENV_PATH, encoding="utf-8"):
            m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
            if m:
                env[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    return env


def get_llm_config():
    """返回 Python 侧统一 LLM 配置：Nacos 优先，本地文件作兜底。"""
    now = time.monotonic()
    if _CACHE["cfg"] is not None and now - _CACHE["at"] < 5:
        return _CACHE["cfg"]

    remote = _nacos_config()
    if remote:
        cfg = dict(DEFAULTS)
        remote = remote.get("python", remote) if isinstance(remote, dict) else remote
        for key in ("url", "key", "name"):
            if remote.get(key):
                cfg[key] = str(remote[key]).strip()
        _CACHE.update(at=now, cfg=cfg)
        return cfg

    cfg = dict(DEFAULTS)
    try:
        if os.path.exists(CONFIG_PATH):
            data = json.load(open(CONFIG_PATH, encoding="utf-8"))
            for k in ("url", "key", "name"):
                v = data.get(k)
                if v:
                    cfg[k] = v
            return cfg
        _CACHE.update(at=now, cfg=cfg)
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
    _CACHE.update(at=now, cfg=cfg)
    return cfg


def _nacos_config():
    """Read Nacos 3 client config API; return None when unavailable."""
    server = os.environ.get("NACOS_SERVER_ADDR", "localhost:8848").strip()
    if server and not server.startswith(("http://", "https://")):
        server = "http://" + server
    params = urllib.parse.urlencode({
        "dataId": DATA_ID,
        "groupName": GROUP,
        "namespaceId": os.environ.get("NACOS_NAMESPACE", ""),
    })
    url = server.rstrip("/") + "/nacos/v3/client/cs/config?" + params
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if int(payload.get("code", -1)) != 0:
            return None
        data = payload.get("data")
        if isinstance(data, dict) and "content" in data:
            data = data["content"]
        if isinstance(data, str):
            data = json.loads(data)
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def _report_usage(usage, dimension):
    """★成本观测：把本次调用的 token 用量写 MySQL llm_cost_daily 日表（mysql CLI 子进程，
    与管线其他脚本同款连接方式，零额外依赖）。失败静默——观测绝不影响主流程。"""
    try:
        if not usage:
            return
        import os, re, subprocess
        env = {}
        base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env")
        with open(base, encoding="utf-8") as f:
            for line in f:
                m = re.match(r"^([A-Z_]+)=(.*)$", line.strip())
                if m:
                    env[m.group(1)] = m.group(2).strip().strip('"')
        host, port = (env.get("RDS_HOST", "127.0.0.1:3306").split(":") + ["3306"])[:2]
        user, pwd = env.get("RDS_USER", "root"), env.get("RDS_PASSWORD", "")
        sql = ("INSERT INTO llm_cost_daily(day,dimension,calls,input_tokens,output_tokens) "
               "VALUES(CURDATE(),'%s',1,%d,%d) "
               "ON DUPLICATE KEY UPDATE calls=calls+1,input_tokens=input_tokens+%d,output_tokens=output_tokens+%d"
               % (dimension.replace("'", ""),
                  usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0),
                  usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0)))
        subprocess.run(["mysql", "-h" + host, "-P" + port, "-u" + user, "-p" + pwd,
                        "--default-character-set=utf8mb4", "aitripplan", "-e", sql],
                       capture_output=True, timeout=15)
    except Exception:
        pass


def chat(messages, temperature=0.3, max_tokens=2000, timeout=120, dimension="pipeline"):
    """统一 chat 调用（OpenAI 兼容 /chat/completions）。返回 content 字符串，失败抛异常。
    ★dimension: 成本维度标签（默认 pipeline），token 用量自动落库 llm_cost_daily。"""
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
    _report_usage(resp.get("usage"), dimension)  # ★按维度记账
    return msg.get("content") or ""


if __name__ == "__main__":
    print(json.dumps(get_llm_config(), ensure_ascii=False, indent=1))
