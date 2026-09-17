<#
  一键启动：MCP server + 两个后端 + 对照台页面
  ============================================================
  做四件事：
    1. 在 8099 起 mcp-skill-server（工具提供方，不含大模型）
    2. 在 8081 起 spring-ai-demo（Spring AI）
    3. 在 8082 起 langchain4j-demo（LangChain4j）
    4. 在 8090 起一个纯静态服务器，托管 dashboard/
  三个后端各自开一个命令行窗口，方便你直接看日志。

  ⚠️ 顺序不能换：两个后端**启动时就会去拉远端工具清单**（MCP 客户端连不上会直接起不来），
     所以必须先把 mcp-skill-server 等到就绪，再去起它们。

  用法（在本目录下）：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1

  可选参数：
      -McpPort 8099 -P1Port 8081 -P2Port 8082 -WebPort 8090
      -NoOpen          不自动打开浏览器
      -JdkHome <路径>  覆盖 JDK 17 路径（默认自动探测）
      -MavenHome <路径> 覆盖 Maven 路径（默认 D:\Software\apache-maven-3.9.10）

  ⚠️ 本文件保存为 UTF-8 带 BOM。因为本机只有 Windows PowerShell 5.1（没有 pwsh 7），
     而 5.1 默认按 GBK 解析无 BOM 的 .ps1 —— 中文注释会把语法读崩，报「缺少右 }」这类假错误。
#>
[CmdletBinding()]
param(
    [int]$McpPort = 8099,
    [int]$P1Port = 8081,
    [int]$P2Port = 8082,
    [int]$WebPort = 8090,
    [string]$JdkHome = '',
    [string]$MavenHome = 'D:\Software\apache-maven-3.9.10',
    [switch]$NoOpen
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# ---------------- 环境探测 ----------------

function Resolve-Jdk {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    $dirs = @('C:\Program Files\Java', 'D:\Program Files\Java', 'D:\Software')
    foreach ($d in $dirs) {
        if (-not (Test-Path $d)) { continue }
        $hit = Get-ChildItem $d -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
               Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
               Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    throw "找不到 JDK 17。请用 -JdkHome <路径> 指定。"
}

function Resolve-ApiKey {
    # 1) 优先用已设置的环境变量（最安全，不落盘）
    if ($env:DEEPSEEK_API_KEY -and $env:DEEPSEEK_API_KEY -notmatch 'REPLACE-ME') {
        return $env:DEEPSEEK_API_KEY
    }
    # 2) 回退：从 IDE 的 workspace.xml 里取（.gitignore 已挡住 .idea/，不会进 git）
    $files = @(
        (Join-Path $root 'spring-ai-demo\.idea\workspace.xml'),
        (Join-Path $root 'langchain4j-demo\.idea\workspace.xml')
    )
    foreach ($f in $files) {
        if (-not (Test-Path $f)) { continue }
        $m = [regex]::Match((Get-Content $f -Raw), 'sk-[A-Za-z0-9_\-]{20,}')
        if ($m.Success) { return $m.Value }
    }
    throw "找不到 DeepSeek API Key。请先设置环境变量 DEEPSEEK_API_KEY，或确认两个项目的 .idea\workspace.xml 里存有 key。"
}

function Resolve-Python {
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cand = Join-Path $env:USERPROFILE '.workbuddy\binaries\python\versions\3.13.12\python.exe'
    if (Test-Path $cand) { return $cand }
    throw "找不到 python。对照台页面需要一个静态服务器（python -m http.server）。"
}

function Test-PortOpen {
    param([int]$Port)
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', $Port)
        $c.Close()
        return $true
    } catch { return $false }
}

function Wait-Port {
    param([int]$Port, [int]$TimeoutSec = 150)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        if (Test-PortOpen -Port $Port) { return $true }
        Start-Sleep -Milliseconds 800
    }
    return $false
}

# ---------------- 准备 ----------------

$JdkHome  = Resolve-Jdk -Explicit $JdkHome
$apiKey   = Resolve-ApiKey
$python   = Resolve-Python

