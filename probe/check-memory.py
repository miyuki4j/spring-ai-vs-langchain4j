#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对话记忆自检：验证两个框架的 /api/chat/memory 都真的"记住"了，而且记忆不是免费的。

这个脚本验四件事，每一件都对应一种"看起来跑通了、其实没生效"的失败：

  1. 【记忆生效】第 4 轮能复述出记忆锚点 LOONG-7749 —— 一个**工具产不出来**的值。
     为什么必须用锚点、不能用 3214 这类数字：那些数字模型在回忆轮可以自己再调一次
     工具查回来（实测真的发生了）。所以它们命中与否跟记忆没关系。

  2. 【无记忆对照】同一个问题、全新的 conversationId，必须连锚点都答不出来。
     只有 1 成立 + 2 失败，才排除了"模型蒙对"。

  3. 【成本受控测量】用「回忆轮 vs 对照轮」的 prompt 差量化记忆开销 ——
     这两次是**同一道题、同一套工具**，唯一变量就是历史消息条数，
     所以差值可以直接换算成「每条历史消息值多少 token」。

  4. 【窗口裁剪可观测】把窗口压到 2 条之后：
     - API 返回的 memoryFirstUser（记忆里最早那条 user 消息）必须不再是第 1 轮的问题；
     - 锚点必须复述不出来。
     两条一起看才叫"可观测"——只看回答少了哪个数字，分不清是记性差还是被裁了。

  5. 【顺序敏感性 / 防 advisor 累积回归】主运行（默认窗口）必须排在小窗口之前，
     而且两次都跑在**同一个 JVM** 里。这不是顺手为之，是故意的：
     Spring AI 侧一旦在每次请求里往注入的 ChatClient.Builder 上 defaultAdvisors(...)，
     advisor 会跨请求累积（addAll，不是覆盖），小窗口那次就会带着上一轮遗留的
     默认窗口 advisor，再被框架「历史已在 prompt 里就不重复注入」的静默去重吞掉 ——
     小窗口等于没生效、且全程不报错。**这个 bug 真的写过一遍**（见下面第 3 条教训），
     当时只有第 4 条那个锚点断言把它捅出来，其余弱断言全绿。

有两条一开始想错、被实测纠正的地方，都是真金白银，写下来：

  a) **不要断言 prompt token 逐轮递增。** 第一版就是这么写的，两侧全红。
     根因是工具调用：触发工具的那一轮，prompt 里除了历史消息还要回灌工具定义和工具结果，
     一次能多出四五百 token，量级远大于几条历史消息。所以 prompt 序列是
     [906, 467, 1012, 522] 这种锯齿形，跟记忆没关系。
     正确做法是**只在非工具轮之间比**，或用上面第 3 条那种受控对照。
     （进一步地，连"非工具轮至少两个"都不能硬断言 —— 回忆轮本身也可能调工具。）

  b) **小窗口不能取 4。** 取 4 时 Spring AI 恰好还能保住第 2 轮（锚点所在轮），
     锚点照样命中，断言分叉；取 2 才能让两侧都必然裁掉锚点。

  c) **弱断言会集体放行一个坏掉的实验。** 小窗口第一次跑出来是"只有 1 项红"：
     窗口参数、历史不超窗口、memoryFirstUser 变了 —— 全绿，
     因为这几项读的都是我们自己 new 出来的那个 memory 对象，它当然是 2 条。
     真正决定模型看到什么的是**链上生效的那个 advisor**，而它被旧 advisor 顶掉了。
     教训：断言要挑**穿过整个链路才能得到的量**（锚点进没进 prompt），
     而不是自己手里对象的内部状态。

另外还会验一个容易静默出错的点：
  - 回归：加了 ChatMemoryProvider Bean 之后，**没有 @MemoryId 的老端点必须照旧能用**
    （LangChain4j 的校验是单向的，这个方向不会报错，所以只能靠实测兜住）

必须先启动两个后端。用法：
    python probe/check-memory.py
    python probe/check-memory.py --no-small-window     # 省掉一半的模型调用
