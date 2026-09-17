<#
  MCP server 裸协议自检（不含大模型，秒级）：
    起 mcp-skill-server（8099）-> 跑 check-mcp-server.py -> 收摊。

  比 run-dashboard-check.ps1 轻得多：后者要拉起两个后端 + 真调模型，约 2 分钟。
  改完 server 侧代码、或只想确认「server 本身对不对」时，先跑这一个最快。

  用法（仓库根目录）：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\probe\run-mcp-server-check.ps1
  日志：
      .workbuddy\logs\mcp-server-check-<日期>.log   裸协议自检的完整输出
      .workbuddy\logs\mcp-matrix-run.log            MCP server 的控制台日志

  ⚠️ 本文件是 UTF-8 带 BOM（本机只有 PS 5.1，无 BOM 会把中文注释读崩）。
  ⚠️ 服务与探针必须在同一个 PowerShell 会话里起/连：本机存在网络沙箱，
     一个 shell 里起的监听端口，另一个 shell 未必连得到。
#>

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"

$root   = "D:\Self\AIWorkspaces\AI4JWorkspaces"
$logdir = Join-Path $root ".workbuddy\logs"
New-Item -ItemType Directory -Force -Path $logdir | Out-Null
$log = Join-Path $logdir ("mcp-server-check-" + (Get-Date -Format "yyyy-MM-dd") + ".log")

$python = "C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe"
if (-not (Test-Path $python)) { $python = "python" }

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

$jar = Join-Path $root "mcp-skill-server\target\mcp-skill-server-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path $jar)) {
    Write-Host "找不到 $jar" -ForegroundColor Red
    Write-Host "先在 mcp-skill-server 目录执行：mvn -q -DskipTests package" -ForegroundColor Yellow
    exit 1
}

# 清掉可能残留的旧实例，避免端口占用导致新实例起不来
Stop-Port 8099
Start-Sleep -Seconds 1

$starter = {
    param($jarPath, $logfile, $port)
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    # 会话会注入 SERVER_PORT（本机实测是 SERVER__PORT=51184），它会盖掉 application.yml，
    # 所以既显式设回环境变量、又在命令行上再压一层（命令行优先级最高）。
    $env:SERVER_PORT = "$port"
    & "$env:JAVA_HOME\bin\java" -jar $jarPath "--server.port=$port" *> $logfile
}

Write-Host "启动 MCP server（8099）..." -ForegroundColor Cyan
$job = Start-Job -ScriptBlock $starter -ArgumentList $jar, "$logdir\mcp-matrix-run.log", 8099
$deadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $deadline) {
    if (Test-Port 8099) { break }
    Start-Sleep -Seconds 1
}
if (-not (Test-Port 8099)) {
    Write-Host "  [!!] 8099 没起来 —— 见 $logdir\mcp-matrix-run.log" -ForegroundColor Red
    exit 1
}

Write-Host "跑裸协议自检（含协议版本协商矩阵）..." -ForegroundColor Cyan
# 用 cmd 重定向：写的是原始字节，绕开 PowerShell 的 UTF-16 重定向，日志里中文才是好的
cmd /c "`"$python`" `"$root\probe\check-mcp-server.py`" --url http://127.0.0.1:8099/mcp > `"$log`" 2>&1"
$code = $LASTEXITCODE

Write-Host "收摊 ..." -ForegroundColor Cyan
Stop-Port 8099
Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue

Start-Sleep -Seconds 2
Write-Host ("自检退出码 = $code（0=全通过）")
Write-Host ("日志：$log")
Write-Host "DONE"
