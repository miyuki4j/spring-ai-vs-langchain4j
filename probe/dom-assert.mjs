#!/usr/bin/env node
/**
 * 在**真实渲染后**的 DOM 上做数值断言。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────
 * 截图像素肉眼看图会误判（本项目就曾把满格的时间轴看成"只填了 68%"），
 * 凡涉及百分比 / 坐标 / 归一化的渲染，都必须把值抠出来算一遍。
 * 之前的做法是 Chrome 的 `--dump-dom`，但**本机 Chrome 152 上它已经不产出了**：
 * `--headless=new` / `--headless=old` / `--headless` / 带不带 `--virtual-time-budget`
 * 五种组合全是 0 字节 stdout（同机 `--screenshot` 正常，所以不是 Chrome 整体不可用）。
 *
 * 于是换成 CDP（Chrome DevTools Protocol）：仍然用 headless Chrome 渲染，
 * 但通过 `Runtime.evaluate` 直接在页面里求值 —— 比 dump-dom 更强，因为可以
 * 返回结构化数据（`returnByValue`），不必再拿正则去啃 HTML 字符串。
 *
 * ── 为什么没有依赖 ──────────────────────────────────────────────────
 * Node 22 自带全局 `WebSocket` 与 `fetch`，所以 CDP 客户端可以手写，
 * 不用装 puppeteer / playwright。脚本只用标准库，跑法：
 *
 *   node probe/dom-assert.mjs --url "file:///.../index.html?demo=1" --expr "1+1"
 *   node probe/dom-assert.mjs --url "..." --expr "JSON.stringify([...document.querySelectorAll('.tab')].map(t=>t.textContent))"
 *   node probe/dom-assert.mjs --url "..." --settle 8000 --expr-file probe/assert-mcp-tab.js
 *
 * 产物：把表达式的求值结果打印到 stdout（对象会自动 JSON 序列化）。
 *
 * 退出码：0 = 求值成功；1 = 求值抛异常或拿不到页面；2 = 参数错。
 *
 * ⚠️ 必须给独立 `--user-data-dir`：本机常驻 Chrome 实例，不加的话新进程会被
 *    「转交」给现有会话，headless 静默失效（`--version` 都拿不到输出）。
 */

import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';

// ── 参数 ────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const out = { settle: 2500, timeout: 30000 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === '--url') out.url = next();
    else if (a === '--expr') out.expr = next();
    else if (a === '--expr-file') out.exprFile = next();
    else if (a === '--settle') out.settle = Number(next());
    else if (a === '--timeout') out.timeout = Number(next());
    else if (a === '--chrome') out.chrome = next();
    else if (a === '--quiet') out.quiet = true;
    else if (a === '-h' || a === '--help') out.help = true;
  }
  return out;
}

const HELP = `用法: node probe/dom-assert.mjs --url <url> (--expr <js> | --expr-file <path>) [选项]

  --url        要打开的地址（file:// 或 http://）
  --expr       在页面里求值的表达式；返回值会被 JSON 序列化后打印
  --expr-file  从文件读表达式（长脚本用这个，别在命令行里塞几十行）
  --settle     打开页面后等多久再求值，毫秒（默认 2500；页面里有 async 自动跑时调大）
  --timeout    整体超时，毫秒（默认 30000）
  --chrome     Chrome 可执行文件路径（默认自动探测）
  --quiet      只打印结果，不打诊断信息

退出码：0 成功 / 1 求值失败 / 2 参数错`;

// ── 小工具 ──────────────────────────────────────────────────────────

const sleep = ms => new Promise(r => setTimeout(r, ms));

