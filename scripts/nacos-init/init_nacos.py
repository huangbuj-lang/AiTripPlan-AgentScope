#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nacos AI 注册中心一键初始化（幂等，可重复执行）：
  提示词 20 条（scripts/nacos-init/prompts.json，与代码默认一致）
  + 技能 2 条（route-making-skill / trip-planner-skill，SKILL.md 自动打包上传）
  + 代理 2 条（routemakingagent / tripplanneragent）
用法:
  python3 scripts/nacos-init/init_nacos.py                     # 默认 localhost:8848, nacos/nacos
  python3 scripts/nacos-init/init_nacos.py --addr 1.2.3.4:8848 --user nacos --pass nacos
素材目录: scripts/nacos-init/
  prompts.json                    20 条提示词内容（key=aitripplan.xxx 的后缀）
  skills/<skill-name>/SKILL.md    技能源文件（zip 内要求 name 全小写连字符）
"""
import argparse, io, json, os, sys, urllib.parse, urllib.request, uuid, zipfile

BASE = os.path.dirname(os.path.abspath(__file__))

def api(addr, path, form=None, method="POST", token=None, raw=None, headers=None):
    url = "http://%s%s" % (addr, path)
    h = dict(headers or {})
    if token:
        h["accessToken"] = token
    # 本机 Nacos 开了 serverIdentity 鉴权(非用户名密码): 管理API需带身份头
    h.setdefault("aitripplan-local-serverIdentity", "aitripplan-local-2026")
    if raw is not None:
        data = raw
    else:
        data = urllib.parse.urlencode(form or {}).encode()
        h.setdefault("Content-Type", "application/x-www-form-urlencoded")
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"code": -1, "message": "HTTP %s: %s" % (method, e)}


def list_prompt_keys(addr, token):
    """返回现有 promptKey 集合（一次拉 200）"""
    r = api(addr, "/nacos/v3/admin/ai/prompt/list?pageNo=1&pageSize=200&namespaceId=public",
            method="GET", token=token)
    items = (r.get("data") or {}).get("pageItems") or []
    return {it.get("promptKey") for it in items}


def ensure_prompt(addr, key, template, token, existing=None):
    """幂等创建/更新提示词到 1.0.0：已有条目直接跳过（不重复创建、不覆盖），缺失则建"""
    if existing is not None and key in existing:
        return True, "exists"
    r1 = api(addr, "/nacos/v3/admin/ai/prompt/draft",
             {"promptKey": key, "targetVersion": "1.0.0", "template": template, "namespaceId": "public"}, token=token)
    if r1.get("code") != 0 and "exist" not in str(r1.get("message", "")).lower():
        return False, r1
    api(addr, "/nacos/v3/admin/ai/prompt/draft",
        {"promptKey": key, "template": template, "namespaceId": "public"}, method="PUT", token=token)
    api(addr, "/nacos/v3/admin/ai/prompt/submit", {"promptKey": key, "namespaceId": "public"}, token=token)
    r4 = api(addr, "/nacos/v3/admin/ai/prompt/publish",
             {"promptKey": key, "version": "1.0.0", "namespaceId": "public"}, token=token)
    return r4.get("code") == 0, r4


def ensure_skill(addr, name, token):
    """把 skills/<name>/SKILL.md 打 zip 上传→发布（幂等）"""
    md_path = os.path.join(BASE, "skills", name, "SKILL.md")
    if not os.path.exists(md_path):
        return False, "missing SKILL.md"
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("%s/SKILL.md" % name, open(md_path, encoding="utf-8").read())
    boundary = uuid.uuid4().hex
    body = io.BytesIO()
    for seg in ["--%s\r\n" % boundary,
                'Content-Disposition: form-data; name="file"; filename="%s.zip"\r\n' % name,
                "Content-Type: application/zip\r\n\r\n"]:
        body.write(seg.encode())
    body.write(buf.getvalue())
    for seg in ["\r\n--%s\r\n" % boundary,
                'Content-Disposition: form-data; name="namespaceId"\r\n\r\npublic\r\n',
                "--%s--\r\n" % boundary]:
        body.write(seg.encode())
    r1 = api(addr, "/nacos/v3/admin/ai/skills/upload", raw=body.getvalue(), token=token,
             headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary})
    if r1.get("code") != 0 and "exist" not in str(r1.get("message", "")).lower():
        return False, r1
    r2 = api(addr, "/nacos/v3/admin/ai/skills/submit",
             {"skillName": name, "namespaceId": "public"}, token=token)
    ver = r2.get("data", "0.0.1") if isinstance(r2, dict) else "0.0.1"
    r3 = api(addr, "/nacos/v3/admin/ai/skills/publish",
             {"skillName": name, "version": ver, "namespaceId": "public"}, token=token)
    return r3.get("code") == 0, r3


def ensure_agentspec(addr, name, desc, url, token, existing=None):
    if existing is not None and name in existing:
        return True, "exists"
    card = json.dumps({
        "name": name, "description": desc, "version": "1.0.0", "url": url,
        "protocolVersion": "0.3.0", "preferredTransport": "JSONRPC",
        "capabilities": {"streaming": True, "pushNotifications": False,
                         "stateTransitionHistory": False, "extendedAgentCard": False},
        "skills": [], "defaultInputModes": ["text"], "defaultOutputModes": ["text"],
        "registrationType": "SERVICE", "latestVersion": True}, ensure_ascii=False)
    api(addr, "/nacos/v3/admin/ai/agentspecs/draft",
        {"agentSpecName": name, "targetVersion": "1.0.0", "namespaceId": "public"}, token=token)
    r2 = api(addr, "/nacos/v3/admin/ai/agentspecs/draft",
             {"agentSpecName": name, "agentSpecCard": card, "namespaceId": "public"},
             method="PUT", token=token)
    api(addr, "/nacos/v3/admin/ai/agentspecs/submit",
        {"agentSpecName": name, "namespaceId": "public"}, token=token)
    r4 = api(addr, "/nacos/v3/admin/ai/agentspecs/publish",
             {"agentSpecName": name, "version": "1.0.0", "namespaceId": "public"}, token=token)
    return r2.get("code") == 0 and r4.get("code") == 0, (r2, r4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--addr", default="localhost:8848")
    ap.add_argument("--user", default="nacos")
    ap.add_argument("--pass", dest="pwd", default="nacos")
    args = ap.parse_args()

    # serverIdentity 鉴权模式下 v1/users/login 不存在(403)——身份头已在 api() 里统一携带,
    # token 为空即以 server identity 直接调用管理 API (list 是 GET, 不是 POST)
    login = api(args.addr, "/nacos/v3/admin/ai/prompt/list?pageNo=1&pageSize=1&namespaceId=public", method="GET")
    if login.get("code") == 0:
        token = None
        print("✅ serverIdentity 鉴权直连", args.addr)
    else:
        login = api(args.addr, "/nacos/v1/auth/users/login",
                    {"username": args.user, "password": args.pwd})
        token = login.get("accessToken")
        if not token:
            print("❌ Nacos 登录失败:", login.get("message", login))
            sys.exit(1)
        print("✅ 登录成功", args.addr)

    prompts = json.load(open(os.path.join(BASE, "prompts.json"), encoding="utf-8"))
    existing = list_prompt_keys(args.addr, token)
    ok = 0
    for k, v in prompts.items():
        good, _ = ensure_prompt(args.addr, "aitripplan." + k, v, token, existing=existing)
        if good:
            ok += 1
        else:
            print("  ⚠️", k)
    print("✅ 提示词 %d/%d（已存在自动跳过）" % (ok, len(prompts)))

    for name in ["route-making-skill", "trip-planner-skill"]:
        good, r = ensure_skill(args.addr, name, token)
        print(("✅ 技能 " if good else "⚠️ 技能 ") + name, "" if good else r)

    agents = {
        "routemakingagent": ("擅长处理自驾游路线制定（路线Agent 8082）", "http://localhost:8082"),
        "tripplanneragent": ("专业的行程规划Agent（行程Agent 8085）", "http://localhost:8085"),
    }
    spec_existing = {it.get("name") for it in
        (api(args.addr, "/nacos/v3/admin/ai/agentspecs/list?pageNo=1&pageSize=200&namespaceId=public",
             method="GET", token=token).get("data") or {}).get("pageItems") or []}
    for name, (desc, url) in agents.items():
        good, r = ensure_agentspec(args.addr, name, desc, url, token, existing=spec_existing)
        print(("✅ 代理 " if good else "⚠️ 代理 ") + name, "" if good else r)

    print("🎉 Nacos AI 注册中心初始化完成（幂等，可重复执行）")


if __name__ == "__main__":
    main()
