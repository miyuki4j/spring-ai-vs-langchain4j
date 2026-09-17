<#
  MCP 端到端自检：起 mcp-skill-server + 两个后端 -> 跑 check-mcp.py -> 收摊。
  一次性跑完，避免后台服务在多次工具调用之间被回收（本机必现）。

  用法（在仓库根目录下）：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\probe\run-mcp-check.ps1

  日志：
      .workbuddy\logs\mcp-check-<日期>.log    自检脚本的完整输出
      .workbuddy\logs\mcp-server-run.log      MCP server 控制台
      .workbuddy\logs\p1-run.log / p2-run.log 两个后端控制台

  ⚠️ 本文件是 UTF-8 带 BOM（本机只有 PS 5.1，无 BOM 会把中文注释读崩）。

  ⚠️ 两个端口坑都在这一个脚本里踩过，注释留在原处：
      1) 会话注入的 SERVER_PORT 优先级高于 application.yml —— 两个后端必须显式设回；
      2) MCP server 用命令行参数 --server.port=8099 覆盖，比环境变量更硬。
#>

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"

$root   = "D:\Self\AIWorkspaces\AI4JWorkspaces"
$logdir = Join-Path $root ".workbuddy\logs"
New-Item -ItemType Directory -Force -Path $logdir | Out-Null
$log = Join-Path $logdir ("mcp-check-" + (Get-Date -Format "yyyy-MM-dd") + ".log")

$python = "C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe"
if (-not (Test-Path $python)) { $python = "python" }

$jdk   = "C:\Program Files\Java\jdk-17"
$maven = "D:\Software\apache-maven-3.9.10"
$jar   = Join-Path $root "mcp-skill-server\target\mcp-skill-server-0.0.1-SNAPSHOT.jar"

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

function Stop-Port([int]$port) {
    $lines = netstat -ano | Select-String ":$port\s" | Select-String "LISTENING"
    foreach ($l in $lines) {
        $owner = ($l.ToString().Trim() -split '\s+')[-1]
        if ($owner -match '^\d+$') { Stop-Process -Id ([int]$owner) -Force -ErrorAction SilentlyContinue }
    }
}

# ── 0. 先把三个服务都停干净，避免上一轮残留占着端口 ─────────────────
foreach ($p in 8099, 8081, 8082) { Stop-Port $p }
Start-Sleep -Seconds 1

# ── 1. 打包 MCP server（保证跑的是最新代码）─────────────────────────
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$maven\bin;$env:Path"
Write-Host "打包 mcp-skill-server ..." -ForegroundColor Cyan
Push-Location (Join-Path $root "mcp-skill-server")
& mvn -B -q package -DskipTests *> (Join-Path $logdir "mcp-package.log")
$pkgExit = $LASTEXITCODE
Pop-Location
if ($pkgExit -ne 0 -or -not (Test-Path $jar)) {
    Write-Host "打包失败（exit=$pkgExit），看 $logdir\mcp-package.log" -ForegroundColor Red
    exit 1
}

# ── 2. 起 MCP server ────────────────────────────────────────────────
Write-Host "启动 mcp-skill-server (8099) ..." -ForegroundColor Cyan
$mcpJob = Start-Job -ScriptBlock {
    param($jarPath, $logfile, $jdkHome)
    & "$jdkHome\bin\java.exe" -jar $jarPath --server.port=8099 *> $logfile
} -ArgumentList $jar, "$logdir\mcp-server-run.log", $jdk

# ── 3. 起两个后端 ───────────────────────────────────────────────────
$apiKey = Get-ApiKey 'spring-ai-demo'
if (-not $apiKey) { $apiKey = Get-ApiKey 'langchain4j-demo' }
if (-not $apiKey) {
    Write-Host "读不到 sk- 开头的 key（两个工程的 .idea\workspace.xml 都试过）。" -ForegroundColor Red
    Stop-Job $mcpJob -ErrorAction SilentlyContinue; Remove-Job $mcpJob -Force -ErrorAction SilentlyContinue
    exit 1
}

$starter = {
    param($dir, $logfile, $key, $port, $jdkHome, $mavenHome)
    $env:JAVA_HOME = $jdkHome
    $env:Path = "$jdkHome\bin;$mavenHome\bin;$env:Path"
    $env:DEEPSEEK_API_KEY = $key
    $env:SERVER_PORT = "$port"     # 会话注入的 SERVER_PORT 会覆盖 application.yml，必须显式设回
    Set-Location $dir
    mvn -B spring-boot:run *> $logfile
}

Write-Host "启动 spring-ai-demo (8081) ..." -ForegroundColor Cyan
$job1 = Start-Job -ScriptBlock $starter -ArgumentList "$root\spring-ai-demo", "$logdir\p1-run.log", $apiKey, 8081, $jdk, $maven

Write-Host "启动 langchain4j-demo (8082) ..." -ForegroundColor Cyan
$job2 = Start-Job -ScriptBlock $starter -ArgumentList "$root\langchain4j-demo", "$logdir\p2-run.log", $apiKey, 8082, $jdk, $maven

# ── 4. 等三个端口 ───────────────────────────────────────────────────
$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    if ((Test-Port 8099) -and (Test-Port 8081) -and (Test-Port 8082)) { break }
    Start-Sleep -Seconds 3
}
$okMcp = Test-Port 8099
$ok1 = Test-Port 8081
$ok2 = Test-Port 8082
Write-Host "端口状态: 8099(MCP)=$okMcp  8081(P1)=$ok1  8082(P2)=$ok2"

# ── 5. 跑自检 ───────────────────────────────────────────────────────
if ($okMcp -and $ok1 -and $ok2) {
    Write-Host "跑 check-mcp.py（4 次模型调用 + 1 次裸协议握手）..." -ForegroundColor Cyan
    # 用 cmd 重定向：写原始字节，绕开 PowerShell 的 UTF-16 重定向，日志里中文才是好的
    cmd /c "`"$python`" `"$root\probe\check-mcp.py`" > `"$log`" 2>&1"
    $rc = $LASTEXITCODE
    Write-Host "自检输出已写入 $log  (exit=$rc)" -ForegroundColor Green
    # 顺手回显，省得还要去开文件
    Get-Content $log -Encoding UTF8 | ForEach-Object { Write-Host $_ }
}
else {
    Write-Host "有服务没起来，跳过自检。抓日志尾部：" -ForegroundColor Red
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    foreach ($n in "mcp-server-run", "p1-run", "p2-run") {
        $f = Join-Path $logdir "$n.log"
        if (Test-Path $f) {
            $tail = (Get-Content $f -Tail 30 -ErrorAction SilentlyContinue) -join "`n"
            [System.IO.File]::AppendAllText($log, "`n===== $n.log 尾部 =====`n$tail`n", $utf8NoBom)
            Write-Host "  $n.log 尾部已追加到 $log"
        }
    }
}

# ── 6. 收摊 ─────────────────────────────────────────────────────────
Write-Host "收摊 ..." -ForegroundColor Cyan
foreach ($p in 8099, 8081, 8082) { Stop-Port $p }
Stop-Job $mcpJob, $job1, $job2 -ErrorAction SilentlyContinue
Remove-Job $mcpJob, $job1, $job2 -Force -ErrorAction SilentlyContinue

Start-Sleep -Seconds 2
Write-Host ("最终端口: 8099=" + (Test-Port 8099) + "  8081=" + (Test-Port 8081) + "  8082=" + (Test-Port 8082))
Write-Host "DONE"