/** 找一个空闲端口给 CDP 用 —— 写死 9222 会撞上本机其它调试实例。 */
function freePort() {
  return new Promise((resolve, reject) => {
    const srv = createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

function detectChrome(given) {
  const candidates = [
    given,
    process.env.CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  ].filter(Boolean);
  return candidates[0];
}

// ── CDP 客户端（手写，够用就好）─────────────────────────────────────

async function waitForTarget(port, timeout) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/list`);
      const list = await res.json();
      // 只挑 page 类型的 target，且必须带 webSocketDebuggerUrl
      const page = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) return page;
    } catch (_) { /* DevTools 还没起来，继续等 */ }
    await sleep(200);
  }
  throw new Error(`等不到 CDP target（${timeout}ms）——Chrome 是否真的起了？`);
}

/**
 * 发一条 CDP 命令并等它的回包。
 *
 * 这里刻意只实现「一次请求/一次响应」的最小闭环：需要连发多条命令的场景
 * 用 `Runtime.evaluate` 里的一个表达式就能覆盖，不必把 CDP 会话做得更复杂。
 */
function cdpCommand(wsUrl, method, params, timeout) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const timer = setTimeout(() => {
      try { ws.close(); } catch (_) { /* 忽略 */ }
      reject(new Error(`CDP 命令超时：${method}`));
    }, timeout);

    ws.addEventListener('open', () => {
      ws.send(JSON.stringify({ id: 1, method, params }));
    });
    ws.addEventListener('message', ev => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (_) { return; }
      // 忽略事件推送（没有 id 的那些），只认我们这条命令的回包
      if (msg.id !== 1) return;
      clearTimeout(timer);
      try { ws.close(); } catch (_) { /* 忽略 */ }
      if (msg.error) reject(new Error(`CDP 错误：${JSON.stringify(msg.error)}`));
      else resolve(msg.result);
    });
    ws.addEventListener('error', () => {
      clearTimeout(timer);
      reject(new Error('CDP WebSocket 出错'));
    });
  });
}

// ── 主流程 ──────────────────────────────────────────────────────────

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) { console.log(HELP); return 0; }
  if (!args.url) { console.error('缺 --url\n\n' + HELP); return 2; }

  if (!args.expr && !args.exprFile) { console.error('缺 --expr 或 --expr-file\n\n' + HELP); return 2; }
  const expression = args.exprFile
    ? readFileSync(args.exprFile, 'utf8')
    : args.expr;

  const chrome = detectChrome(args.chrome);
  const port = await freePort();
  const profile = mkdtempSync(join(tmpdir(), 'ai4j-cdp-'));

  const child = spawn(chrome, [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--allow-file-access-from-files',
    `--user-data-dir=${profile}`,
    `--remote-debugging-port=${port}`,
    args.url
  ], { stdio: 'ignore' });

  const cleanup = () => {
    try { child.kill(); } catch (_) { /* 忽略 */ }
    try { rmSync(profile, { recursive: true, force: true }); } catch (_) { /* 忽略 */ }
  };

  try {
    const page = await waitForTarget(port, args.timeout);

    // 页面里的 async 自动跑（?auto=1）需要时间；等一会儿再求值，
    // 否则取到的是骨架屏中间态 —— 这类"太快"导致的假结果很难发现。
    await sleep(args.settle);

    const result = await cdpCommand(page.webSocketDebuggerUrl, 'Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true
    }, args.timeout);

    if (result.exceptionDetails) {
      const ex = result.exceptionDetails;
      const text = (ex.exception && (ex.exception.description || ex.exception.value)) || ex.text;
      console.error('[X] 页面里求值抛异常：' + text);
      return 1;
    }

    const value = result.result && result.result.value;
    if (!args.quiet) {
      console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2));
    }

    // 如果表达式顺手算了断言（返回对象里带 asserts 数组），就把勾选清单也打出来，
    // 并让退出码反映结果 —— 这样调用脚本里直接判 LASTEXITCODE 就行，
    // 不必再写一段解析 JSON 的胶水代码。
    const asserts = value && Array.isArray(value.asserts) ? value.asserts : null;
    if (asserts) {
      if (!args.quiet) console.log('');
      for (const a of asserts) {
        console.log(`  [${a.ok ? 'OK' : 'X '}] ${a.name}` + (a.detail ? `  —— ${a.detail}` : ''));
      }
      const bad = asserts.filter(a => !a.ok);
      console.log('');
      console.log(bad.length
        ? `结论：${bad.length}/${asserts.length} 项未通过`
        : `结论：全部通过（${asserts.length} 项）`);
      return bad.length ? 1 : 0;
    }
    return 0;
  } catch (e) {
    console.error('[X] ' + e.message);
    return 1;
  } finally {
    cleanup();
  }
}

main().then(code => process.exit(code));
