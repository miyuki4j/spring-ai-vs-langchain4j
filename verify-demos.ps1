<#
  验证 spring-ai-demo / langchain4j-demo 的样例端点。

  覆盖 11 条用例：
      P1-1..3  chat / stream / agent（工具调用）
      P2-1..3  chat / stream / chat（工具调用）
      P1-4..6  think / think(思考关) / think/stream —— 思考模式（reasoning_content）对照
      P2-4..5  think / think/stream

  用法（在本目录下）：
      pwsh -File .\verify-demos.ps1

  ⚠️ 本机（2026-09-16 核实）只装了 Windows PowerShell 5.1，没有 pwsh 7。
  5.1 默认按 GBK 解析无 BOM 的 .ps1，中文注释会把语法读崩（报“缺少右 }”这种假错误），
  所以本文件保存为 **UTF-8 带 BOM**，并改用：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-demos.ps1

  为什么用 curl.exe 而不是 Invoke-WebRequest：
      1. Windows PowerShell 里 curl 是 Invoke-WebRequest 的别名，要用 curl.exe 才是真 curl
      2. Invoke-WebRequest 会把流式响应整体缓冲，看不到逐字返回；curl.exe -N 才能看到 SSE 实时输出
#>

$ErrorActionPreference = "Continue"

# curl.exe 吐的是 UTF-8 字节流，而 5.1 默认按控制台代码页（GBK）解码原生命令输出，
# 结果就是模型回答里的中文全变乱码。这两行把解码/编码都钉成 UTF-8。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

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

# ---------------- 思考模式（reasoning_content）对照：README「下一步」第 4 条 ----------------
# 判定标准：reasoningChars 是思考内容的字符数，非 0 才说明真的拿到了 reasoning_content。

$thinkMsg = "What%20is%2013%20squared%3F%20Answer%20with%20the%20number%20only."

# P1-4 开思考：Spring AI 能拿到 DeepSeekAssistantMessage.getReasoningContent()
Invoke-Check -Name "P1-4  think  思考=开（期望 reasoningChars 非 0）" `
    -Url "$springAiBase/api/chat/think?thinking=true&message=$thinkMsg" `
    -Expect '"reasoningChars":\s*[1-9]\d*'

# P1-5 关思考：证明「请求层开关」真的生效（这条是 LangChain4j 做不到的）
Invoke-Check -Name "P1-5  think  思考=关（期望 reasoningChars:0）" `
    -Url "$springAiBase/api/chat/think?thinking=false&message=$thinkMsg" `
    -Expect '"reasoningChars":\s*0'

# P1-6 流式思考：R: 是思考分片，C: 是正式回答
Invoke-Check -Name "P1-6  think/stream 流式思考（期望 R: 分片）" `
    -Url "$springAiBase/api/chat/think/stream?thinking=true&message=$thinkMsg" `
    -Expect "data:R:"

# P2-4 非流式思考：AiMessage.thinking()，需要 return-thinking: true
Invoke-Check -Name "P2-4  think  思考（期望 reasoningChars 非 0）" `
    -Url "$lc4jBase/api/chat/think?message=$thinkMsg" `
    -Expect '"reasoningChars":\s*[1-9]\d*'

# P2-5 流式思考：TokenStream.onPartialThinking(...)
Invoke-Check -Name "P2-5  think/stream 流式思考（期望 R: 分片）" `
    -Url "$lc4jBase/api/chat/think/stream?message=$thinkMsg" `
    -Expect "data:R:"

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Yellow
Write-Host "若有 [X]：把上面的完整输出 + 两个服务的控制台日志一起发回来。" -ForegroundColor Yellow
Write-Host ""
Write-Host "常见报错对照：" -ForegroundColor DarkGray
Write-Host "  401 / Authentication Fails        -> DEEPSEEK_API_KEY 没设或不对" -ForegroundColor DarkGray
Write-Host "  404 且日志里 URL 少了/多了 /v1     -> 改 `$env:DEEPSEEK_BASE_URL" -ForegroundColor DarkGray
Write-Host "  400 提到 tool_choice / thinking   -> DeepSeek 思考模式与工具调用冲突" -ForegroundColor DarkGray
Write-Host "  500 且日志报 TokenStream/onNext   -> 还有 API 名字没改过来，发我" -ForegroundColor DarkGray
