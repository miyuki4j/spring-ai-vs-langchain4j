<#
  对话记忆自检：起两个后端 -> 跑 check-memory.py -> 收摊。一次性跑完，
  避免后台服务在多次工具调用之间被回收（本机必现）。

  用法（在仓库根目录下）：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\probe\run-memory-check.ps1
  可选：$env:MEMCHECK_ARGS = "--no-small-window"  跳过小窗口对照，省一半模型调用
  日志：
      .workbuddy\logs\memory-check-<日期>.log      自检脚本的完整输出
      .workbuddy\logs\p1-run.log / p2-run.log      两个后端的控制台日志

  ⚠️ 本文件是 UTF-8 带 BOM（本机只有 PS 5.1，无 BOM 会把中文注释读崩）。
#>

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"

$root   = "D:\Self\AIWorkspaces\AI4JWorkspaces"
$logdir = Join-Path $root ".workbuddy\logs"
New-Item -ItemType Directory -Force -Path $logdir | Out-Null
$log = Join-Path $logdir ("memory-check-" + (Get-Date -Format "yyyy-MM-dd") + ".log")

$python = "C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe"
if (-not (Test-Path $python)) { $python = "python" }

$extraArgs = ""
if ($env:MEMCHECK_ARGS) { $extraArgs = $env:MEMCHECK_ARGS }

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

$key1 = Get-ApiKey 'spring-ai-demo'
$key2 = Get-ApiKey 'langchain4j-demo'
if (-not $key1 -and -not $key2) {
    Write-Host "两个工程的 .idea\workspace.xml 里都读不到 sk- 开头的 key，先检查一下。" -ForegroundColor Red
    exit 1
}

$starter = {
    param($dir, $logfile, $key, $port)
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
    $env:Path = "$env:JAVA_HOME\bin;D:\Software\apache-maven-3.9.10\bin;$env:Path"
    $env:DEEPSEEK_API_KEY = $key
    $env:SERVER_PORT = "$port"     # 会话注入的 SERVER_PORT 会覆盖 application.yml，必须显式设回
    Set-Location $dir
    mvn -B spring-boot:run *> $logfile
}

Write-Host "启动两个后端（key 从 .idea\workspace.xml 读，不打印）..." -ForegroundColor Cyan
$job1 = Start-Job -ScriptBlock $starter -ArgumentList "$root\spring-ai-demo", "$logdir\p1-run.log", $key1, 8081
$job2 = Start-Job -ScriptBlock $starter -ArgumentList "$root\langchain4j-demo", "$logdir\p2-run.log", $key2, 8082

$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    if ((Test-Port 8081) -and (Test-Port 8082)) { break }
    Start-Sleep -Seconds 3
}

$ok1 = Test-Port 8081
$ok2 = Test-Port 8082
Write-Host "端口状态: 8081=$ok1  8082=$ok2"

if ($ok1 -and $ok2) {
    Write-Host "跑 check-memory.py（每个后端 5 次模型调用，含小窗口则 10 次）..." -ForegroundColor Cyan
    # 用 cmd 重定向：写原始字节，绕开 PowerShell 的 UTF-16 重定向，日志里中文才是好的
    cmd /c "`"$python`" `"$root\probe\check-memory.py`" $extraArgs > `"$log`" 2>&1"
    Write-Host "自检输出已写入 $log" -ForegroundColor Green
}
else {
    Write-Host "有服务没起来，跳过自检。看 $logdir\p1-run.log / p2-run.log" -ForegroundColor Red
    # 起不来时抓启动日志的尾部，省得再去翻文件
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    foreach ($n in "p1", "p2") {
        $f = Join-Path $logdir "$n-run.log"
        if (Test-Path $f) {
            $tail = (Get-Content $f -Tail 25 -ErrorAction SilentlyContinue) -join "`n"
            [System.IO.File]::AppendAllText($log, "`n===== $n-run.log 尾部 =====`n$tail`n", $utf8NoBom)
        }
    }
}

Write-Host "收摊 ..." -ForegroundColor Cyan
foreach ($port in 8081, 8082) {
    $lines = netstat -ano | Select-String ":$port\s" | Select-String "LISTENING"
    foreach ($l in $lines) {
        $owner = ($l.ToString().Trim() -split '\s+')[-1]
        if ($owner -match '^\d+$') { Stop-Process -Id ([int]$owner) -Force -ErrorAction SilentlyContinue }
    }
}
Stop-Job $job1, $job2 -ErrorAction SilentlyContinue
Remove-Job $job1, $job2 -Force -ErrorAction SilentlyContinue

Start-Sleep -Seconds 2
Write-Host ("最终端口: 8081=" + (Test-Port 8081) + "  8082=" + (Test-Port 8082))
Write-Host "DONE"
