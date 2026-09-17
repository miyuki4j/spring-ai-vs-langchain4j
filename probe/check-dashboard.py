#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对照台自检：确认两个后端的接口形状和 CORS 放行，都符合 dashboard/ 页面的预期。

页面跑不起来，绝大多数时候不是 JS 的问题，而是这三件事之一：
  1. 后端没起（或端口被 SERVER_PORT 环境变量顶掉了）
  2. CORS 没放行页面来源 —— 浏览器连预检都不让过，fetch 直接 reject
  3. 接口返回的字段名和页面预期对不上（比如 think 端点少了 tokens）

这个脚本把这三点一次性验掉，而且不依赖浏览器。必须先启动两个后端。

用法：
    python probe/check-dashboard.py
    python probe/check-dashboard.py --origin http://localhost:8090
    python probe/check-dashboard.py --p1 http://localhost:8081 --p2 http://localhost:8082
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

TIMEOUT = 120
PROMPT = "13 的平方是多少？只回答数字。"

OK = "[OK]"
BAD = "[X]"

failures = []


def check(name, cond, detail="", fail_detail=""):
    # detail 只在通过时展示（通常是"实际拿到了什么"这类信息）；
    # fail_detail 只在失败时展示（是诊断，不该在通过时冒充结论）。
    # 这两者一度共用同一个参数，于是"对照组正确答不出"时反而打印了
    # "对照组也答对了 —— 实验作废" —— 通过的报告里写着结论相反的话。
    text = detail if cond else (fail_detail or detail)
    tag = OK if cond else BAD
    print(f"  {tag} {name}" + (f"  —— {text}" if text else ""))
    if not cond:
        failures.append(name)
    return cond


def request(url, origin=None, stream=False):
    """返回 (status, headers, body_text 或 stream 对象)。"""
    req = urllib.request.Request(url)
    if origin:
        req.add_header("Origin", origin)
    req.add_header("Accept", "text/event-stream" if stream else "application/json")
    try:
        resp = urllib.request.urlopen(req, timeout=TIMEOUT)
        if stream:
            return resp.status, dict(resp.headers), resp
        body = resp.read().decode("utf-8", errors="replace")
        return resp.status, dict(resp.headers), body
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        return None, {}, str(e)


def hdr(headers, key):
    """响应头大小写不敏感地取值。"""
    for k, v in headers.items():
        if k.lower() == key.lower():
            return v
    return None


