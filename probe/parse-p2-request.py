"""从 langchain4j-demo 的运行日志里挖出「实际发出的请求体」，用于证明：
  1) 顶层没有 thinking / reasoning_effort / response_format 参数
  2) 返回类型写 ChatResponse 时，框架会把 JSON schema 指令塞进 user 消息

用法：python parse-p2-request.py [日志路径]
"""
import json
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else \
    r"D:\Self\AIWorkspaces\AI4JWorkspaces\.workbuddy\logs\p2-run.log"


def read_text(p):
    b = open(p, "rb").read()
    for enc in ("utf-16", "utf-8", "gbk"):
        try:
            return b.decode(enc)
        except Exception:
            continue
    return b.decode("utf-8", errors="replace")


def extract_body(lines, start):
    """从 '- body:' 那行开始，按括号深度切出完整 JSON（跳过字符串内的括号）。"""
    depth = 0
    in_str = False
    esc = False
    started = False
    buf = []
    for i in range(start, len(lines)):
        line = "{" if i == start else lines[i]
        buf.append(line)
        for ch in line:
            if esc:
                esc = False
                continue
            if ch == "\\":
                esc = True
                continue
            if ch == '"':
                in_str = not in_str
                continue
            if in_str:
                continue
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
        if started and depth == 0:
            break
    return json.loads("\n".join(buf))


def main():
    lines = read_text(PATH).splitlines()
    starts = [i for i, l in enumerate(lines) if l.strip().startswith("- body:")]
    if not starts:
        sys.exit("日志里没找到 '- body:'，确认应用挂了 log-requests: true 并跑过请求")
    print(f"日志：{PATH}")
    print(f"找到 {len(starts)} 个请求体，逐个列出顶层键：\n")

    for k, s in enumerate(starts, 1):
        try:
            j = extract_body(lines, s)
        except Exception as e:
            print(f"[请求 {k}] 解析失败：{e}")
            continue
        url = next((l.strip() for l in lines[max(0, s - 6):s] if "url:" in l), "?")
        print(f"[请求 {k}] {url}")
        for key, val in j.items():
            if isinstance(val, list):
                print(f"    {key}: <{len(val)} 项>")
            else:
                print(f"    {key}: {val!r}"[:170])
        print(f"    >>> 有 thinking? {'thinking' in j}   "
              f"有 reasoning_effort? {'reasoning_effort' in j}   "
              f"有 response_format? {'response_format' in j}")
        # 如果 user 消息里被塞了 JSON 指令，把塞进去的那段单独打出来
        for m in j.get("messages", []):
            c = m.get("content") or ""
            if isinstance(c, str) and "You must answer strictly in the following JSON format" in c:
                head = c.split("You must answer strictly in the following JSON format", 1)[1]
                print(f"    >>> user 消息被注入的 JSON 指令前 220 字：{head[:220]!r}")
        print()


if __name__ == "__main__":
    main()
