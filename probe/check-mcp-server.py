#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
MCP server 独立自检 —— 不经过任何 AI 框架，直接按 MCP 协议说话。

为什么要先做这一步？
    客户端连不上 MCP server 时，锅可能在两边：server 本身没起来，
    或者只是某个框架的客户端配置写错了。用裸 HTTP 直接把 JSON-RPC
    发过去，就能把这两种情况彻底分开 ——
    **这一步通了，server 就是对的，剩下的一定在客户端侧。**

它做的事，正好就是一次最小的 MCP 握手：
    1. initialize              协商协议版本、交换能力
    2. notifications/initialized  告诉 server「我准备好了」（通知，无响应）
    3. tools/list              问它到底有哪些工具
    4. tools/call              真的调一个工具，看返回值
    5. tools/call（第二个）     换一个工具，证明不是「只有一条路能通」
    6. 协议版本协商矩阵         逐个版本发 initialize + 试 server/discover，
                              看清「谁将就谁」——版本回显还是硬推、认不认现代协议

用法：
    python check-mcp-server.py
    python check-mcp-server.py --url http://localhost:8099/mcp
"""

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

# MCP 客户端必须同时接受这两种 content-type，否则服务端会按规范回 406
ACCEPT = "application/json, text/event-stream"

# 自检目标如果是本机，就不该被 http_proxy / HTTP_PROXY 劫持。
# 这不是理论问题：某些开发/沙箱环境会给 127.0.0.1 也套一层代理，
# 结果 curl/urllib 拿到的是代理的 502，而不是 server 的真实响应 —— 排查时会
# 误判成「server 没起来」。这里对本机地址显式清空代理，让结论只反映 server 本身。
_LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}


def disable_proxy_for_local(url: str) -> None:
    host = urllib.parse.urlparse(url).hostname or ""
    if host in _LOCAL_HOSTS:
        urllib.request.install_opener(
            urllib.request.build_opener(urllib.request.ProxyHandler({}))
        )


def parse_body(raw: str):
    """把响应体解析成 JSON。

    Streamable HTTP 的响应有两种形态：
      - Content-Type: application/json        → 直接就是 JSON
      - Content-Type: text/event-stream       → SSE，真正的载荷在 data: 行里
    后者是常态（MCP 用 SSE 承载单次响应），所以必须解这一层。
    """
    raw = raw.strip()
    if not raw:
        return None
    if raw.startswith("{") or raw.startswith("["):
        return json.loads(raw)
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            payload = line[5:].strip()
            if payload:
                return json.loads(payload)
    return None


class McpProbe:

    def __init__(self, url: str, timeout: int = 15):
        self.url = url
        self.timeout = timeout
        self.session_id = None
        self._id = 0

    def next_id(self) -> int:
        self._id += 1
        return self._id

    def post(self, body: dict):
        data = json.dumps(body).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": ACCEPT}
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        req = urllib.request.Request(self.url, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                sid = resp.headers.get("Mcp-Session-Id")
                if sid:
                    self.session_id = sid
                return resp.status, resp.headers.get("Content-Type", ""), resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            sid = e.headers.get("Mcp-Session-Id") if e.headers else None
            if sid:
                self.session_id = sid
            return e.code, (e.headers.get("Content-Type", "") if e.headers else ""), e.read().decode("utf-8", "replace")
        except TimeoutError:
            # 服务端收到请求后既不回结果、也不报错，把连接挂起（Streamable HTTP 会按 SSE 流等待）。
            # 这不是「网络抖动」，而是一种明确的「该请求不被应答」的表现，必须显式区分出来。
            return 0, "", "<timeout：服务端在超时时间内未返回响应体>"

    def rpc(self, method: str, params=None):
        body = {"jsonrpc": "2.0", "id": self.next_id(), "method": method}
        if params is not None:
            body["params"] = params
        status, ctype, raw = self.post(body)
        return status, ctype, parse_body(raw), raw

    def notify(self, method: str, params=None):
        body = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            body["params"] = params
        return self.post(body)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8099/mcp", help="MCP endpoint（Streamable HTTP）")
    args = ap.parse_args()

    disable_proxy_for_local(args.url)

    p = McpProbe(args.url)
    failures = []

    print("=" * 68)
    print(f"MCP server 裸协议自检  ->  {args.url}")
    print("=" * 68)

    # ── 1. initialize ────────────────────────────────────────────────
    status, ctype, body, raw = p.rpc("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "ai4j-probe", "version": "1.0"},
    })
    print(f"\n[1] initialize        -> HTTP {status}  ({ctype.split(';')[0]})")
    if status != 200 or not body or "result" not in body:
        failures.append(f"initialize 失败：HTTP {status} / {raw[:200]}")
        print("    原始响应（前 300 字）：", raw[:300])
    else:
        r = body["result"]
        print(f"    协议版本 : {r.get('protocolVersion')}")
        info = r.get("serverInfo", {})
        print(f"    服务标识 : {info.get('name')} v{info.get('version')}")
        caps = r.get("capabilities", {})
        print(f"    声明的能力: {sorted(caps.keys())}")
        if p.session_id:
            print(f"    会话 ID  : {p.session_id[:16]}...")
        if "tools" not in caps:
            failures.append("server 没有声明 tools 能力")

    # ── 2. notifications/initialized ─────────────────────────────────
    st, _, _ = p.notify("notifications/initialized")
    print(f"\n[2] initialized 通知   -> HTTP {st}")
    if st not in (200, 202, 204):
        failures.append(f"initialized 通知返回了非预期状态 {st}")

    # ── 3. tools/list ────────────────────────────────────────────────
    status, _, body, raw = p.rpc("tools/list", {})
    tools = []
    print(f"\n[3] tools/list        -> HTTP {status}")
    if status != 200 or not body or "result" not in body:
        failures.append(f"tools/list 失败：HTTP {status} / {raw[:200]}")
    else:
        tools = body["result"].get("tools", [])
        print(f"    共 {len(tools)} 个工具：")
        for t in tools:
            desc = (t.get("description") or "").replace("\n", " ")
            print(f"      - {t['name']:<24} {desc[:52]}")
        names = {t["name"] for t in tools}
        expect = {"list_skills", "get_skill", "explain_effect_target", "validate_condition"}
        missing = expect - names
        if missing:
            failures.append(f"缺少预期工具：{sorted(missing)}")

    # ── 4. tools/call：真调一个 ──────────────────────────────────────
    print("\n[4] tools/call get_skill(skillId=9001)")
    status, _, body, raw = p.rpc("tools/call", {"name": "get_skill", "arguments": {"skillId": 9001}})
    if status != 200 or not body or "result" not in body:
        failures.append(f"tools/call 失败：HTTP {status} / {raw[:200]}")
        print(f"    HTTP {status}  {raw[:200]}")
    else:
        result = body["result"]
        texts = [c.get("text", "") for c in result.get("content", []) if c.get("type") == "text"]
        text = "\n".join(texts)
        print("    返回值：")
        for line in text.splitlines():
            print("      | " + line)
        if "9001" not in text or "龙血·磐石" not in text:
            failures.append("tools/call 的返回值里没有锚点技能信息")
        if result.get("isError"):
            failures.append("tools/call 返回了 isError=true")

    # ── 5. 另一个工具，验证不是只有一条路能通 ────────────────────────
    print("\n[5] tools/call validate_condition(\"HP_PERCENT(SELF) < 50\")")
    status, _, body, raw = p.rpc("tools/call", {
        "name": "validate_condition",
        "arguments": {"expression": "HP_PERCENT(SELF) < 50"},
    })
    if status == 200 and body and "result" in body:
        for c in body["result"].get("content", []):
            if c.get("type") == "text":
                for line in c.get("text", "").splitlines():
                    print("      | " + line)
    else:
        failures.append(f"validate_condition 失败：HTTP {status}")

    # ── 6. 协议版本协商矩阵 ──────────────────────────────────────────
    # 这一步回答的是「到底是客户端将就 server，还是 server 将就客户端」。
    # 客户端连不上的常见症状只是「工具列表为空」，看不出是哪一环的版本没对上；
    # 把不同 protocolVersion 逐个发过去、把 server 回的版本记下来，
    # 就能一次看清三件事（且可复跑）：
    #   ① server 接受哪些版本；② 它是回显客户端请求，还是硬推自己的版本；
    #   ③ 现代协议的 server/discover 它认不认。
    print("\n[6] 协议版本协商矩阵（每个版本开一个新会话）")
    matrix = [
        "2026-07-28",   # 现代协议：LangChain4j 会先用 server/discover 探它
        "2025-11-25",   # 两个框架客户端实际请求的版本
        "2025-06-18",   # 本项目裸协议 / 浏览器页请求的版本
        "2025-03-26",
        "2024-11-05",
        "1999-01-01",   # 明显不存在，看 server 对未知版本的态度
    ]
    for ver in matrix:
        q = McpProbe(args.url)
        st, _, b, raw = q.rpc("initialize", {
            "protocolVersion": ver,
            "capabilities": {},
            "clientInfo": {"name": "ai4j-probe-matrix", "version": "1.0"},
        })
        got = b["result"].get("protocolVersion") if (b and "result" in b) else None
        err = (b or {}).get("error") if b else None
        if got:
            tag = "回显（= 请求值）" if got == ver else "改推自己的版本"
        elif err:
            tag = f"拒绝：code={err.get('code')} {err.get('message')}"
        else:
            tag = f"HTTP {st} / 无解析结果"
        print(f"    请求 {ver:<12} -> 服务端 {str(got):<12} {tag}")

    # server/discover：现代协议的探测方法。LangChain4j 正是先试它（此时还没有会话）、
    # 失败才回落 initialize。这里分两步，把「只是缺会话」和「真不支持」分开 ——
    # 否则第一次响应里的 "Session ID missing" 会让人误以为「补个会话就好了」。
    print("\n[6b] server/discover（现代协议的探测方法）")
    q = McpProbe(args.url)
    st, _, b, raw = q.rpc("server/discover", {})
    print(f"    ① 无会话（= 客户端首次探测的真实处境）-> HTTP {st}")
    print("       " + (json.dumps(b, ensure_ascii=False)[:160] if b else raw[:160]))

    q2 = McpProbe(args.url)   # 先握手拿到会话，再带着会话 id 发一次
    q2.rpc("initialize", {
        "protocolVersion": "2025-11-25",
        "capabilities": {},
        "clientInfo": {"name": "ai4j-probe-discover", "version": "1.0"},
    })
    st2, _, b2, raw2 = q2.rpc("server/discover", {})
    print(f"    ② 带会话（排除「只是缺会话」）      -> HTTP {st2}")
    if st2 == 0:
        print("       超时：服务端既不回结果、也不报错，请求被挂起（按 SSE 流等待）。")
    else:
        print("       " + (json.dumps(b2, ensure_ascii=False)[:160] if b2 else raw2[:160]))
    if st2 == 0 or (b2 and "error" in b2 and b2["error"].get("code") == -32601):
        print("    → 结论：现代协议的 server/discover 本 server 不实现 ——")
        print("            无会话时被传输层以 -32601 挡下；带会话时请求被挂起、不应答。")
        print("            所以客户端只能回落 initialize（实测两边都回落用 2025-11-25）。")

    # ── 结论 ─────────────────────────────────────────────────────────
    print("\n" + "=" * 68)
    if failures:
        print(f"结论：{len(failures)} 项未通过")
        for f in failures:
            print("  [X] " + f)
        return 1
    print("结论：全部通过 —— MCP server 本身工作正常。")
    print("      客户端若连不上，问题一定在客户端侧（传输方式/URL/协议版本）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
