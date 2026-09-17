"""DeepSeek 思考模式行为探针（不打印 key，只输出结论）。

回答三个问题：
  A. thinking.type=disabled  时，响应里有没有 reasoning_content？
  B. 完全不传 thinking 字段 时，有没有？（这一条决定 LangChain4j 能否拿到思考）
  C. thinking.type=enabled   时，有没有？

用法：
  set DEEPSEEK_API_KEY 环境变量，或从 .idea/workspace.xml 自动读取。
"""
import json
import os
import re
import sys
import urllib.request
import urllib.error

API = "https://api.deepseek.com/chat/completions"
MODEL = os.environ.get("AI_MODEL", "deepseek-flash")


def find_root(start):
    """向上找到同时含 spring-ai-demo 和 langchain4j-demo 的目录（即仓库根）。"""
    p = os.path.abspath(start)
    while True:
        if os.path.isdir(os.path.join(p, "spring-ai-demo")) and os.path.isdir(os.path.join(p, "langchain4j-demo")):
            return p
        parent = os.path.dirname(p)
        if parent == p:
            sys.exit("往上一路都没找到仓库根目录（缺 spring-ai-demo / langchain4j-demo）")
        p = parent


ROOT = find_root(os.path.dirname(os.path.abspath(__file__)))

PROMPT = "计算 2+3，只输出数字"


def load_key():
    k = os.environ.get("DEEPSEEK_API_KEY")
    if k and k.startswith("sk-"):
        return k
    for name in ("spring-ai-demo", "langchain4j-demo"):
        p = os.path.join(ROOT, name, ".idea", "workspace.xml")
        if os.path.exists(p):
            m = re.search(r"sk-[A-Za-z0-9_-]{20,}", open(p, encoding="utf-8", errors="replace").read())
            if m:
                return m.group(0)
    sys.exit("找不到 DEEPSEEK_API_KEY（环境变量和 .idea/workspace.xml 都没有）")


def call(key, label, extra):
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": PROMPT}],
        "max_tokens": 512,
    }
    body.update(extra)
    req = urllib.request.Request(
        API,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            status, raw = r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read().decode("utf-8", errors="replace")

    if status != 200:
        print(f"[{label}] HTTP {status}  请求体={json.dumps(extra, ensure_ascii=False)}")
        print("       响应:", raw[:300])
        return None

    j = json.loads(raw)
    msg = j["choices"][0]["message"]
    rc = msg.get("reasoning_content")
    usage = j.get("usage", {})
    print(f"[{label}] HTTP 200  请求体={json.dumps(extra, ensure_ascii=False)}")
    print(f"       message 字段: {sorted(msg.keys())}")
    print(f"       reasoning_content: {'有，' + str(len(rc)) + ' 字符' if rc else '无/null'}")
    print(f"       content: {repr((msg.get('content') or '')[:60])}")
    print(f"       usage: {usage}")
    if rc:
        print(f"       reasoning 样例: {repr(rc[:80])}")
    print()
    return rc


def main():
    key = load_key()
    print(f"model={MODEL}  prompt={PROMPT}\n" + "=" * 70)
    results = {}
    results["A_disabled"] = call(key, "A thinking=disabled", {"thinking": {"type": "disabled"}})
    results["B_absent"] = call(key, "B 不传 thinking 字段", {})
    results["C_enabled"] = call(key, "C thinking=enabled", {"thinking": {"type": "enabled"}})

    print("=" * 70)
    print("结论：")
    print(f"  A disabled -> reasoning_content: {'有' if results['A_disabled'] else '无'}")
    print(f"  B 不传字段 -> reasoning_content: {'有' if results['B_absent'] else '无'}")
    print(f"  C enabled  -> reasoning_content: {'有' if results['C_enabled'] else '无'}")
    if results["B_absent"]:
        print("  => 服务端默认开启思考：LangChain4j 即使不传 thinking，也能拿到 reasoning_content")
    elif results["C_enabled"] and not results["B_absent"]:
        print("  => 服务端默认关闭思考：LangChain4j 不传 thinking 就拿不到，必须想办法注入该字段")

    # ---- 第二轮：reasoning_effort（OpenAI 的参数名，LangChain4j 的 starter 有对应配置项）----
    print("\n" + "=" * 70)
    print("附测：reasoning_effort（OpenAI 的参数名，DeepSeek 认不认？）")
    print("=" * 70)
    for effort in ("high", "max", "low", "none"):
        call(key, f"reasoning_effort={effort}", {"reasoning_effort": effort})


if __name__ == "__main__":
    main()
