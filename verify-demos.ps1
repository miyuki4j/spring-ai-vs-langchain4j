<#
  验证 spring-ai-demo / langchain4j-demo 的样例端点。

  用法（在本目录下）：
      pwsh -File .\verify-demos.ps1

  为什么用 curl.exe 而不是 Invoke-WebRequest：
      1. Windows PowerShell 里 curl 是 Invoke-WebRequest 的别名，要用 curl.exe 才是真 curl
      2. Invoke-WebRequest 会把流式响应整体缓冲，看不到逐字返回；curl.exe -N 才能看到 SSE 实时输出
#>

$ErrorActionPreference = "Continue"

$springAiBase = "http://localhost:8081"
$lc4jBase     = "http://localhost:8082"

function Invoke-Check {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Url,
        [string]$Expect = "",
        [int]$TimeoutSec = 90
    )

    Write-Host ""
    Write-Host ("-" * 72)
    Write-Host "  $Name" -ForegroundColor Cyan
    Write-Host "  $Url" -ForegroundColor DarkGray
    Write-Host ("-" * 72)

    $raw  = (& curl.exe -s -N -m $TimeoutSec -w "`n__STATUS__%{http_code}" $Url 2>&1 | Out-String)
    $exit = $LASTEXITCODE

    if ($exit -ne 0) {
        Write-Host "  [X] curl 退出码 $exit —— 这个端口上没服务在监听？" -ForegroundColor Red
        return
    }

    $status = "?"
    if ($raw -match "__STATUS__(\d+)") {
        $status = $matches[1]
        $raw = $raw -replace "\s*__STATUS__\d+\s*$", ""
    }

    if ($status -eq "200") {
        Write-Host "  [OK] HTTP 200" -ForegroundColor Green
    }
    else {
        Write-Host "  [X] HTTP $status" -ForegroundColor Red
    }

    $body = $raw.Trim()
    if ([string]::IsNullOrWhiteSpace($body)) {
        Write-Host "  [X] 响应体为空" -ForegroundColor Red
    }
    else {
        Write-Host ""
        Write-Host $body
        Write-Host ""
    }

    if ($Expect -ne "") {
        if ($body -match $Expect) {
            Write-Host "  [OK] 命中预期 /$Expect/" -ForegroundColor Green
        }
        else {
            Write-Host "  [X] 未命中预期 /$Expect/ —— 需要看服务端控制台日志" -ForegroundColor Red
        }
    }
}

Write-Host ""
Write-Host "==== spring-ai-demo (8081) + langchain4j-demo (8082) 端点验证 ====" -ForegroundColor Yellow

# ---------------- 项目 1：Spring AI ----------------

Invoke-Check -Name "P1-1  chat   基础问答" `
    -Url "$springAiBase/api/chat?message=Reply%20with%20exactly%3A%20SPRINGAI-OK" `
    -Expect "SPRINGAI-OK"

Invoke-Check -Name "P1-2  stream 流式（应看到多行 data:）" `
    -Url "$springAiBase/api/chat/stream?message=Count%20from%201%20to%205" `
    -Expect "data:"

# 这条是关键：3214 是 GameServerTools 里写死的假数据，模型不可能凭空知道。
# 回答里出现 3214 => 工具调用链路真的通了。
Invoke-Check -Name "P1-3  agent  工具调用（期望出现 3214）" `
    -Url "$springAiBase/api/chat/agent?message=How%20many%20players%20are%20online%20on%20server%20s1%3F" `
    -Expect "3,?214"

# ---------------- 项目 2：LangChain4j ----------------

Invoke-Check -Name "P2-1  chat   基础问答" `
    -Url "$lc4jBase/api/chat?message=Reply%20with%20exactly%3A%20LC4J-OK" `
    -Expect "LC4J-OK"

Invoke-Check -Name "P2-2  stream 流式（应看到多行 data:）" `
    -Url "$lc4jBase/api/chat/stream?message=Count%20from%201%20to%205" `
    -Expect "data:"

# P2 的工具没有独立端点：GameOpsAssistant 是声明式装配，直接问就会触发。
Invoke-Check -Name "P2-3  chat   工具调用（期望出现 3214）" `
    -Url "$lc4jBase/api/chat?message=How%20many%20players%20are%20online%20on%20server%20s1%3F" `
    -Expect "3,?214"

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Yellow
Write-Host "若有 [X]：把上面的完整输出 + 两个服务的控制台日志一起发回来。" -ForegroundColor Yellow
Write-Host ""
Write-Host "常见报错对照：" -ForegroundColor DarkGray
Write-Host "  401 / Authentication Fails        -> DEEPSEEK_API_KEY 没设或不对" -ForegroundColor DarkGray
Write-Host "  404 且日志里 URL 少了/多了 /v1     -> 改 `$env:DEEPSEEK_BASE_URL" -ForegroundColor DarkGray
Write-Host "  400 提到 tool_choice / thinking   -> DeepSeek 思考模式与工具调用冲突" -ForegroundColor DarkGray
Write-Host "  500 且日志报 TokenStream/onNext   -> 还有 API 名字没改过来，发我" -ForegroundColor DarkGray