def check_backend(label, base, origin, with_llm_call=True):
    print(f"\n{'=' * 66}\n{label}  {base}\n{'=' * 66}")

    # ---------- 1. health：探活 + CORS ----------
    status, headers, body = request(f"{base}/api/health", origin=origin)
    if status is None:
        check("能连上服务", False, f"连不上：{body}")
        check("提示", False, "服务没起？或者 bash 里 JAVA_HOME 没切到 17？")
        return

    check("GET /api/health 返回 200", status == 200, f"实际 {status}")

    acao = hdr(headers, "Access-Control-Allow-Origin")
    check(
        f"CORS 放行了 {origin}",
        acao is not None and acao != "",
        f"Access-Control-Allow-Origin = {acao!r}"
        + ("" if acao else "  ← 页面会在这里失败，检查 WebConfig 是否生效"),
    )

    try:
        cfg = json.loads(body)
        for field in ("app", "framework", "port", "model"):
            check(f"health 含字段 {field}", field in cfg, f"{field}={cfg.get(field)!r}")
        print(f"       生效配置：{json.dumps(cfg, ensure_ascii=False)}")
    except Exception as e:  # noqa: BLE001
        check("health 是合法 JSON", False, str(e))

    if not with_llm_call:
        return

    # ---------- 2. think：字段形状，尤其是 tokens ----------
    status, headers, body = request(
        f"{base}/api/chat/think?message={urllib.parse.quote(PROMPT)}", origin=origin
    )
    if check("GET /api/chat/think 返回 200", status == 200, f"实际 {status}"):
        try:
            data = json.loads(body)
            for field in ("answer", "reasoningContent", "reasoningChars"):
                check(f"think 含字段 {field}", field in data)
            has_tokens = isinstance(data.get("tokens"), dict)
            check(
                "think 含 tokens（页面的 token 对照依赖它）",
                has_tokens,
                json.dumps(data.get("tokens"), ensure_ascii=False) if has_tokens else "缺失",
            )
            if has_tokens:
                for field in ("prompt", "completion", "total"):
                    check(f"  tokens.{field}", field in data["tokens"])
            check(
                "reasoningChars > 0（拿到了思考内容）",
                isinstance(data.get("reasoningChars"), int) and data["reasoningChars"] > 0,
                f"reasoningChars={data.get('reasoningChars')}",
            )
            print(f"       回答：{str(data.get('answer'))[:60]!r}")
        except Exception as e:  # noqa: BLE001
            check("think 返回合法 JSON", False, f"{e}  原始前 120 字：{body[:120]!r}")

    # ---------- 3. stream：SSE 帧格式 + 流能否正常结束 ----------
    # 注意必须把流读完：只读前几帧的话，"服务端不关流"这种问题根本测不出来，
    # 而它会让页面上的流式那侧永远停在打字光标、时间轴也永远画不出来。
    import time  # noqa: PLC0415

    status, headers, body = request(
        f"{base}/api/chat/stream?message={urllib.parse.quote('从 1 数到 5')}",
        origin=origin,
        stream=True,
    )
    if check("GET /api/chat/stream 返回 200", status == 200, f"实际 {status}"):
        ctype = hdr(headers, "Content-Type") or ""
        check("Content-Type 是 text/event-stream", "text/event-stream" in ctype, ctype)

        frames = []
        first_at = None
        ended_normally = False
        t0 = time.time()
        try:
            for raw in body:
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                if line.startswith("data:"):
                    if first_at is None:
                        first_at = time.time() - t0
                    frames.append(line[5:].strip())
            ended_normally = True
        except Exception as e:  # noqa: BLE001
            check("流能被完整读取", False, f"读流时报错：{e}")
        finally:
            try:
                body.close()
            except Exception:  # noqa: BLE001
                pass

        total = time.time() - t0
        check("至少收到 5 个 data 帧", len(frames) >= 5, f"收到 {len(frames)} 个")
        check(
            "流以 EOF 正常结束（不是被超时掐断）",
            ended_normally,
            f"首帧 {first_at:.2f}s，读完全程 {total:.2f}s"
            + ("" if ended_normally else "  ← 服务端没关流，页面会一直等下去"),
        )
        if frames:
            print(f"       流内容：{''.join(frames)[:70]!r}")


MCP_EXPECTED = {"list_skills", "get_skill", "explain_effect_target", "validate_condition"}

MCP_QUESTION = "技能 9001 叫什么名字？它作用于什么目标类型？"


def mcp_post(url, body, sid=None, origin=None):
    """按 MCP 的 Streamable HTTP 说一次 JSON-RPC，返回 (status, headers, parsed, raw)。

    两个容易写错的点这里都处理了：
      · Accept 必须**同时**声明 application/json 和 text/event-stream，少一个可能被回 406；
      · 响应体常常不是 JSON 而是 SSE —— 真正的载荷在 data: 行里。
    """
    data = json.dumps(body).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if sid:
        headers["Mcp-Session-Id"] = sid
    if origin:
        headers["Origin"] = origin

    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        resp = urllib.request.urlopen(req, timeout=30)
        status, hdrs, text = resp.status, dict(resp.headers), resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        status, hdrs, text = e.code, dict(e.headers), e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, {}, None, str(e)

    raw = (text or "").strip()
    parsed = None
    try:
        if raw.startswith("{"):
            parsed = json.loads(raw)
        else:
            for line in raw.splitlines():
                if line.strip().startswith("data:"):
                    parsed = json.loads(line.strip()[5:].strip())
                    break
    except Exception:  # noqa: BLE001
        parsed = None
    return status, hdrs, parsed, raw


