"""比对两个 Maven 仓库：命令行用的 vs IDE 用的。

为什么需要这个脚本？
  本机有两个 Maven 仓库，互不相通：
    - 命令行  mvn（D:\\Software\\apache-maven-3.9.10）
      读 conf/settings.xml -> localRepository = D:\\Software\\apache-maven-reop
    - IDE（内置 Maven）
      读 ~/.m2/settings.xml -> 不存在 -> 落到默认的 C:\\Users\\<user>\\.m2\\repository

  于是会出现"命令行编译通过、IDE 一片爆红"这种最容易被误判成代码错误的现象。
  这个脚本把某个工程的**真实依赖 classpath** 逐个映射到另一个仓库，直接列出缺哪些 jar。

用法：
  1) 先在工程目录导出 classpath（用命令行那份 settings）：
     mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
  2) python check-repo-gap.py cp.txt
"""

import pathlib
import sys

CUSTOM_REPO = r"D:\Software\apache-maven-reop"
DEFAULT_REPO = r"C:\Users\Administrator\.m2\repository"


def main() -> int:
    cp_file = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "cp.txt")
    if not cp_file.exists():
        print(f"[X] 找不到 classpath 文件：{cp_file}")
        return 2

    raw = cp_file.read_text(encoding="utf-8", errors="replace").strip()
    items = [x for x in raw.split(";") if x.strip()] or [x for x in raw.split(":") if x.strip()]

    missing, present = [], []
    for item in items:
        rel = item.replace(CUSTOM_REPO, "").replace(DEFAULT_REPO, "").lstrip("\\/")
        (present if pathlib.Path(DEFAULT_REPO, rel).exists() else missing).append(rel)

    print(f"classpath 条目 : {len(items)}")
    print(f"默认仓库里有   : {len(present)}")
    print(f"默认仓库里缺   : {len(missing)}")
    print()
    if missing:
        print("=== 缺失（这些就是 IDE 爆红的根因）===")
        for m in missing:
            print("  X", m.split("\\")[-1])
    else:
        print("=== 默认仓库是完整的，IDE 爆红不是依赖缺失导致的 ===")
    return 0 if not missing else 1


if __name__ == "__main__":
    raise SystemExit(main())
