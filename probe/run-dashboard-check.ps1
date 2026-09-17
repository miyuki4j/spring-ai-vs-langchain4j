<#
  对照台端到端自检：起三个服务 -> 起静态服务器 -> 跑 check-dashboard.py
                 -> 再用 CDP 对**真实后端**下的页面做一次 DOM 断言 -> 收摊。
  一次性跑完，避免服务在多次工具调用之间被回收。

  ⚠️ MCP server（8099）必须**先于两个后端**起来：两个后端启动时就会去拉远端
     工具清单，它不在的话后端起不来（表现为 p1-run.log / p2-run.log 里的连接失败）。

  用法（在仓库根目录下）：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\probe\run-dashboard-check.ps1
  日志：
      .workbuddy\logs\dashboard-check-<日期>.log   自检脚本的完整输出
      .workbuddy\logs\p1-run.log / p2-run.log      两个后端的控制台日志
      .workbuddy\logs\mcp-run.log                  MCP server 的控制台日志
      .workbuddy\logs\web.log                      静态服务器的日志
      .workbuddy\logs\dom-real-mcp.json            真实后端下的页面断言结果

  ⚠️ 本文件是 UTF-8 带 BOM（本机只有 PS 5.1，无 BOM 会把中文注释读崩）。
#>

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"   # 让 python 也直接吐 UTF-8，避免中文变乱码

$root   = "D:\Self\AIWorkspaces\AI4JWorkspaces"
$logdir = Join-Path $root ".workbuddy\logs"
New-Item -ItemType Directory -Force -Path $logdir | Out-Null
$log = Join-Path $logdir ("dashboard-check-" + (Get-Date -Format "yyyy-MM-dd") + ".log")

$python = "C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe"
if (-not (Test-Path $python)) { $python = "python" }

$webPort = 8090

function Get-ApiKey([string]$proj) {
    $xml = Get-Content (Join-Path $root "$proj\.idea\workspace.xml") -Raw -ErrorAction SilentlyContinue
    if ($xml -and $xml -match 'sk-[A-Za-z0-9_-]{20,}') { return $Matches[0] }
    return ""
}

function Test-Port([int]$port) {
    try {
        $c = New-Object Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', $port)
        $c.Close()
        return $true
    }
    catch { return $false }
}

$starter = {
    param($dir, $logfile, $key, $port)
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
    $env:Path = "$env:JAVA_HOME\bin;D:\Software\apache-maven-3.9.10\bin;$env:Path"
    $env:DEEPSEEK_API_KEY = $key
    $env:SERVER_PORT = "$port"     # 会话会注入 SERVER_PORT，它会覆盖 application.yml，必须显式设回
    Set-Location $dir
    mvn -B spring-boot:run *> $logfile
}

# MCP server 不带大模型、也不需要 key —— 它是纯工具提供方
$starterMcp = {
    param($dir, $logfile, $port)
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
    $env:Path = "$env:JAVA_HOME\bin;D:\Software\apache-maven-3.9.10\bin;$env:Path"
    $env:SERVER_PORT = "$port"
    Set-Location $dir
    mvn -B spring-boot:run *> $logfile
}

Write-Host "启动 MCP server（8099，先起它）..." -ForegroundColor Cyan
$jobMcp = Start-Job -ScriptBlock $starterMcp -ArgumentList "$root\mcp-skill-server", "$logdir\mcp-run.log", 8099
$mcpDeadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $mcpDeadline) {
    if (Test-Port 8099) { break }
    Start-Sleep -Seconds 2
}
if (-not (Test-Port 8099)) {
    Write-Host "  [!!] MCP server 没在 3 分钟内起来 —— 两个后端大概率也起不来" -ForegroundColor Red
}

Write-Host "启动两个后端（key 从 .idea/workspace.xml 读，不打印）..." -ForegroundColor Cyan
$job1 = Start-Job -ScriptBlock $starter -ArgumentList "$root\spring-ai-demo", "$logdir\p1-run.log", (Get-ApiKey 'spring-ai-demo'), 8081
$job2 = Start-Job -ScriptBlock $starter -ArgumentList "$root\langchain4j-demo", "$logdir\p2-run.log", (Get-ApiKey 'langchain4j-demo'), 8082

Write-Host "启动对照台静态服务器（$webPort）..." -ForegroundColor Cyan
$jobWeb = Start-Job -ScriptBlock {
    param($py, $dir, $port, $logfile)
    & $py -m http.server $port --directory $dir *> $logfile
} -ArgumentList $python, "$root\dashboard", $webPort, "$logdir\web.log"

$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    if ((Test-Port 8081) -and (Test-Port 8082)) { break }
    Start-Sleep -Seconds 3
}

$ok1 = Test-Port 8081
$ok2 = Test-Port 8082
$ok9 = Test-Port 8099
$okW = Test-Port $webPort
Write-Host "端口状态: 8099(MCP)=$ok9  8081=$ok1  8082=$ok2  静态页=$okW"