Write-Host ""
Write-Host "JDK     : $JdkHome"
Write-Host "Maven   : $MavenHome"
Write-Host "Python  : $python"
Write-Host "API Key : $($apiKey.Substring(0,6))****$($apiKey.Substring($apiKey.Length-4))  (只注入进程环境，不写在命令行里)"
Write-Host ""

if (-not (Test-Path (Join-Path $MavenHome 'bin\mvn.cmd'))) {
    throw "在 $MavenHome 下找不到 bin\mvn.cmd，请用 -MavenHome 指定。"
}

# 三个进程各自的端口，用显式设置覆盖 application.yml
# （这一点很关键：宿主环境若注入了 SERVER_PORT，它的优先级高于 yml —— 本机就踩过，
#   mcp-skill-server 曾因此起在 51184，报「Port 51184 was already in use」）
$env:JAVA_HOME = $JdkHome
$env:DEEPSEEK_API_KEY = $apiKey
$env:Path = "$JdkHome\bin;$MavenHome\bin;$env:Path"

# ---------------- 先起 MCP server（两个后端依赖它）----------------

Write-Host "启动 mcp-skill-server  (端口 $McpPort) ..."
$env:SERVER_PORT = "$McpPort"
Start-Process cmd.exe -ArgumentList '/k', "cd /d `"$root\mcp-skill-server`" && mvn -B spring-boot:run" | Out-Null

Write-Host "等待 mcp-skill-server 就绪 ..."
$okMcp = Wait-Port -Port $McpPort
if (-not $okMcp) {
    Write-Host "  [!!] mcp-skill-server 未在 150 秒内监听 $McpPort —— 两个后端起不来，先去看那个窗口。" -ForegroundColor Red
}

# ---------------- 再起两个后端 ----------------

Write-Host "启动 spring-ai-demo  (端口 $P1Port) ..."
$env:SERVER_PORT = "$P1Port"
Start-Process cmd.exe -ArgumentList '/k', "cd /d `"$root\spring-ai-demo`" && mvn -B spring-boot:run" | Out-Null

Write-Host "启动 langchain4j-demo  (端口 $P2Port) ..."
$env:SERVER_PORT = "$P2Port"
Start-Process cmd.exe -ArgumentList '/k', "cd /d `"$root\langchain4j-demo`" && mvn -B spring-boot:run" | Out-Null

# 起静态服务器时不应该把 SERVER_PORT 带出去，清掉
Remove-Item Env:\SERVER_PORT -ErrorAction SilentlyContinue

# ---------------- 起对照台页面 ----------------

Write-Host "启动对照台页面  (端口 $WebPort) ..."
Start-Process $python -ArgumentList '-m', 'http.server', "$WebPort", '--directory', "$root\dashboard" | Out-Null

# ---------------- 等就绪 ----------------

Write-Host ""
Write-Host "等待两个后端编译并启动（首次可能要下依赖，慢一点）..."
$ok1 = Wait-Port -Port $P1Port
$ok2 = Wait-Port -Port $P2Port

Write-Host ""
if ($okMcp) { Write-Host "  [OK] mcp-skill-server  http://localhost:$McpPort/mcp" } else { Write-Host "  [!!] mcp-skill-server  未就绪，两个后端大概率起不来" }
if ($ok1) { Write-Host "  [OK] spring-ai-demo    http://localhost:$P1Port" } else { Write-Host "  [!!] spring-ai-demo    未在 150 秒内监听 $P1Port，去看那个窗口的日志" }
if ($ok2) { Write-Host "  [OK] langchain4j-demo  http://localhost:$P2Port" } else { Write-Host "  [!!] langchain4j-demo  未在 150 秒内监听 $P2Port，去看那个窗口的日志" }
Write-Host "  [OK] 对照台            http://localhost:$WebPort"
Write-Host ""

if (-not $NoOpen) {
    Start-Process "http://localhost:$WebPort/"
}

Write-Host "关掉那两个后端窗口即可停止服务。"
