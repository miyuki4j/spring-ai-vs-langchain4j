#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
MCP 端到端自检 —— 三个参与方都起来之后跑。

参与方：
    mcp-skill-server   :8099   工具提供方（不含大模型）
    spring-ai-demo     :8081   MCP 客户端 A（Spring AI）
    langchain4j-demo   :8082   MCP 客户端 B（LangChain4j）

三条断言，按「由弱到强」排：

  1) **清单一致**：用裸 MCP 协议直接问 server 拿到的 tools/list，
     和两个框架客户端各自报出来的工具清单，三者必须**完全相同**。
     —— 这条最便宜，却最能说明 MCP 的意义：三个进程、两套框架、一个协议，
        看到的是同一份能力清单。客户端甚至可以在编译期完全不知道这些工具存在。

  2) **静默失败检查**：两侧客户端的工具数都不能是 0。
     —— 前几课反复吃过静默失败的亏（TokenStream 忘了 start()、
        @AiService 返回 ChatResponse、记忆 advisor 被悄悄顶掉），
        这里把「0 个工具」当成硬失败，而不是当成一个正常结果。

  3) **锚点 + 对照**（最强的一条）：问一个**只有 MCP server 才知道**的技能。
     挂上 MCP 工具 → 必须答对；不挂 → 必须答不出。
     —— 只测「挂了工具答得对」是不够的：模型可能本来就知道，也可能瞎猜中了。
        必须同时有对照组，一真一假，才排除了这两种可能。
        （方法延续自记忆那一课：能被别的途径拿到的信息，不能当证据。）

用法：
    python check-mcp.py
    python check-mcp.py --p1 http://localhost:8081 --p2 http://localhost:8082
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

# ── 锚点：只在 mcp-skill-server 的 skills.json 里存在 ───────────────────
ANCHOR_ID = "9001"
ANCHOR_NAME = "磐石"          # 技能「龙血·磐石」，用核心词判定，容忍模型加前后缀
ANCHOR_TARGET = "REVEALED_AREA"
ANCHOR_TARGET_CN = "已揭示"    # 模型可能把枚举翻译成中文，也算命中

# 问题刻意同时问「名字」和「目标类型」，两个都答对才算真的查到了配置
QUESTION = "技能 9001 叫什么名字？它作用于什么目标类型？"

EXPECTED_TOOLS = {"list_skills", "get_skill", "explain_effect_target", "validate_condition"}

ACCEPT = "application/json, text/event-stream"


def http_json(url, timeout=120):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", "replace"))


def mcp_tools_via_raw_protocol(url):
    """不经过任何框架，直接按 MCP 协议问 server 要 tools/list。

    这一步的价值在于：如果它和两个框架客户端拿到的清单不一致，
    那问题就在客户端侧（或协议版本协商），而不是 server 没提供。
    """
    state = {"sid": None}

    def post(body):
        data = json.dumps(body).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": ACCEPT}
        if state["sid"]:
            headers["Mcp-Session-Id"] = state["sid"]
        req = urllib.request.Request(url, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=20) as resp:
            sid = resp.headers.get("Mcp-Session-Id")
            if sid:
                state["sid"] = sid
            return resp.read().decode("utf-8", "replace")

    def parse(raw):
        raw = raw.strip()
        if raw.startswith("{"):
            return json.loads(raw)
        for line in raw.splitlines():
            if line.strip().startswith("data:"):
                return json.loads(line.strip()[5:].strip())
        return None

    post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {},
        "clientInfo": {"name": "ai4j-mcp-check", "version": "1.0"}}})
    post({"jsonrpc": "2.0", "method": "notifications/initialized"})
    body = parse(post({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}))
    return sorted(t["name"] for t in body["result"]["tools"])


class Report:

    def __init__(self):
        self.fails = []
        self.notes = []

    def check(self, name, ok, detail="", fail_detail=""):
        # detail 只在通过时展示（通常是"实际拿到了什么"这类信息）；
        # fail_detail 只在失败时展示（是诊断，不该在通过时冒充结论）。
        # 这两者一度共用同一个参数，于是"对照组正确答不出"时反而打印了
        # "对照组居然答对了 —— 实验作废" —— 通过的报告里写着结论相反的话。
        text = detail if ok else (fail_detail or detail)
        print(f"  [{'OK' if ok else 'X '}] {name}" + (f"  ({text})" if text else ""))
        if not ok:
            self.fails.append(name)

    def note(self, text):
        print(f"       · {text}")


def hit(answer, *tokens):
    return any(t in (answer or "") for t in tokens)


