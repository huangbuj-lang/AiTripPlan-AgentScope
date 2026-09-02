#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""增量修正 /app/revise 耗时基准（对照改造记录 78s：影响分析1s→定向重查50s→局部重聚合10s→校验审查17s）"""
import http.cookiejar, json, re, sys, time, urllib.parse, urllib.request

BASE = "http://localhost:8081"
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def post(path, data=None):
    req = urllib.request.Request(BASE + path, data=data or b"", method="POST")
    return opener.open(req, timeout=30).read().decode("utf-8", "replace")

def get(path):
    return opener.open(BASE + path, timeout=30).read().decode("utf-8", "replace")

user = "bench_%d" % int(time.time())
post("/api/register?username=%s&password=123456" % user)
print("login:", post("/api/login?username=%s&password=123456" % user)[:60])

prompt = urllib.parse.quote("帮我制定杭州2日游计划，包含景点、酒店、美食，预算3000元")
t0 = time.time()
print("生成:", post("/app?prompt=" + prompt)[:40])
for i in range(120):
    time.sleep(3)
    r = get("/result")
    if "还在执行中" not in r:
        break
gen = time.time() - t0
print("生成耗时: %.0fs 结果%d字符" % (gen, len(r)))

instr = urllib.parse.quote("酒店换成更便宜的经济型，第二天行程安排轻松一点")
t1 = time.time()
print("修订:", post("/app/revise?instruction=" + instr)[:40])
for i in range(120):
    time.sleep(3)
    r2 = get("/result")
    if "还在执行中" not in r2:
        break
rev = time.time() - t1
print("修订耗时: %.0fs 结果%d字符" % (rev, len(r2)))

ev = get("/events")
for m in re.finditer(r'"message":"([^"]*定向重查[^"]*)"|"message":"([^"]*受影响域[^"]*)"', ev):
    print("事件:", (m.group(1) or m.group(2))[:120])
print("BENCH: generate=%.0fs revise=%.0fs" % (gen, rev))
