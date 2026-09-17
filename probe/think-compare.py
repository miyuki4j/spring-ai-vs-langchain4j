"""思考模式（reasoning_content）两侧对照实测。

同时打 spring-ai-demo(8081) 和 langchain4j-demo(8082) 的 /think 与 /think/stream。

用法：python think-compare.py
前置：两个服务都在跑。
"""
import json
import urllib.request
import urllib.error

P1 = "http://localhost:8081"
P2 = "http://localhost:8082"
MSG = "13 的平方是多少？只回答数字。"


def get(url, timeout=240):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def show_json(label, url):
    status, body = get(url)
    print(f"--- {label}")
    print(f"    GET {url.replace(P1, '').replace(P2, '')}")
    print(f"    HTTP {status}")
    if status != 200:
        print(f"    响应: {body[:400]}\n")
        return None
    j = json.loads(body)
    rc = j.get("reasoningContent")
    print(f"    answer            : {j.get('answer')!r}")
    print(f"    reasoningChars    : {j.get('reasoningChars')}")
    print(f"    tokens            : {j.get('tokens')}")
    if rc:
        print(f"    reasoning 前 100 字: {rc[:100]!r}")
    print()
    return j


def show_stream(label, url):
    status, body = get(url)
    frames = [l[5:].strip() for l in body.splitlines() if l.startswith("data:")]
    r_frames = [f for f in frames if f.startswith("R:")]
    c_frames = [f for f in frames if f.startswith("C:")]
    print(f"--- {label}")
    print(f"    GET {url.replace(P1, '').replace(P2, '')}")
    print(f"    HTTP {status}  总帧={len(frames)}  R(思考)帧={len(r_frames)}  C(回答)帧={len(c_frames)}")
    if r_frames:
        joined = "".join(f[2:] for f in r_frames)
        print(f"    思考拼接后前 100 字: {joined[:100]!r}")
    if c_frames:
        print(f"    回答拼接            : {''.join(f[2:] for f in c_frames)!r}")
    print()
    return len(r_frames), len(c_frames)


if __name__ == "__main__":
    from urllib.parse import quote
    q = quote(MSG)
    print("=" * 78)
    print(f"问题：{MSG}")
    print("=" * 78)

    print("\n########## 非流式 ##########")
    p1_on = show_json("P1 Spring AI  thinking=true", f"{P1}/api/chat/think?message={q}&thinking=true")
    p1_off = show_json("P1 Spring AI  thinking=false", f"{P1}/api/chat/think?message={q}&thinking=false")
    p2 = show_json("P2 LangChain4j（无开关可传）", f"{P2}/api/chat/think?message={q}")

    print("########## 流式 ##########")
    show_stream("P1 Spring AI  /think/stream thinking=true", f"{P1}/api/chat/think/stream?message={q}&thinking=true")
    show_stream("P2 LangChain4j /think/stream", f"{P2}/api/chat/think/stream?message={q}")

    print("=" * 78)
    print("结论：")
    print(f"  P1 thinking=true  -> reasoningChars = {p1_on.get('reasoningChars') if p1_on else '?'}")
    print(f"  P1 thinking=false -> reasoningChars = {p1_off.get('reasoningChars') if p1_off else '?'}")
    print(f"  P2（不传 thinking）-> reasoningChars = {p2.get('reasoningChars') if p2 else '?'}")