if ($ok1 -and $ok2) {
    Write-Host "跑 check-dashboard.py（会真实调用模型，后端 4 次 + MCP 4 次）..." -ForegroundColor Cyan
    # 用 cmd 重定向：写的是原始字节，绕开 PowerShell 的 UTF-16 重定向，日志里中文才是好的
    $mcpArg = if ($ok9) { "--mcp http://localhost:8099" } else { "--no-mcp" }
    cmd /c "`"$python`" `"$root\probe\check-dashboard.py`" --origin http://localhost:$webPort $mcpArg > `"$log`" 2>&1"
    Write-Host "自检输出已写入 $log" -ForegroundColor Green

    Write-Host "顺手确认静态页能被取到 ..."
    $status = ""
    try {
        $r = Invoke-WebRequest "http://localhost:$webPort/index.html" -UseBasicParsing -TimeoutSec 10
        $status = "HTTP $($r.StatusCode)，$($r.Content.Length) 字节"
    }
    catch {
        $status = "取不到：" + $_.Exception.Message
    }
    # 用 .NET 直接按 UTF-8 追加：PS 5.1 的 Add-Content 默认走 ANSI，
    # 会把这一行的中文写成乱码（前面那份日志就中招了）
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::AppendAllText($log, "`n[静态页] index.html -> $status`n", $utf8NoBom)
    Write-Host "  index.html -> $status"

    # ---------- 真实后端下的页面 DOM 断言 ----------
    # 上面 check-dashboard.py 验的是「接口给的数据对不对」；这一步验的是
    # 「页面把数据渲染成了什么」。两者之间还隔着一层 JS 判定逻辑
    # （哪一组算通过、三方算不算一致），那层错了**不报错**，
    # 只会安静地渲染出反的结论 —— 所以必须抠值，不能靠看截图。
    $node = "C:\Users\Administrator\.workbuddy\binaries\node\versions\22.22.2-3\node.exe"
    if (-not (Test-Path $node)) { $node = "node" }

    $domOut = Join-Path $logdir "dom-real-mcp.json"
    $realUrl = "http://localhost:$webPort/index.html?tab=mcp&auto=1"
    Write-Host "对真实后端下的页面做 DOM 断言（页面会再打 4 次模型，等 35 秒）..." -ForegroundColor Cyan

    # 用 cmd 起：node 的输出直接重定向成原始字节，和上面 python 一样绕开 PS 的 UTF-16
    # 不加 --quiet：日志里既要有勾选清单，也要留住表达式返回的结构化摘要（断言依据）
    cmd /c "`"$node`" `"$root\probe\dom-assert.mjs`" --url `"$realUrl`" --settle 35000 --expr-file `"$root\probe\assert-mcp-tab.js`" > `"$domOut`" 2>&1"
    $domExit = $LASTEXITCODE

    [System.IO.File]::AppendAllText($log, "`n[页面 DOM 断言 · 真实后端]`n", $utf8NoBom)
    if (Test-Path $domOut) {
        # ⚠️ 必须显式按 UTF-8 读：node 写出来的是 UTF-8 字节，而 PS 5.1 的
        # Get-Content 默认按 ANSI(GBK) 解 —— 读进来就已经是乱码，再以 UTF-8 追加
        # 就把乱码固化了（第一版日志就中招了：断言内容全成了「椤堕儴鏈?6 涓〉绛?」）。
        # 用 .NET 的 ReadAllText + 显式 Encoding 双保险，别用 Get-Content。
        $domText = [System.IO.File]::ReadAllText($domOut, [System.Text.Encoding]::UTF8)
        [System.IO.File]::AppendAllText($log, $domText + "`n", $utf8NoBom)
    }
    Write-Host ("  DOM 断言退出码 = $domExit（0=全通过）")
}
else {
    Write-Host "有服务没起来，跳过自检。看 $logdir\p1-run.log / p2-run.log" -ForegroundColor Red
}

Write-Host "收摊 ..." -ForegroundColor Cyan
foreach ($port in 8081, 8082, 8099) {
    $lines = netstat -ano | Select-String ":$port\s" | Select-String "LISTENING"
    foreach ($l in $lines) {
        $owner = ($l.ToString().Trim() -split '\s+')[-1]
        if ($owner -match '^\d+$') { Stop-Process -Id ([int]$owner) -Force -ErrorAction SilentlyContinue }
    }
}
Stop-Job $job1, $job2, $jobMcp, $jobWeb -ErrorAction SilentlyContinue
Remove-Job $job1, $job2, $jobMcp, $jobWeb -Force -ErrorAction SilentlyContinue

Start-Sleep -Seconds 2
Write-Host ("最终端口: 8099=" + (Test-Port 8099) + "  8081=" + (Test-Port 8081) + "  8082=" + (Test-Port 8082) + "  8090=" + (Test-Port $webPort))
Write-Host "DONE"