"""

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TIMEOUT = 600  # 一趟 memory 跑 = 5 次模型调用，P2 开思考时单次可能十几秒

OK = "[OK]"
BAD = "[X]"

# 工具返回值的特征值。回答里出现这些，说明这一轮真的调了工具
# —— 和 dashboard/app.js 里的 TOOL_SIGNS 是同一套判据。
TOOL_SIGNS = [re.compile(r"3,?214"), re.compile(r"1,?870"), re.compile(r"542")]

ROUND1_Q = "1区现在在线多少人？"

# 记忆锚点：工具产不出来的值。只有它能区分「记住了」和「现查了一遍」，
# 因为 3214 / 1870 这两个数字模型在回忆轮完全可以自己再调一次工具拿回来 ——
# 实测确实发生过：窗口把第 1 轮裁掉之后，回答里照样出现了 3214。
ANCHOR = "LOONG-7749"
FETCHABLE_TXT = "3214 / 1870"

# 小窗口对照用 2 条：这样连第 2 轮（锚点所在的那轮）都会被裁掉，
# 两侧的 anchorHit 必然为 false，断言才是统一的。
# 用 4 条时 P1 恰好还能保住第 2 轮，锚点照样命中，断言就分叉了。
SMALL_WINDOW = 2

failures = []


def check(name, cond, detail=""):
    tag = OK if cond else BAD
    print(f"  {tag} {name}" + (f"  —— {detail}" if detail else ""))
    if not cond:
        failures.append(name)
    return cond


def note(text):
    print(f"       {text}")


def request(url, origin=None, timeout=TIMEOUT):
    req = urllib.request.Request(url)
    if origin:
        req.add_header("Origin", origin)
    req.add_header("Accept", "application/json")
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        return resp.status, dict(resp.headers), resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        return None, {}, str(e)


def hdr(headers, key):
    for k, v in headers.items():
        if k.lower() == key.lower():
            return v
    return None


def q(s):
    return urllib.parse.quote(s)


def is_tool_round(turn):
    return any(p.search(turn.get("answer") or "") for p in TOOL_SIGNS)


def prompt_of(turn):
    return (turn.get("tokens") or {}).get("prompt")


def all_turns(run):
    return list(run["turns"]) + [run["recall"]]


def print_turns(label, run):
    total_rounds = len(run["turns"])
    print(f"       {label}  window={run['windowMaxMessages']}（{run['windowSource']}）")
    print("       轮次    性质   记忆条数   prompt  completion  total    耗时  记忆里的角色")
    rows = list(run["turns"]) + [run["recall"]]
    if run.get("control"):
        rows.append(run["control"])
    for t in rows:
        tk = t.get("tokens") or {}
        if t["round"] == -1:
            kind, no = "对照", "对照"
        else:
            kind = "工具轮" if is_tool_round(t) else "纯对话"
            no = "回忆" if t["round"] > total_rounds else f"第{t['round']}轮"
        roles = ",".join(t.get("memoryRoles") or []) or "-"
        print(
            f"       {no:<6} {kind:<6} {t['memoryMessages']:>6}   "
            f"{str(tk.get('prompt')):>6}  {str(tk.get('completion')):>9}  "
            f"{str(tk.get('total')):>6}  {t['elapsedMs']:>5}ms  {roles}"
        )


def check_growth(prefix, run):
    """
    记忆条数必须递增。prompt 只在**非工具轮**之间比较，且只作报告不作断言 ——
    因为回忆轮本身也可能触发工具（实测两侧都触发了），那样就一个非工具轮都不剩，
    硬断言会误报。prompt 的严格量化交给 check_controlled_cost 用受控对照来做。
    """
    seq = all_turns(run)

    mem = [t["memoryMessages"] for t in seq]
    check(f"{prefix} 记忆条数逐轮递增", all(b > a for a, b in zip(mem, mem[1:])), f"{mem}")

    nontool = [t for t in seq if not is_tool_round(t)]
    if len(nontool) >= 2:
        vals = [prompt_of(t) for t in nontool]
        check(
            f"{prefix} 非工具轮的 prompt 随历史递增",
            all(b > a for a, b in zip(vals, vals[1:])),
            "  ".join(f"第{t['round']}轮(历史{t['memoryMessages']}条)={prompt_of(t)}" for t in nontool),
        )
    else:
        note(f"非工具轮只有 {len(nontool)} 个（回忆轮也调了工具），"
             f"prompt 的增长改用下面的受控对照来量化")


def check_controlled_cost(prefix, run, min_per_msg=1.0):
    """
    受控测量：回忆轮 与 对照轮 是同一道题、同一套工具，唯一变量是历史条数。
    prompt 差 / 历史条数差 = 每条历史消息的平均 token 成本。
    """
    if not run.get("control"):
        return
    recall, control = run["recall"], run["control"]
    pr, pc = prompt_of(recall), prompt_of(control)
    if pr is None or pc is None:
        check(f"{prefix} 回忆/对照都有 prompt token", False, f"{pr} / {pc}")
        return

    delta_hist = recall["memoryMessages"] - control["memoryMessages"]
    delta_prompt = pr - pc
    check(
        f"{prefix} 记忆确实抬高了 prompt（同题对照）",
        delta_prompt > 0 and delta_hist > 0,
        f"历史 {control['memoryMessages']} 条 -> {recall['memoryMessages']} 条，"
        f"prompt {pc} -> {pr}，多花 {delta_prompt}",
    )
    if delta_hist > 0:
        per = delta_prompt / delta_hist
        check(
            f"{prefix} 每条历史消息的成本可算",
            per >= min_per_msg,
            f"约 {per:.1f} token/条（{delta_prompt} ÷ {delta_hist}）",
        )


def check_evidence(prefix, run):
    ev = run["evidence"]
    check(
        f"{prefix} 锚点 {ANCHOR} 被复述出来（记忆生效的决定性证据）",
        ev.get("anchorHit"),
        f"命中 {ev['hits']}，缺 {ev['misses']}",
    )
    check(
        f"{prefix} 无记忆对照侧连锚点都答不出",
        not ev["controlHit"],
        "对照侧也说出锚点了，说明实验不成立",
    )
    note(f"事实命中：{'、'.join(ev['hits']) or '（无）'}；缺失：{'、'.join(ev['misses']) or '（无）'}")
    note(f"提示：{FETCHABLE_TXT} 工具能现查，命中它们不构成记忆证据 —— 只看锚点")
    note(f"结论：{ev['verdict']}")
    note(f"回忆回答：{str(run['recall']['answer'])[:100]!r}")
    if run.get("control"):
        note(f"对照回答：{str(run['control']['answer'])[:100]!r}")


def run_memory(base, conversation_id, max_messages=None, reset=True):
    url = f"{base}/api/chat/memory?conversationId={q(conversation_id)}&reset={str(reset).lower()}"
    if max_messages is not None:
        url += f"&maxMessages={max_messages}"
    t0 = time.time()
    status, _, body = request(url)
    return status, body, time.time() - t0


def check_backend(label, base, origin, no_llm, small_window):
    print(f"\n{'=' * 76}\n{label}  {base}\n{'=' * 76}")

    # ---------- 0. CORS：新端点也要被 WebConfig 的 /api/** 规则覆盖 ----------
    status, headers, body = request(f"{base}/api/health", origin=origin)
    if status is None:
        check("能连上服务", False, f"连不上：{body}  ← 服务没起？")
        return
    acao = hdr(headers, "Access-Control-Allow-Origin")
    check(f"CORS 放行 {origin}", acao not in (None, ""), f"Access-Control-Allow-Origin={acao!r}")

    if no_llm:
        return

    # ---------- 1. 主运行：默认窗口 ----------
    print("\n  ── 主运行（窗口 = 自动配置/Bean 默认，20 条）──")
    status, body, took = run_memory(base, "probe-main")
    if not check("GET /api/chat/memory 返回 200", status == 200,
                 f"实际 {status}；耗时 {took:.1f}s" + (f"；body={body[:300]}" if status != 200 else "")):
        return

    try:
        run = json.loads(body)
    except Exception as e:  # noqa: BLE001
        check("memory 返回合法 JSON", False, f"{e}  原始前 200 字：{body[:200]!r}")
        return

    for field in ("conversationId", "windowMaxMessages", "windowSource", "turns", "recall",
                  "control", "evidence", "note"):
        check(f"含字段 {field}", field in run)
    for field in ("memoryMessages", "memoryRoles", "memoryFirstUser", "tokens", "elapsedMs"):
        check(f"Turn 含字段 {field}", field in (run["turns"][0] if run["turns"] else {}))

    print_turns("主运行", run)
    check_evidence("主运行", run)
    check_growth("主运行", run)
    check_controlled_cost("主运行", run)

    # 默认窗口下不该有裁剪：最早那条 user 消息应该还是第 1 轮的问题
    first = run["recall"].get("memoryFirstUser") or ""
    check(
        "主运行 默认窗口没有裁掉第 1 轮",
        first.startswith(ROUND1_Q[:10]),
        f"memoryFirstUser={first!r}",
    )

    # ---------- 2. 回归：没有 @MemoryId 的老端点必须照旧能用 ----------
    print("\n  ── 回归（加了 Provider Bean 之后，老端点会不会被带崩）──")
    status, _, body = request(f"{base}/api/chat?message={q('只回答两个字：收到')}")
    check("GET /api/chat 仍然 200", status == 200, f"实际 {status}；{body[:160]!r}")

    # ---------- 3. 小窗口：裁剪必须是可观测的 ----------
    if not small_window:
        print("\n  ── 小窗口对照：已跳过（--no-small-window）──")
        return

    print(f"\n  ── 小窗口对照（maxMessages={SMALL_WINDOW}，一轮就把窗口占满）──")
    status, body, took = run_memory(base, "probe-small", max_messages=SMALL_WINDOW)
    if not check("小窗口运行返回 200", status == 200, f"实际 {status}；耗时 {took:.1f}s"):
        return
    try:
        small = json.loads(body)
    except Exception as e:  # noqa: BLE001
        check("小窗口返回合法 JSON", False, str(e))
        return

    print_turns("小窗口", small)
    check("窗口参数被采纳", small["windowMaxMessages"] == SMALL_WINDOW,
          f"window={small['windowMaxMessages']}")

    caps = [t["memoryMessages"] for t in all_turns(small)]
    check("每轮送进去的历史都不超过窗口", all(c <= SMALL_WINDOW for c in caps), f"{caps}")

    # 裁剪的直接证据：最早那条 user 消息不再是第 1 轮的问题
    first_small = small["recall"].get("memoryFirstUser") or ""
    check(
        "裁剪可观测：最早那条 user 消息已不是第 1 轮",
        not first_small.startswith(ROUND1_Q[:10]),
        f"memoryFirstUser={first_small!r}  ← 不再是「{ROUND1_Q[:10]}…」",
    )

    # 结论层：锚点必须丢。这是这一节的硬断言 ——
    # 不能用「3214 丢了没」来判，因为模型可以现调工具把它查回来。
    ev = small["evidence"]
    check(
        f"窗口裁掉锚点 {ANCHOR}（记忆退化的硬证据）",
        not ev.get("anchorHit"),
        f"命中 {ev['hits']}，缺 {ev['misses']}",
    )
    if "3214" in ev["hits"]:
        note("注意：3214 仍然命中 —— 那大概率是回忆轮自己又调了一次工具查回来的，"
             "不是记忆。这正是必须用锚点判定的原因。")
    note(f"结论：{ev['verdict']}")
    # 把回答原文和记忆里的角色打出来。这一条是被「红项排查」逼出来的：
    # 当初这一项红了，而脚本只打了 hits/misses，看不到回答长什么样，
    # 只能靠 prompt token 去反推，白绕了一大圈。
    note(f"回忆回答：{str(small['recall']['answer'])[:140]!r}")
    note(f"对照回答：{str(small['control']['answer'])[:140]!r}" if small.get("control") else "（未跑对照）")
    note(f"回忆轮记忆里的角色：{small['recall'].get('memoryRoles')}")
    check_window_order_regression("小窗口对照", run, small)


def check_window_order_regression(prefix, run_a, run_b):
    """
    顺带把「advisor 累积」这类回归钉死。

    这两次调用本身没有断言价值 —— 有价值的是**顺序**：
    先默认窗口、后小窗口，跑在**同一个 JVM** 里。Spring AI 那边一旦有人在每次请求里
    往注入的 ChatClient.Builder 上 defaultAdvisors(...)（addAll 不是覆盖），
    小窗口这次就会带上上一轮遗留的默认窗口 advisor；框架又有「历史已在 prompt 里
    就不重复注入」的静默去重，于是小窗口被顶掉、且不报错。
    真发生过 —— 当时只有上面那条锚点断言把它捅出来，其它弱断言全绿。
    """
    a_anchor = run_a["evidence"].get("anchorHit")
    b_anchor = run_b["evidence"].get("anchorHit")
    check(
        f"{prefix} 同一 JVM 内先默认窗口后小窗口：结果必须不同（防 advisor 累积回归）",
        a_anchor and not b_anchor,
        f"默认窗口锚点命中={a_anchor}，小窗口锚点命中={b_anchor}；"
        f"两者都为 True 说明小窗口那次实际用的是旧 advisor",
    )


def main():
    ap = argparse.ArgumentParser(description="对话记忆端到端自检")
    ap.add_argument("--p1", default="http://localhost:8081")
    ap.add_argument("--p2", default="http://localhost:8082")
    ap.add_argument("--origin", default="http://localhost:8090")
    ap.add_argument("--no-llm", action="store_true", help="只验探活与 CORS（不花钱）")
    ap.add_argument("--no-small-window", action="store_true", help="跳过小窗口对照，省一半调用")
    args = ap.parse_args()

    print(f"模拟页面来源：{args.origin}")
    check_backend("Spring AI  (spring-ai-demo)", args.p1, args.origin, args.no_llm, not args.no_small_window)
    check_backend("LangChain4j (langchain4j-demo)", args.p2, args.origin, args.no_llm, not args.no_small_window)

    print(f"\n{'=' * 76}")
    if failures:
        print(f"{BAD} 有 {len(failures)} 项未通过：")
        for f in failures:
            print(f"     - {f}")
        sys.exit(1)
    print(f"{OK} 全部通过：记忆生效、对照失败、成本可量化、裁剪可观测。")


if __name__ == "__main__":
    main()
