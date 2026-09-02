#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数据管线统一配置读取器（唯一出口）：
- Nacos 配置 aitripplan-pipeline-config.json（优先，5秒缓存）
- 本地兜底 data-pipeline/pipeline_config.json
- 代码内置默认值
用法:
    from pipeline_config import get_pipeline_config
    cfg = get_pipeline_config()
"""
import json
import os
import re
import time
import urllib.parse
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
LOCAL_CONFIG_PATH = os.path.join(BASE, "pipeline_config.json")
ENV_PATH = os.path.join(os.path.dirname(BASE), ".env")

DEFAULTS = {
    "pricing": {
        "hotelBase": 300.0,
        "holidayFactor": 1.3,
    },
    "profile": {
        "maxRecords": 30,
        "contentLimit": 160,
        "allowPrefixes": ["常去", "偏好", "风格"],
        "enabled": True,
    },
    "forecast": {
        "days": 7,
    },
}

DATA_ID = "aitripplan-pipeline-config.json"
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


def _nacos_config():
    env = _load_env()
    server = env.get("NACOS_SERVER_ADDR", "localhost:8848").strip()
    if server and not server.startswith(("http://", "https://")):
        server = "http://" + server
    params = urllib.parse.urlencode({
        "dataId": DATA_ID,
        "groupName": GROUP,
        "namespaceId": env.get("NACOS_NAMESPACE", ""),
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


def _deep_merge(base, override):
    result = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = _deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def get_pipeline_config():
    """返回数据管线配置：Nacos 优先，本地文件兜底，代码默认值保底。"""
    now = time.monotonic()
    if _CACHE["cfg"] is not None and now - _CACHE["at"] < 5:
        return _CACHE["cfg"]

    remote = _nacos_config()
    if remote:
        cfg = _deep_merge(DEFAULTS, remote)
        _CACHE.update(at=now, cfg=cfg)
        return cfg

    cfg = dict(DEFAULTS)
    try:
        if os.path.exists(LOCAL_CONFIG_PATH):
            with open(LOCAL_CONFIG_PATH, encoding="utf-8") as f:
                local = json.load(f)
            if isinstance(local, dict):
                cfg = _deep_merge(DEFAULTS, local)
    except Exception:
        pass
    _CACHE.update(at=now, cfg=cfg)
    return cfg


def get_pricing_config():
    return get_pipeline_config().get("pricing", DEFAULTS["pricing"])


def get_profile_config():
    return get_pipeline_config().get("profile", DEFAULTS["profile"])


def get_forecast_config():
    return get_pipeline_config().get("forecast", DEFAULTS["forecast"])