def check_mcp_server(base_mcp, origin):
    """MCP server（第三个进程）自检 —— 重点是**浏览器视角**才会暴露的两件事。

    命令行裸协议（probe/check-mcp-server.py）能验 server 本身对不对，但验不出
    「浏览器能不能连」。这里是页面的代言人：先过 CORS 预检，再握手。
    """
    print(f"\n{'=' * 66}\nMCP server  {base_mcp}   （页面直连的第三方视角）\n{'=' * 66}")
    url = base_mcp.rstrip("/") + "/mcp"

    # ---------- 1. CORS 预检 ----------
    # 页面发的是 POST + Content-Type: application/json + 自定义头 Mcp-Session-Id，
    # 这三样随便哪个都会让浏览器先发一个 OPTIONS。这一关不过，页面第三列永远是空的。
    req = urllib.request.Request(url, method="OPTIONS")
    req.add_header("Origin", origin)
    req.add_header("Access-Control-Request-Method", "POST")
    req.add_header("Access-Control-Request-Headers", "content-type, mcp-session-id")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            s, h = resp.status, dict(resp.headers)
    except urllib.error.HTTPError as e:
        s, h = e.code, dict(e.headers)
    except Exception as e:  # noqa: BLE001
        print(f"  {BAD} 连不上 MCP server：{e}")
        failures.append("MCP server 连不上")
        return None

    check("OPTIONS /mcp 预检返回 2xx", 200 <= s < 300, f"实际 {s}")
    acao = hdr(h, "Access-Control-Allow-Origin")
    check("预检放行了页面来源", acao not in (None, ""), f"Access-Control-Allow-Origin = {acao!r}")

    expose = (hdr(h, "Access-Control-Expose-Headers") or "").lower()
    check(
        "预检暴露了 Mcp-Session-Id（否则页面读不到会话 id）",
        "mcp-session-id" in expose,
        f"Access-Control-Expose-Headers = {expose!r}"
        + ("" if "mcp-session-id" in expose
           else "  ← 页面会表现为「initialize 成功、后续全部 400」，很难查"),
    )

    # ---------- 2. 裸协议握手 + tools/list ----------
    status, headers, body, raw = mcp_post(url, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "ai4j-check-dashboard", "version": "1.0"}},
    }, origin=origin)
    if not check("initialize 返回 200", status == 200, f"实际 {status}  {raw[:120]!r}"):
        return None

    sid = hdr(headers, "Mcp-Session-Id")
    result = (body or {}).get("result") or {}
    print(f"       会话 id={sid!r}  协商到的协议版本={result.get('protocolVersion')!r}")
    check("服务端下发了 Mcp-Session-Id", bool(sid),
          "浏览器侧靠它维持会话",
          "没下发会话 id —— 跨域时 JS 会读不到，表现为「initialize 成功、后续全 400」")

    mcp_post(url, {"jsonrpc": "2.0", "method": "notifications/initialized"}, sid=sid, origin=origin)
    status, headers, body, raw = mcp_post(
        url, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
        sid=sid, origin=origin)
    if not check("tools/list 返回 200", status == 200, f"实际 {status}  {raw[:120]!r}"):
        return None

    names = sorted(t["name"] for t in ((body or {}).get("result") or {}).get("tools", []))
    check("tools/list 报出全部 4 个预期工具", set(names) == MCP_EXPECTED, f"实际 {names}")
    print(f"       远端清单：{names}")
    return names