def run_side(label, base, rep: Report):
    print(f"\n{'─' * 66}\n{label}   {base}\n{'─' * 66}")

    # ── 工具清单 ────────────────────────────────────────────────────
    tools = http_json(f"{base}/api/chat/mcp/tools")
    rep.check(f"{label} /tools 返回非空", len(tools) > 0, f"{len(tools)} 个：{tools}")
    missing = sorted(EXPECTED_TOOLS - set(tools))
    rep.check(f"{label} 工具清单含全部预期工具", not missing, "", f"缺 {missing}")

    # ── 实验组：挂 MCP 工具 ─────────────────────────────────────────
    on = http_json(f"{base}/api/chat/mcp?withMcp=true&message=" + urllib.parse.quote(QUESTION))
    print(f"\n  [实验组] withMcp=true   {on['elapsedMs']}ms  toolCount={on['toolCount']}")
    print(f"           回答：{on['answer'][:150]!r}")
    rep.check(f"{label} 挂 MCP 后答出锚点技能名",
              hit(on["answer"], ANCHOR_NAME),
              f"命中 {ANCHOR_NAME!r}",
              f"锚点 {ANCHOR_NAME!r} 未出现 —— 工具挂了却没被用上")
    rep.check(f"{label} 挂 MCP 后答出目标类型",
              hit(on["answer"], ANCHOR_TARGET, ANCHOR_TARGET_CN),
              f"命中 {ANCHOR_TARGET!r}",
              f"锚点 {ANCHOR_TARGET!r} 未出现 —— 配置只查到一半")

    # ── 对照组：不挂 MCP 工具 ───────────────────────────────────────
    off = http_json(f"{base}/api/chat/mcp?withMcp=false&message=" + urllib.parse.quote(QUESTION))
    print(f"\n  [对照组] withMcp=false  {off['elapsedMs']}ms  toolCount={off['toolCount']}")
    print(f"           回答：{off['answer'][:150]!r}")
    rep.check(f"{label} 不挂 MCP 时答不出锚点（对照成立）",
              not hit(off["answer"], ANCHOR_NAME, ANCHOR_TARGET, ANCHOR_TARGET_CN),
              "对照组答不出，锚点封闭",
              "对照组居然答对了 —— 说明这个信息模型本来就知道（或记忆串了槽），锚点无效，实验作废")

    return {"tools": tools, "withMcp": on, "withoutMcp": off}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--p1", default="http://localhost:8081")
    ap.add_argument("--p2", default="http://localhost:8082")
    ap.add_argument("--mcp", default="http://localhost:8099/mcp")
    args = ap.parse_args()

    import urllib.parse  # noqa: F401  (供内部 quote 使用)

    rep = Report()
    print("=" * 66)
    print("MCP 端到端自检")
    print("=" * 66)
    print(f"\n问题：{QUESTION}")
    print(f"锚点：技能 {ANCHOR_ID}「龙血·{ANCHOR_NAME}」/ 目标类型 {ANCHOR_TARGET}")
    print("      —— 这三样只存在于 mcp-skill-server 的 skills.json 里，")
    print("         两个后端进程的代码里一个字都没有。")

    # ── 1. server 侧（裸协议）────────────────────────────────────────
    print(f"\n{'─' * 66}\n[mcp-skill-server] {args.mcp}  （裸协议直问）\n{'─' * 66}")
    try:
        server_tools = mcp_tools_via_raw_protocol(args.mcp)
        print(f"  tools/list -> {server_tools}")
        rep.check("server 通过裸 MCP 协议报出 4 个工具",
                  set(server_tools) == EXPECTED_TOOLS,
                  "4 个工具，与预期一致",
                  f"实际 {sorted(set(server_tools))}，与预期不符")
    except Exception as e:  # noqa: BLE001
        print(f"  裸协议握手失败：{e}")
        rep.fails.append("server 裸协议握手失败")
        server_tools = None

    # ── 2. 两个客户端 ───────────────────────────────────────────────
    p1 = run_side("P1 Spring AI", args.p1, rep)
    p2 = run_side("P2 LangChain4j", args.p2, rep)

    # ── 3. 三方清单交叉比对（本节最想证明的事）──────────────────────
    print(f"\n{'─' * 66}\n三方工具清单一致性   ← MCP 解耦的硬证据\n{'─' * 66}")
    if server_tools is not None:
        same = (p1["tools"] == p2["tools"] == server_tools)
        print(f"  server（裸协议）: {server_tools}")
        print(f"  P1 Spring AI    : {p1['tools']}")
        print(f"  P2 LangChain4j  : {p2['tools']}")
        rep.check("三个进程看到的工具清单完全一致", same,
                  "三方逐项相同",
                  "至少两方清单不同 —— 问题在客户端侧或协议版本协商")
        rep.check("这份清单谁也不在编译期知道（4 个工具都来自远端）", len(server_tools) == 4,
                  "4 个工具全部来自远端",
                  f"远端只报了 {len(server_tools)} 个")

    # ── 结论 ────────────────────────────────────────────────────────
    print("\n" + "=" * 66)
    if rep.fails:
        print(f"结论：{len(rep.fails)} 项未通过")
        for f in rep.fails:
            print("  [X] " + f)
        return 1
    print("结论：全部通过。")
    print("      · 同一个 MCP server，两个不同框架的客户端，工具清单一致；")
    print("      · 挂上工具才答得出、不挂就答不出 —— 锚点对照封闭，MCP 调用链成立。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
