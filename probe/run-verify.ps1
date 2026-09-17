<#
  一体化验证：起两个服务 -> 等端口就绪 -> 跑 verify-demos.ps1 -> 收服务。
  一次性跑完，避免服务在多次工具调用之间被回收。

  用法：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\.workbuddy\probe\run-verify.ps1
  日志：
      .workbuddy\logs\verify-<日期>.log        验证脚本的完整输出
      .workbuddy\logs\p1-run.log / p2-run.log  两个服务的控制台日志
#>

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$root   = "D:\Self\AIWorkspaces\AI4JWorkspaces"
$logdir = Join-Path $root ".workbuddy\logs"
$log    = Join-Path $logdir ("verify-" + (Get-Date -Format "yyyy-MM-dd") + ".log")
New-Item -ItemType Directory -Force -Path $logdir | Out-Null

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
    $env:SERVER_PORT = "$port"     # 会话会注入 SERVER_PORT，会覆盖 application.yml，必须显式设回
    Set-Location $dir
    mvn -B spring-boot:run *> $logfile
}

Write-Host "启动两个服务（key 从 .idea/workspace.xml 读，不打印）..." -ForegroundColor Cyan
$job1 = Start-Job -ScriptBlock $starter -ArgumentList "$root\spring-ai-demo", "$logdir\p1-run.log", (Get-ApiKey 'spring-ai-demo'), 8081
$job2 = Start-Job -ScriptBlock $starter -ArgumentList "$root\langchain4j-demo", "$logdir\p2-run.log", (Get-ApiKey 'langchain4j-demo'), 8082

$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    if ((Test-Port 8081) -and (Test-Port 8082)) { break }
    Start-Sleep -Seconds 3
}

$ok1 = Test-Port 8081
$ok2 = Test-Port 8082
Write-Host "端口状态: 8081=$ok1  8082=$ok2"

if ($ok1 -and $ok2) {
    Write-Host "跑 verify-demos.ps1 ..." -ForegroundColor Cyan
    Set-Location $root
    & "$root\verify-demos.ps1" *> $log
    Write-Host "验证输出已写入 $log" -ForegroundColor Green
}
else {
    Write-Host "有服务没起来，跳过验证。看 $logdir\p1-run.log / p2-run.log" -ForegroundColor Red
}

Write-Host "收服务 ..." -ForegroundColor Cyan
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