def check_mcp_endpoints(label, base, origin, with_llm_call=True):
    """两个后端的 MCP 端点：字段形状必须和页面预期一致。"""
    print(f"\n{'-' * 66}\n{label}  MCP 端点\n{'-' * 66}")

    status, headers, body = request(f"{base}/api/chat/mcp/tools", origin=origin)
    if not check(f"{label} /api/chat/mcp/tools 返回 200", status == 200, f"实际 {status}"):
        return None

    try:
        tools = json.loads(body)
    except Exception as e:  # noqa: BLE001
        check("/tools 返回合法 JSON", False, f"{e}  {body[:120]!r}")
        return None

    check("远端工具清单非空", len(tools) > 0,
          f"{len(tools)} 个：{tools}" + ("" if tools else "  ← 0 个说明它没连上 MCP server"))
    check("工具名与 MCP server 一致", set(tools) == MCP_EXPECTED,
          "逐项一致",
          f"缺 {sorted(MCP_EXPECTED - set(tools))}  多 {sorted(set(tools) - MCP_EXPECTED)}")

    if not with_llm_call:
        return tools

    q = urllib.parse.quote(MCP_QUESTION)
    for with_mcp in (True, False):
        status, headers, body = request(
            f"{base}/api/chat/mcp?message={q}&withMcp={with_mcp}", origin=origin)
        if not check(f"{label} withMcp={with_mcp} 返回 200", status == 200, f"实际 {status}"):
            continue
        try:
            d = json.loads(body)
        except Exception as e:  # noqa: BLE001
            check(f"  withMcp={with_mcp} 返回合法 JSON", False, str(e))
            continue

        missing = [f for f in ("answer", "withMcp", "toolCount", "toolNames", "elapsedMs", "tokens")
                   if f not in d]
        check(f"  withMcp={with_mcp} 字段齐全（页面按这些字段渲染）", not missing,
              "字段全齐", f"缺 {missing}")

        # 把这次调用的数值打出来：一是肉眼能核，二是演示模式的合成数据
        # （dashboard/app.js 里的 MCP_DEMO）要按这里回填，免得演示数字和真实结果对不上。
        print(f"       {d.get('toolCount')} 工具 · {d.get('elapsedMs')}ms · "
              f"tokens={json.dumps(d.get('tokens'), ensure_ascii=False)}")

        ans = d.get("answer") or ""
        hit_name = "磐石" in ans
        hit_target = ("REVEALED_AREA" in ans) or ("已揭示" in ans)
        if with_mcp:
            check("  实验组答出锚点", hit_name and hit_target,
                  f"技能名命中={hit_name} 目标命中={hit_target}")
        else:
            check("  对照组答不出锚点（对照成立）", not (hit_name or hit_target),
                  f"技能名命中={hit_name} 目标命中={hit_target}",
                  "对照组也答对了 —— 信息泄漏，或记忆串了槽，锚点无效、实验作废")
    return tools


def main():
    ap = argparse.ArgumentParser(description="对照台接口与 CORS 自检")
    ap.add_argument("--p1", default="http://localhost:8081")
    ap.add_argument("--p2", default="http://localhost:8082")
    ap.add_argument("--mcp", default="http://localhost:8099",
                    help="MCP server 地址（页面的第三个进程）")
    ap.add_argument("--origin", default="http://localhost:8090",
                    help="模拟页面来源，用于验证 CORS 放行")
    ap.add_argument("--no-llm", action="store_true",
                    help="只验 health/CORS/工具清单，不调模型（不花钱）")
    ap.add_argument("--no-mcp", action="store_true",
                    help="完全跳过 MCP 相关检查（只起了两个后端时用）")
    args = ap.parse_args()

    import urllib.parse  # noqa: PLC0415  (放这里避免和上面的 urllib.request 混一起)

    print(f"模拟页面来源：{args.origin}")

    # MCP server 先验：它是第三个进程，两个后端的工具清单都从它来 ——
    # 它不对，两边的 /tools 一定是空的，先查它能把范围砍掉一半。
    if not args.no_mcp:
        check_mcp_server(args.mcp, args.origin)

    check_backend("Spring AI  (spring-ai-demo)", args.p1, args.origin, not args.no_llm)
    check_backend("LangChain4j (langchain4j-demo)", args.p2, args.origin, not args.no_llm)

    if not args.no_mcp:
        check_mcp_endpoints("Spring AI  (spring-ai-demo)", args.p1, args.origin, not args.no_llm)
        check_mcp_endpoints("LangChain4j (langchain4j-demo)", args.p2, args.origin, not args.no_llm)

    print(f"\n{'=' * 66}")
    if failures:
        print(f"{BAD} 有 {len(failures)} 项未通过：")
        for f in failures:
            print(f"     - {f}")
        sys.exit(1)
    print(f"{OK} 全部通过，对照台页面可以正常取数。")


if __name__ == "__main__":
    main()
