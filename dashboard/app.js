/* ============================================================
   AI4J 对照台 —— 逻辑
   设计原则：所有时序数据（首字延迟、总耗时）都保留原始毫秒值，
   只在渲染时做归一化，且两条时间轴共用同一刻度 —— 否则"看起来更快"
   可能只是各自的条形被拉满了，属于骗自己的可视化。
   ============================================================ */

(() => {
  'use strict';

  // ---------------- 元信息 ----------------

  const META = {
    p1: { key: 'p1', name: 'Spring AI', version: '2.0.1' },
    p2: { key: 'p2', name: 'LangChain4j', version: '1.20.0' }
  };

  /**
   * 三个地址：两个后端，外加 **MCP server**。
   *
   * mcp 那一个是第三个进程（:8099），它不含大模型、也不依赖任何 AI 框架 ——
   * 页面要拿它当「第三方裸协议客户端」，才能做出「三方工具清单一致」这个实验。
   * 可用 ?mcp=… 覆盖，和其它两个地址一样。
   */
  const DEFAULT_BASE = { p1: 'http://localhost:8081', p2: 'http://localhost:8082', mcp: 'http://localhost:8099' };
  const HEALTH = { p1: null, p2: null };

  /** 工具返回值的特征值。回答里出现这些，说明工具真的被调用了。
   *  （模型不可能凭空知道 3214 这个写死的数字，所以是个可靠的信号。） */
  const TOOL_SIGNS = [/3,?214/, /1,?870/, /542/, /applied/i, /rolled-?back/i];

  // ---------------- 小工具 ----------------

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function ms(v) {
    if (v == null) return '—';
    return v < 1000 ? Math.round(v) + ' ms' : (v / 1000).toFixed(2) + ' s';
  }

  const enc = encodeURIComponent;

  /** 后端地址：URL 参数 > localStorage > 默认值。 */
  function base(key) {
    const qs = new URLSearchParams(location.search);
    const fromUrl = qs.get(key);
    if (fromUrl) {
      try { localStorage.setItem('ai4j.base.' + key, fromUrl); } catch (_) { /* 隐私模式 */ }
      return fromUrl.replace(/\/+$/, '');
    }
    try {
      const saved = localStorage.getItem('ai4j.base.' + key);
      if (saved) return saved.replace(/\/+$/, '');
    } catch (_) { /* 忽略 */ }
    return DEFAULT_BASE[key];
  }

  /** 回答渲染：先转义（防 XSS），再把工具特征值高亮出来。 */
  function answerHtml(text) {
    return esc(text).replace(/(3,?214|1,?870)/g, '<mark class="hit-num">$1</mark>');
  }

  function toolHit(text) {
    return TOOL_SIGNS.some(re => re.test(text));
  }

  // ---------------- 侧卡骨架 ----------------

  function shell(key, endpoint, bodyHtml) {
    const m = META[key];
    return `
      <header class="side-head">
        <span class="dot"></span>
        <span class="side-name">${esc(m.name)} <b>${esc(m.version)}</b></span>
        <code class="side-ep" title="${esc(endpoint)}">${esc(endpoint)}</code>
      </header>
      <div class="side-body">${bodyHtml}</div>`;
  }

  const SKELETON = `<div class="skeleton">
      <div class="sk-line"></div><div class="sk-line"></div><div class="sk-line"></div>
    </div>`;

  function showIdle(el, key, endpoint) {
    el.innerHTML = shell(key, endpoint, '<p class="muted" style="margin:0">等待发送…</p>');
  }

  function showLoading(el, key, endpoint) {
    el.innerHTML = shell(key, endpoint, SKELETON);
  }

  function showError(el, key, endpoint, message) {
    el.innerHTML = shell(key, endpoint, `
      <div class="verdict err">请求失败</div>
      <div class="answer" style="font-size:12.5px;color:var(--text-2)">
        ${esc(message)}
        <br><br>
        排查方向：后端是否已启动（Spring AI 看 8081、LangChain4j 看 8082）、
        页面来源是否在后端的 CORS 放行名单里。
      </div>`);
  }

  // ---------------- SSE 解析 ----------------

  /**
   * 用 fetch + ReadableStream 读 SSE，而不是 EventSource。
   * 原因：EventSource 只能 GET、不能自定义头、无法做统一的中断处理，
   * 而且拿不到"帧到达时刻"—— 我们恰恰要用它算首字延迟。
   *
   * @param onChunk 每收到一个数据帧回调一次（已还原成原始文本片段）
   */
  async function streamSSE(url, onChunk) {
    const res = await fetch(url, { headers: { Accept: 'text/event-stream' } });
    if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
    if (!res.body) throw new Error('响应没有可读流（浏览器不支持或连接被代理截断）');

    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });

      // SSE 事件之间用空行分隔；最后一段可能被截断，留到下一轮
      const parts = buf.split(/\r?\n\r?\n/);
      buf = parts.pop();
      for (const part of parts) {
        const data = extractData(part);
        if (data !== null) onChunk(data);
      }
    }
    // 收尾：处理没有以空行结束的最后一帧
    const tail = extractData(buf);
    if (tail !== null) onChunk(tail);
  }

  /** 从一个 SSE 事件块里取出 data 字段。多行 data 按规范用换行拼接。 */
  function extractData(block) {
    const lines = block.split(/\r?\n/);
    const out = [];
    for (const line of lines) {
      if (line.startsWith('data:')) out.push(line.slice(5).replace(/^ /, ''));
    }
    return out.length ? out.join('\n') : null;
  }

  // ---------------- 页签切换 ----------------

  function bindTabs() {
    $$('.tab').forEach(tab => {
      tab.addEventListener('click', () => {
        $$('.tab').forEach(t => t.classList.toggle('is-on', t === tab));
        const target = 'panel-' + tab.dataset.panel;
        $$('.panel').forEach(p => p.classList.toggle('is-on', p.id === target));
      });
    });
  }

  function bindPresets() {
    $$('.chip[data-fill]').forEach(chip => {
      chip.addEventListener('click', () => {
        const input = document.getElementById(chip.dataset.fill);
        if (input) { input.value = chip.dataset.val; input.focus(); }
      });
    });
  }

  /** 思考内容折叠 */
  function bindThinkToggle() {
    document.addEventListener('click', ev => {
      const head = ev.target.closest('.think-head');
      if (head) head.parentElement.classList.toggle('collapsed');
    });
  }

  // ---------------- 探活 + 配置 ----------------

  const CFG_LABELS = {
    app: '应用名', framework: '框架', bootVersion: 'Spring Boot', webStack: 'Web 栈',
    javaVersion: 'JDK 版本', port: '端口', model: '模型名',
    thinking: '思考开关（全局）', modelStarter: '模型接入方式',
    baseUrl: 'base-url', returnThinking: 'return-thinking', reasoningEffort: 'reasoning-effort'
  };

  async function refreshHealth() {
    for (const key of ['p1', 'p2']) {
      const svc = $(`#svc-${key}`);
      const cfgEl = $(`#ref-cfg-${key}`);
      try {
        const res = await fetch(`${base(key)}/api/health`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const j = await res.json();
        HEALTH[key] = j;

        svc.className = 'svc is-on';
        svc.innerHTML = `<span class="svc-dot"></span><span class="svc-text">${esc(j.framework)} · 在线</span>`;

        cfgEl.innerHTML = Object.entries(j).map(([k, v]) => {
          let cls = '';
          // 把「配了但其实是坑」和「配对了」的项直接标出来
          if (k === 'thinking' && String(v) === 'disabled') cls = 'hi-warn';
          if (k === 'returnThinking' && String(v) === 'true') cls = 'hi-ok';
          if (k === 'reasoningEffort' && String(v).startsWith('(')) cls = 'hi-warn';
          return `<div class="cfg-row">
              <span class="cfg-key">${esc(CFG_LABELS[k] ?? k)}</span>
              <span class="cfg-val ${cls}">${esc(v)}</span>
            </div>`;
        }).join('');
      }
      catch (e) {
        HEALTH[key] = null;
        svc.className = 'svc is-off';
        svc.innerHTML = `<span class="svc-dot"></span><span class="svc-text">${esc(META[key].name)} · 离线</span>`;
        cfgEl.innerHTML = `<p class="muted">未获取到：${esc(e.message)}。<br>
          若后端确实已启动，那就是 CORS 没放行本页来源。</p>`;
      }
    }

    // 第三个指示灯：MCP server。它**没有** /api/health（它根本不是本项目的后端，
    // 只是个按 MCP 协议对外提供工具的进程），所以用「按协议握一次手」来探活。
    // 好处是顺带能提前暴露「CORS 没放行 8099」这类问题 —— 否则要等到点 MCP 页签才发现。
    const mcpSvc = $('#svc-mcp');
    if (mcpSvc) {
      const ok = await probeMcpServer();
      mcpSvc.className = 'svc ' + (ok ? 'is-on' : 'is-off');
      mcpSvc.innerHTML = `<span class="svc-dot"></span><span class="svc-text">MCP server · ${ok ? '在线' : '离线'}</span>`;
    }

    // 两个后端都离线时，在页脚给一句明确指引 —— 否则第一次打开的人只看到一片「离线」，
    // 不知道界面本身可以直接预览（点右上角「演示模式」）。
    const note = $('#foot-note');
    if (note) {
      const usable = HEALTH.p1 || HEALTH.p2 || demoOn;
      note.textContent = usable
        ? '对照台是独立静态页，两个后端需各自开放 CORS'
        : '两个后端当前都没起 —— 想看界面效果，点右上角「演示模式」（用本地合成响应走同样的渲染路径）。';
    }
  }

  // ---------------- 页签 1：问答与工具调用 ----------------

  async function runQA() {
    const q = $('#qa-q').value.trim();
    if (!q) return;

    const jobs = [
      { key: 'p1', el: $('#qa-p1'), ep: '/api/chat/agent', path: '/api/chat/agent' },
      { key: 'p2', el: $('#qa-p2'), ep: '/api/chat', path: '/api/chat' }
    ];
    jobs.forEach(j => showLoading(j.el, j.key, j.ep));

    await Promise.all(jobs.map(async j => {
      const t0 = performance.now();
      try {
        const res = await fetch(`${base(j.key)}${j.path}?message=${enc(q)}`);
        const text = await res.text();
        const cost = performance.now() - t0;
        if (!res.ok) { showError(j.el, j.key, j.ep, `HTTP ${res.status} — ${text.slice(0, 200)}`); return; }

        const hit = toolHit(text);
        const verdict = hit
          ? `<div class="verdict hit">已命中工具返回值</div>`
          : `<div class="verdict miss">未见工具返回值特征（可能模型选择直接回答）</div>`;

        j.el.innerHTML = shell(j.key, j.ep, `
          ${verdict}
          <div class="answer">${answerHtml(text)}</div>
          <div class="meta">
            <span>耗时 <b>${ms(cost)}</b></span>
            <span>HTTP <b>${res.status}</b></span>
            <span>响应长度 <b>${text.length}</b> 字符</span>
          </div>`);
      }
      catch (e) {
        showError(j.el, j.key, j.ep, e.message);
      }
    }));
  }

  // ---------------- 页签 2：流式输出 ----------------

  async function runStream() {
    const q = $('#st-q').value.trim();
    if (!q) return;

    const jobs = [
      { key: 'p1', el: $('#st-p1'), ep: '/api/chat/stream' },
      { key: 'p2', el: $('#st-p2'), ep: '/api/chat/stream' }
    ];
    jobs.forEach(j => showLoading(j.el, j.key, j.ep));

    const results = await Promise.all(jobs.map(j => streamOne(j, q)));

    // 两条时间轴共用同一刻度：谁的总耗时更长，谁就是 100%
    const scale = Math.max(...results.filter(r => r && !r.failed).map(r => r.total), 1);
    results.forEach((r, i) => {
      if (!r || r.failed) return;
      const box = jobs[i].el.querySelector('.tl-slot');
      if (box) box.innerHTML = timelineHtml(r, scale);
    });
  }

  async function streamOne(job, q) {
    job.el.innerHTML = shell(job.key, job.ep, `
      <div class="answer cursor"></div>
      <div class="tl-slot"><p class="muted" style="margin:10px 0 0">
        时间轴要等两侧都流完，再用同一刻度绘制 —— 否则「看起来更快」可能只是各自把条拉满了。</p></div>
      <div class="meta" style="visibility:hidden"></div>`);

    const answerEl = job.el.querySelector('.answer');
    const metaEl = job.el.querySelector('.meta');

    const t0 = performance.now();
    let firstAt = null, frames = 0, chars = 0;

    try {
      await streamSSE(`${base(job.key)}${job.ep}?message=${enc(q)}`, chunk => {
        if (firstAt === null) firstAt = performance.now() - t0;
        frames++;
        chars += chunk.length;
        answerEl.textContent += chunk;
      });
    }
    catch (e) {
      showError(job.el, job.key, job.ep, e.message);
      return { failed: true };
    }

    const total = performance.now() - t0;
    answerEl.classList.remove('cursor');
    metaEl.style.visibility = 'visible';
    metaEl.innerHTML = `
      <span>首字 <b>${ms(firstAt)}</b></span>
      <span>完成 <b>${ms(total)}</b></span>
      <span>数据帧 <b>${frames}</b></span>
      <span>总字符 <b>${chars}</b></span>`;

    return { firstAt: firstAt ?? total, total, frames, chars };
  }

  function timelineHtml(r, scale) {
    const waitPct = (r.firstAt / scale) * 100;
    const flowPct = (Math.max(0, r.total - r.firstAt) / scale) * 100;
    return `
      <div class="tl">
        <div class="tl-title">时间轴（两侧同一刻度，满格 = ${ms(scale)}）</div>
        <div class="tl-track">
          <span class="tl-wait" style="width:${waitPct.toFixed(2)}%"></span>
          <span class="tl-flow" style="width:${flowPct.toFixed(2)}%"></span>
        </div>
        <div class="tl-legend">
          <span>等待首字 ${ms(r.firstAt)}</span>
          <span>流式输出 ${ms(r.total - r.firstAt)}</span>
        </div>
      </div>`;
  }

  // ---------------- 页签 3：思考模式 ----------------

  async function runThink() {
    const q = $('#tk-q').value.trim();
    if (!q) return;
    const thinking = $('input[name="tk-thinking"]:checked').value;

    const jobs = [
      {
        key: 'p1', el: $('#tk-p1'), ep: `/api/chat/think?thinking=${thinking}`,
        url: `${base('p1')}/api/chat/think?message=${enc(q)}&thinking=${thinking}`
      },
      {
        key: 'p2', el: $('#tk-p2'), ep: '/api/chat/think',
        url: `${base('p2')}/api/chat/think?message=${enc(q)}`
      }
    ];
    jobs.forEach(j => showLoading(j.el, j.key, j.ep));

    const results = [];
    await Promise.all(jobs.map(async (j, i) => {
      const t0 = performance.now();
      try {
        const res = await fetch(j.url);
        const text = await res.text();
        const cost = performance.now() - t0;
        if (!res.ok) { showError(j.el, j.key, j.ep, `HTTP ${res.status} — ${text.slice(0, 200)}`); results[i] = null; return; }
        const data = JSON.parse(text);
        results[i] = { ...data, cost };
        j.el.innerHTML = shell(j.key, j.ep, `
          ${thinkBoxHtml(data.reasoningContent, data.reasoningChars)}
          <div class="answer">${esc(data.answer)}</div>
          <div class="meta">
            <span>耗时 <b>${ms(cost)}</b></span>
            <span>思考字符 <b>${data.reasoningChars ?? 0}</b></span>
          </div>`);
      }
      catch (e) {
        showError(j.el, j.key, j.ep, e.message);
        results[i] = null;
      }
    }));

    renderCost(results[0], results[1]);
  }

  function thinkBoxHtml(text, len) {
    if (!text) {
      return `
        <div class="think-box collapsed">
          <div class="think-head">
            <span class="think-arrow">▼</span>
            <span>思考内容 reasoning_content</span>
            <span class="think-len">未返回</span>
          </div>
          <div class="think-body"></div>
        </div>`;
    }
    return `
      <div class="think-box">
        <div class="think-head">
          <span class="think-arrow">▼</span>
          <span>思考内容 reasoning_content</span>
          <span class="think-len">${len ?? text.length} 字符</span>
        </div>
        <div class="think-body">${esc(text)}</div>
      </div>`;
  }

  /** 把两侧的 token 用量放在同一刻度上画条形图 —— 柱子长度直接可比。 */
  function renderCost(a, b) {
    const box = $('#tk-cost-body');
    if (!a && !b) {
      box.innerHTML = '<p class="muted">两侧都没有返回数据。</p>';
      return;
    }

    const pick = r => {
      const t = r && r.tokens;
      return t ? [t.prompt, t.completion, t.total].map(v => (v == null ? null : v)) : null;
    };
    const ta = pick(a), tb = pick(b);

    const all = [...(ta ?? []), ...(tb ?? [])].filter(v => v != null);
    const max = all.length ? Math.max(...all) : 1;

    const barHtml = (label, val) => `
      <div class="bar-row">
        <span class="bar-label">${label}</span>
        <span class="bar-track"><i class="bar-fill" style="width:${val == null ? 0 : (val / max) * 100}%"></i></span>
        <span class="bar-val">${val == null ? '—' : val}</span>
      </div>`;

    const names = ['prompt', 'completion', 'total'];

    const col = (t, cls) => `
      <div class="cfg-col ${cls}">
        ${t ? t.map((v, i) => barHtml(names[i], v)).join('')
            : '<p class="muted" style="margin:0">该侧未返回 token 用量</p>'}
      </div>`;

    // reasoningChars 的结论得按实际值判断，不能写死一句话
    const reasoningNote = (() => {
      if (!a || !b) return '有一侧没返回数据';
      const x = a.reasoningChars ?? 0, y = b.reasoningChars ?? 0;
      if (x === 0 && y === 0) return '两侧都没拿到思考内容 —— 检查 return-thinking 配置';
      if (x === 0) return 'Spring AI 侧没拿到（请求层的思考开关可能被关掉了）';
      if (y === 0) return 'LangChain4j 侧没拿到 —— 检查 return-thinking: true';
      return '两侧都拿到了思考内容';
    })();
    const rc = r => (r == null ? '—' : (r.reasoningChars ?? 0));

    box.innerHTML = `
      <div class="cfg-grid" style="margin-bottom:16px">
        ${col(ta, 'p1')}
        ${col(tb, 'p2')}
      </div>
      <table class="tbl">
        <thead>
          <tr><th>指标</th><th>Spring AI</th><th>LangChain4j</th><th>看差异</th></tr>
        </thead>
        <tbody>
          <tr>
            <td>reasoningChars</td>
            <td>${rc(a)}</td>
            <td>${rc(b)}</td>
            <td>${reasoningNote}</td>
          </tr>
          <tr>
            <td>prompt token</td>
            <td>${ta?.[0] ?? '—'}</td>
            <td>${tb?.[0] ?? '—'}</td>
            <td>LangChain4j 侧通常大得多：@AiService 每次都把工具定义一起发出去</td>
          </tr>
          <tr>
            <td>total token</td>
            <td>${ta?.[2] ?? '—'}</td>
            <td>${tb?.[2] ?? '—'}</td>
            <td>${diffText(ta?.[2], tb?.[2])}</td>
          </tr>
          <tr>
            <td>回答</td>
            <td>${esc(truncate(a?.answer))}</td>
            <td>${esc(truncate(b?.answer))}</td>
            <td>两侧应当是同一个答案</td>
          </tr>
        </tbody>
      </table>
      <p class="muted" style="margin:12px 0 0">
        completion 里含思考 token，所以思考开着时它会明显偏大。
        把上面 Spring AI 侧的开关从「开」切成「关」再发一次，total 会明显下降；
        LangChain4j 侧没有这个开关可切 —— 这就是两侧最实质的差别。
      </p>`;
  }

  function truncate(s, n = 60) {
    if (s == null) return '—';
    const t = String(s).replace(/\s+/g, ' ').trim();
    return t.length > n ? t.slice(0, n) + '…' : t;
  }

  function diffText(x, y) {
    if (x == null || y == null) return '有一侧没返回用量';
    if (x === y) return '完全一致';
    const d = y - x;
    const pct = x === 0 ? null : Math.round(Math.abs(d) / x * 100);
    return `LangChain4j ${d > 0 ? '多' : '少'} ${Math.abs(d)}${pct == null ? '' : `（约 ${pct}%）`}`;
  }

  // ---------------- 页签 4：对话记忆 ----------------

  /** 第 4 轮回忆里应该出现的三个事实。 */
  const MEM_FACTS = ['3214', '1870', 'LOONG-7749'];

  /**
   * 记忆锚点：只有它是**工具产不出来**的值。
   *
   * 另外两个数字（3214 / 1870）是工具查得到的 —— 模型在回忆轮完全可以自己再调一次
   * 工具把它们查回来。实测就真发生了：窗口把第 1 轮裁掉之后，回答里照样出现了 3214。
   * 所以判定记忆是否生效**只能看锚点**，另外两个命中与否跟记忆没关系。
   */
  const MEM_ANCHOR = 'LOONG-7749';

  /**
   * 把一次运行摊平成「逐轮列表」。
   *
   * 这里顺手给每条打上 _kind 标记：后端返回体里 recall / control 是**顶层字段**
   * （`d.recall` / `d.control`），Turn 记录本身没有 recall/control 布尔值 ——
   * 所以标记只能在这里打。早先版本直接读 t.control / t.recall，结果真实运行时
   * 对照轮渲染成了「第 -1 轮」（round = -1），只有演示模式的合成数据是对的。
   */
  function memRows(d) {
    const rows = d.turns.map(t => ({ ...t, _kind: 'round' }));
    rows.push({ ...d.recall, _kind: 'recall' });
    if (d.control) rows.push({ ...d.control, _kind: 'control' });
    return rows;
  }

  function factsHtml(ev) {
    const hits = new Set((ev && ev.hits) || []);
    return MEM_FACTS.map(f => {
      // 命中的标绿、缺失的标红划掉 —— 一眼能看出丢了哪几个事实
      const cls = hits.has(f) ? 'fact hit' : 'fact miss';
      const tag = f === MEM_ANCHOR ? '<i class="fact-tag">锚点</i>' : '';
      return `<span class="${cls}">${esc(f)}${tag}</span>`;
    }).join('');
  }

  function memorySideHtml(d) {
    const rows = memRows(d);
    // 每轮那条细进度条按「两侧所有轮次里的最大 prompt」归一化，
    // 所以两侧的条子可以直接比长短，也能一眼看出哪一轮被工具抬高了
    const maxPrompt = Math.max(1, ...rows.map(t => (t.tokens && t.tokens.prompt) || 0));

    const head = `
      <div class="mm-window">记忆窗口 <b>${d.windowMaxMessages}</b> 条 · ${esc(d.windowSource)}</div>
      <div class="verdict ${d.evidence.anchorHit ? 'hit' : 'miss'}">
        ${d.evidence.anchorHit
          ? '锚点 LOONG-7749 被复述出来 —— 记忆确实生效'
          : '锚点 LOONG-7749 没被复述出来 —— 记忆没生效，或被窗口裁掉了'}
      </div>
      <div class="mm-facts">${factsHtml(d.evidence)}</div>`;

    return head + rows.map(t => memRoundHtml(t, maxPrompt)).join('')
      + `<p class="muted" style="margin:10px 0 0">${esc(d.note)}</p>`;
  }

  function memRoundHtml(t, maxPrompt) {
    const tk = t.tokens || {};
    const p = tk.prompt ?? 0;
    const width = maxPrompt ? (p / maxPrompt) * 100 : 0;

    const cls = t._kind === 'control' ? 'mm-round control'
              : t._kind === 'recall' ? 'mm-round recall' : 'mm-round';
    const no = t._kind === 'control' ? '对照 · 全新会话（无记忆）'
             : t._kind === 'recall' ? '第 4 轮 · 回忆'
             : `第 ${t.round} 轮`;

    const roles = (t.memoryRoles || [])
      .map(r => `<span class="role role-${esc(r)}">${esc(r)}</span>`).join('');
    const firstUser = t.memoryFirstUser
      ? `<span class="mm-first" title="记忆里最早那条 user 消息">最早：${esc(t.memoryFirstUser)}</span>`
      : '';

    return `
      <div class="${cls}">
        <div class="mm-round-head">
          <span class="mm-round-no">${esc(no)}</span>
          <span class="mm-round-q">${esc(t.question)}</span>
        </div>
        <div class="mm-round-a">${esc(t.answer)}</div>
        <div class="mm-bar"><i style="width:${width.toFixed(2)}%"></i></div>
        <div class="mm-round-meta">
          <span>记忆 <b>${t.memoryMessages}</b> 条</span>
          <span>prompt <b>${tk.prompt ?? '—'}</b></span>
          <span>total <b>${tk.total ?? '—'}</b></span>
          <span>耗时 <b>${ms(t.elapsedMs)}</b></span>
        </div>
        ${roles ? `<div class="mm-roles">${roles}</div>` : ''}
        ${firstUser ? `<div class="mm-roles">${firstUser}</div>` : ''}
      </div>`;
  }

  async function runMemory() {
    const cid = ($('#mm-cid').value || '').trim() || 'demo-a';
    const win = $('#mm-window').value;

    const jobs = [
      { key: 'p1', el: $('#mm-p1'), ep: '/api/chat/memory' },
      { key: 'p2', el: $('#mm-p2'), ep: '/api/chat/memory' }
    ];
    jobs.forEach(j => showLoading(j.el, j.key, j.ep));

    const results = [];
    await Promise.all(jobs.map(async (j, i) => {
      let url = `${base(j.key)}/api/chat/memory?conversationId=${enc(cid)}&reset=true`;
      if (win) url += `&maxMessages=${enc(win)}`;
      try {
        const res = await fetch(url);
        const text = await res.text();
        if (!res.ok) {
          showError(j.el, j.key, j.ep, `HTTP ${res.status} — ${text.slice(0, 200)}`);
          results[i] = null;
          return;
        }
        const data = JSON.parse(text);
        results[i] = data;
        j.el.innerHTML = shell(j.key, j.ep, memorySideHtml(data));
      }
      catch (e) {
        showError(j.el, j.key, j.ep, e.message);
        results[i] = null;
      }
    }));

    renderMemoryChart(results[0], results[1]);
    renderMemoryVerdict(results[0], results[1]);
  }

  /** 把轴上界取整到好看的刻度，免得最高点贴着顶边。 */
  function niceMax(v) {
    const step = v > 2000 ? 500 : v > 800 ? 200 : v > 300 ? 100 : v > 100 ? 20 : 10;
    return Math.ceil(v / step) * step;
  }

  /**
   * 增长图：上半部分是 prompt token 折线（两侧共用同一 y 轴），
   * 下半部分是每轮送进 prompt 的历史消息条数（分组柱）。
   *
   * 为什么非要用同一刻度：如果各画各的，"增长"会变成两条都贴顶的线，
   * 什么也看不出来 —— 这和流式那页时间轴归一化是同一个道理。
   *
   * 另外这张图会顺手暴露一个反直觉现象：prompt 不是逐轮单调上升的，
   * 触发工具的那一轮会被工具定义和工具结果回灌抬高一大截。见下方图注。
   */
  function renderMemoryChart(a, b) {
    const box = $('#mm-chart');
    if (!a && !b) {
      box.innerHTML = '<p class="muted">两侧都没有返回数据。</p>';
      return;
    }

    const series = [
      { cls: 'p1', name: META.p1.name, rows: a ? memRows(a) : [] },
      { cls: 'p2', name: META.p2.name, rows: b ? memRows(b) : [] }
    ];
    const ref = series[0].rows.length ? series[0].rows : series[1].rows;
    if (!ref.length) {
      box.innerHTML = '<p class="muted">没拿到轮次数据。</p>';
      return;
    }

    // 标签按 _kind 判定，不能按位置 —— memRows() 末尾会再补一个「对照」行，
    // 按位置判会把回忆轮标成「第 4 轮」、把对照轮标成「回忆」。
    const labels = ref.map((t, i) =>
      t._kind === 'control' ? '对照'
      : t._kind === 'recall' ? '回忆'
      : `第${i + 1}轮`);

    const pVals = series.flatMap(s => s.rows.map(t => (t.tokens && t.tokens.prompt) || 0));
    const mVals = series.flatMap(s => s.rows.map(t => t.memoryMessages || 0));
    const pMax = niceMax(Math.max(1, ...pVals));
    const mMax = Math.max(2, ...mVals);

    const W = 680, H = 386;
    const L = 64, R = W - 24;
    const n = labels.length;
    const band = (R - L) / n;
    const cx = i => L + band * (i + 0.5);

    const top = { y0: 40, y1: 208 };
    const bot = { y0: 264, y1: 352 };

    const yOf = (v, box0, max) => box0.y1 - (v / max) * (box0.y1 - box0.y0);

    const grid = (box0, max, step) => {
      let out = '';
      for (let v = 0; v <= max + 1e-9; v += step) {
        const y = yOf(v, box0, max);
        out += `<line class="g-grid" x1="${L}" y1="${y.toFixed(1)}" x2="${R}" y2="${y.toFixed(1)}"/>`;
        out += `<text class="g-val" x="${L - 8}" y="${(y + 3.5).toFixed(1)}" text-anchor="end">${v}</text>`;
      }
      return out;
    };

    const axis = box0 =>
      `<line class="g-axis" x1="${L}" y1="${box0.y1}" x2="${R}" y2="${box0.y1}"/>`;

    const xLabels = labels.map((lb, i) =>
      `<text class="g-xlab" x="${cx(i).toFixed(1)}" y="${H - 12}" text-anchor="middle">${esc(lb)}</text>`
    ).join('');

    // 折线 + 端点 + 数值
    let lines = '', dots = '';
    series.forEach(s => {
      if (s.rows.length < 2) return;
      const pts = s.rows.map((t, i) => {
        const v = (t.tokens && t.tokens.prompt) || 0;
        return `${cx(i).toFixed(1)},${yOf(v, top, pMax).toFixed(1)}`;
      }).join(' ');
      lines += `<polyline class="g-${s.cls}-line" points="${pts}"/>`;

      // 有工具轮时数值标签容易叠在一起，所以交替上下摆放
      s.rows.forEach((t, i) => {
        const v = (t.tokens && t.tokens.prompt) || 0;
        const y = yOf(v, top, pMax);
        const dy = (i % 2 === 0) ? -9 : 16;
        dots += `<circle class="g-${s.cls}-dot" cx="${cx(i).toFixed(1)}" cy="${y.toFixed(1)}" r="3.6"/>`;
        dots += `<text class="g-${s.cls}-val" x="${cx(i).toFixed(1)}" y="${(y + dy).toFixed(1)}" `
              + `text-anchor="middle">${v}</text>`;
      });
    });

    // 记忆条数分组柱
    const bw = Math.min(30, band * 0.28);
    let bars = '';
    for (let i = 0; i < n; i++) {
      series.forEach((s, k) => {
        const t = s.rows[i];
        if (!t) return;
        const v = t.memoryMessages || 0;
        const h = (v / mMax) * (bot.y1 - bot.y0);
        const x = cx(i) + (k === 0 ? -bw - 2 : 2);
        bars += `<rect class="g-${s.cls}-bar" x="${x.toFixed(1)}" y="${(bot.y1 - h).toFixed(1)}" `
              + `width="${bw.toFixed(1)}" height="${h.toFixed(1)}" rx="2"/>`;
        if (v > 0) {
          bars += `<text class="g-${s.cls}-val" x="${(x + bw / 2).toFixed(1)}" `
                + `y="${(bot.y1 - h - 5).toFixed(1)}" text-anchor="middle">${v}</text>`;
        }
      });
    }

    const legend = series.filter(s => s.rows.length).map(s =>
      `<span class="lg"><i class="sw ${s.cls}"></i>${esc(s.name)}</span>`).join('');

    box.innerHTML = `
      <div class="growth-legend">${legend}
        <span class="muted" style="margin:0">两条线共用同一 y 轴（0 ～ ${pMax} token）</span>
      </div>
      <svg class="growth" viewBox="0 0 ${W} ${H}" role="img"
           aria-label="记忆条数与 prompt token 的逐轮增长">
        <text class="g-title" x="${L}" y="${top.y0 - 16}">prompt token（含系统提示 + 工具定义 + 历史消息）</text>
        ${grid(top, pMax, pMax / 4)}
        ${axis(top)}
        ${lines}
        ${dots}
        <text class="g-title" x="${L}" y="${bot.y0 - 16}">送进 prompt 的历史消息条数</text>
        ${grid(bot, mMax, Math.max(1, Math.ceil(mMax / 4)))}
        ${axis(bot)}
        ${bars}
        ${xLabels}
      </svg>
      <p class="muted" style="margin:12px 0 0">
        <b>看图注意两件事</b>：① 折线不是单调上升的 —— 第 1、3 轮问的是在线人数，触发工具，
        工具定义和工具结果一起回灌进 prompt，一次能多出四五百 token，
        远超几条历史消息的量级；第 2 轮只是「记住一个号」的纯对话轮，反而最低。
        所以「prompt 逐轮涨」这个直觉是错的，要看清记忆的开销，
        得拿<b>同一道题、同样不带工具</b>的两次调用来比（下半部分的柱子就是这个口径）。
        ② 换个小窗口再跑，柱子会被压到窗口上限 —— 那是记忆被裁剪的直接体现。
      </p>`;
  }

  function renderMemoryVerdict(a, b) {
    const box = $('#mm-verdict');
    if (!a && !b) {
      box.innerHTML = '<p class="muted">两侧都没有返回数据。</p>';
      return;
    }

    const cost = d => {
      if (!d || !d.control) return null;
      const pr = (d.recall.tokens && d.recall.tokens.prompt) ?? null;
      const pc = (d.control.tokens && d.control.tokens.prompt) ?? null;
      if (pr == null || pc == null) return null;
      const dh = d.recall.memoryMessages - d.control.memoryMessages;
      return { delta: pr - pc, hist: dh, per: dh > 0 ? (pr - pc) / dh : null };
    };
    const ca = cost(a), cb = cost(b);

    const s = d => d || {};
    const v = (x, f) => (x == null ? '—' : f(x));

    const hitText = d => (d ? `${(d.evidence.hits || []).length}/3` : '—');
    const anchorText = d => (d ? (d.evidence.anchorHit ? '命中' : '丢失') : '—');
    const tokSum = d => {
      if (!d) return null;
      return [...d.turns, d.recall].reduce((acc, t) => acc + ((t.tokens && t.tokens.total) || 0), 0);
    };

    box.innerHTML = `
      <table class="tbl">
        <thead>
          <tr><th>指标</th><th>Spring AI</th><th>LangChain4j</th><th>说明</th></tr>
        </thead>
        <tbody>
          <tr>
            <td><b>锚点命中</b></td>
            <td>${anchorText(a)}</td>
            <td>${anchorText(b)}</td>
            <td><b>判定记忆是否生效只看这一行</b>。锚点 LOONG-7749 工具产不出来，只可能来自记忆</td>
          </tr>
          <tr>
            <td>三个事实命中数</td>
            <td>${hitText(a)}</td>
            <td>${hitText(b)}</td>
            <td>仅供参考：3214 / 1870 工具能现查，模型在回忆轮可能自己又调了一次工具</td>
          </tr>
          <tr>
            <td>无记忆对照 · 锚点</td>
            <td>${a ? (a.evidence.controlHit ? '也说出来了' : '答不出') : '—'}</td>
            <td>${b ? (b.evidence.controlHit ? '也说出来了' : '答不出') : '—'}</td>
            <td>必须「答不出」，否则说明模型能蒙对，整个实验不成立</td>
          </tr>
          <tr>
            <td>记忆窗口</td>
            <td>${v(s(a).windowMaxMessages, x => x + ' 条')}</td>
            <td>${v(s(b).windowMaxMessages, x => x + ' 条')}</td>
            <td>P1 来自 <code>MessageWindowChatMemory</code> 默认值 20；P2 来自 <code>MemoryConfig</code> 手配值</td>
          </tr>
          <tr>
            <td>窗口换算成对话轮</td>
            <td>${a ? Math.floor(a.windowMaxMessages / 2) + ' 轮' : '—'}</td>
            <td>${b ? '≈1～4 轮（含 system 与 tool）' : '—'}</td>
            <td><b>「窗口大小」在两个框架里不是同一个东西</b>：P1 只存 user/assistant 两条一轮；
              P2 连 <code>system</code> 和工具轮的 <code>tool</code> 中间消息一起存，一轮能占 4~5 条</td>
          </tr>
          <tr>
            <td>回忆轮历史条数</td>
            <td>${v(a && a.recall.memoryMessages, String)}</td>
            <td>${v(b && b.recall.memoryMessages, String)}</td>
            <td>同样的窗口数字，LangChain4j 实到的条数更多</td>
          </tr>
          <tr>
            <td>回忆轮 prompt</td>
            <td>${v(a && a.recall.tokens && a.recall.tokens.prompt, String)}</td>
            <td>${v(b && b.recall.tokens && b.recall.tokens.prompt, String)}</td>
            <td>同一道题、无工具，含全部历史</td>
          </tr>
          <tr>
            <td>对照轮 prompt</td>
            <td>${v(a && a.control && a.control.tokens && a.control.tokens.prompt, String)}</td>
            <td>${v(b && b.control && b.control.tokens && b.control.tokens.prompt, String)}</td>
            <td>同一道题、无工具、<b>没有历史</b> —— 记忆开销的基准线</td>
          </tr>
          <tr>
            <td><b>记忆开销</b>（回忆 − 对照）</td>
            <td>${v(ca && ca.delta, x => '+' + x + ' token')}</td>
            <td>${v(cb && cb.delta, x => '+' + x + ' token')}</td>
            <td>同题同工具，唯一变量就是历史条数 —— 这是唯一干净的口径</td>
          </tr>
          <tr>
            <td>每条历史消息成本</td>
            <td>${v(ca && ca.per, x => x.toFixed(1) + ' token/条')}</td>
            <td>${v(cb && cb.per, x => x.toFixed(1) + ' token/条')}</td>
            <td>这个数字才是「记忆不是免费的」的量化答案</td>
          </tr>
          <tr>
            <td>4 轮 total 累计</td>
            <td>${v(tokSum(a), String)}</td>
            <td>${v(tokSum(b), String)}</td>
            <td>含工具轮与思考 token，仅供量级参考</td>
          </tr>
        </tbody>
      </table>
      <p class="muted" style="margin:14px 0 0">
        <b>结论怎么读</b>：只有「锚点命中」和「对照 · 锚点答不出」这两行同时成立，记忆才是真的生效了；
        缺一条实验就不成立。下面的 token 行说明的是代价 —— 历史每一轮都要重新发一遍，
        <b>并不因为「模型已经看过」就免费</b>。
      </p>`;
  }

  // ---------------- 页签 5：MCP 远程工具 ----------------

  /**
   * 期望的工具清单 —— 与 probe/check-mcp.py 里的 EXPECTED_TOOLS 保持一致。
   *
   * 写死在页面上的意义：让「三方清单一致」这句话**可判定**，
   * 而不是靠肉眼看三个列表长得像不像。
   */
  const MCP_EXPECTED = ['explain_effect_target', 'get_skill', 'list_skills', 'validate_condition'];

  /** 锚点：技能名与目标类型枚举。只存在于 mcp-skill-server 的 skills.json 里。 */
  const MCP_ANCHOR_NAME = '磐石';
  const MCP_ANCHOR_TARGET = 'REVEALED_AREA';

  /**
   * 浏览器**自己说一遍 MCP 协议**，拿 tools/list。
   *
   * <p>这是 {@code probe/check-mcp-server.py} 的 JS 版：不借助任何框架、不借助任何后端，
   * 直接在页面上完成 initialize → notifications/initialized → tools/list 三段握手。
   *
   * <p><b>为什么值得在页面上再写一遍？</b>「三方清单一致」这句话里的三方是
   * 「裸协议 / P1 / P2」，前两方用的都是框架封好的 {@code McpClient} ——
   * 只有这第三方是裸协议。它在页面上跑通，才说明这份清单**不是哪个框架的私有视图**，
   * 而是协议本身的内容。
   *
   * <p><b>两个必须踩对的细节</b>（和 Python 版一致）：
   * <ol>
   *   <li>{@code Accept} 要同时声明 {@code application/json} 与 {@code text/event-stream}，
   *       少一个可能被服务端按规范回 406；</li>
   *   <li>响应体**常常不是 JSON 而是 SSE** —— 得从 {@code data:} 行里取载荷。</li>
   * </ol>
   *
   * <p>还有一个只会在<b>浏览器</b>里暴露的坑：会话 id 走响应头 {@code Mcp-Session-Id}，
   * 而跨域响应里 JS <b>默认看不到</b>任何非简单响应头 —— 必须由服务端
   * {@code exposedHeaders} 显式暴露（见 {@code mcp-skill-server} 的 {@code WebConfig}）。
   * 否则这里永远拿到 {@code null}，表现为「initialize 成功、后续全部 400」这种很难查的错。
   */
  async function mcpToolsViaRawProtocol() {
    const url = base('mcp').replace(/\/+$/, '') + '/mcp';
    let sid = null;

    const post = async body => {
      const headers = {
        'Content-Type': 'application/json',
        // ⚠️ 少写 text/event-stream 就可能被回 406
        Accept: 'application/json, text/event-stream'
      };
      if (sid) headers['Mcp-Session-Id'] = sid;

      const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
      const got = res.headers.get('Mcp-Session-Id');
      if (got) sid = got;

      const text = await res.text();
      if (!res.ok) throw new Error(`HTTP ${res.status} — ${text.slice(0, 160)}`);
      return parseMcpBody(text);
    };

    await post({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        // 客户端提案一个版本，服务端支持就原样回显（不支持才推行自己的上限）。
        // 实测这个 server 的上限是 2025-11-25：请求 2025-06-18 就回 2025-06-18。
        // 它不实现现代协议的 server/discover，所以浏览器页也走不了那条新路径。
        protocolVersion: '2025-06-18', capabilities: {},
        clientInfo: { name: 'ai4j-dashboard', version: '1.0' }
      }
    });
    // 通知类消息没有 id。规范要求服务端回 202 且不带 JSON-RPC body，所以不解析它的返回值。
    await post({ jsonrpc: '2.0', method: 'notifications/initialized' });

    const body = await post({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
    return ((body.result && body.result.tools) || []).map(t => t.name).sort();
  }

  /** Streamable HTTP 的响应体：可能是纯 JSON，也可能是 SSE（真正的载荷在 {@code data:} 行里）。 */
  function parseMcpBody(text) {
    const raw = String(text || '').trim();
    if (!raw) return {};
    if (raw.startsWith('{')) return JSON.parse(raw);
    for (const line of raw.split(/\r?\n/)) {
      const t = line.trim();
      if (t.startsWith('data:')) return JSON.parse(t.slice(5).trim());
    }
    return {};
  }

  /** 只做一次握手探活，给顶部那个 MCP 指示灯用 —— 不调模型，不花钱。 */
  async function probeMcpServer() {
    try { await mcpToolsViaRawProtocol(); return true; }
    catch (_) { return false; }
  }

  /** 锚点命中判定：技能名与目标类型各算一项，**两个都中**才算真的查到了配置。 */
  function mcpHit(answer) {
    const a = answer || '';
    return {
      name: a.includes(MCP_ANCHOR_NAME),
      target: a.includes(MCP_ANCHOR_TARGET) || a.includes('已揭示')
    };
  }

  /** 锚点高亮：把锚点值在回答里标出来，命中与否一眼可见。 */
  function mcpAnswerHtml(text) {
    return esc(text)
      .replace(/磐石/g, '<mark class="hit-num">磐石</mark>')
      // 长的写在前面：正则的 | 从左往右试，写反了会把「已揭示的区域」切成「已揭示」+「的区域」
      .replace(/(REVEALED_AREA|已揭示的区域|已揭示)/g, '<mark class="hit-num">$1</mark>');
  }

  /** 一组（实验组或对照组）的卡片。 */
  function mcpCaseHtml(title, withMcp, d) {
    const tk = d.tokens || {};
    const hit = mcpHit(d.answer);
    // 实验组的期望是「两个都命中」；对照组的期望是「一个都不命中」——
    // 两个方向的期望相反，所以传递/不通过的判定也必须分开写。
    const pass = withMcp ? (hit.name && hit.target) : !(hit.name || hit.target);

    return `
      <div class="mm-round mcp-case ${withMcp ? 'mcp-on' : 'mcp-off'}">
        <div class="mm-round-head">
          <span class="mm-round-no">${esc(title)}</span>
          <span class="mm-round-q">withMcp=${withMcp}</span>
        </div>
        <div class="mm-round-a">${mcpAnswerHtml(d.answer)}</div>
        <div class="mcp-anchor">
          <span class="${hit.name ? 'fact hit' : 'fact miss'}">技能名「${esc(MCP_ANCHOR_NAME)}」</span>
          <span class="${hit.target ? 'fact hit' : 'fact miss'}">目标 ${esc(MCP_ANCHOR_TARGET)}</span>
          <span class="mcp-pass ${pass ? 'ok' : 'bad'}">${pass ? '符合预期' : '不符合预期'}</span>
        </div>
        <div class="mm-round-meta">
          <span>工具 <b>${d.toolCount ?? '—'}</b> 个</span>
          <span>prompt <b>${tk.prompt ?? '—'}</b></span>
          <span>total <b>${tk.total ?? '—'}</b></span>
          <span>耗时 <b>${ms(d.elapsedMs)}</b></span>
        </div>
      </div>`;
  }

  function mcpSideHtml(d) {
    const on = mcpHit(d.on.answer);
    const off = mcpHit(d.off.answer);
    const onHit = on.name && on.target;
    const offClean = !(off.name || off.target);
    const closed = onHit && offClean;

    return `
      <div class="verdict ${closed ? 'hit' : 'miss'}">
        ${closed
          ? '挂上答得出、不挂答不出 —— 锚点对照封闭'
          : '实验未封闭：对照组也答对了锚点（信息泄漏，或记忆串了槽），本次结果不能当证据'}
      </div>
      <div class="mm-window">远端工具 <b>${d.tools.length}</b> 个 · 每次真的去问一次 ${esc(base('mcp'))}/mcp</div>
      ${mcpCaseHtml('实验组 · 挂 MCP 工具', true, d.on)}
      ${mcpCaseHtml('对照组 · 只挂本地工具', false, d.off)}`;
  }

  async function fetchMcpAnswer(key, q, withMcp) {
    const res = await fetch(`${base(key)}/api/chat/mcp?message=${enc(q)}&withMcp=${withMcp}`);
    const text = await res.text();
    if (!res.ok) throw new Error(`HTTP ${res.status} — ${text.slice(0, 160)}`);
    return JSON.parse(text);
  }

  async function runMcpSide(key, q) {
    const el = $(`#mc-${key}`);
    const ep = '/api/chat/mcp';
    showLoading(el, key, ep);
    try {
      const tools = await (await fetch(`${base(key)}/api/chat/mcp/tools`)).json();
      // 两组**串行**跑，不并行：并行会让两次请求互相抢带宽，
      // 「实验组比对照组慢」这个耗时对照就没法读了。
      const on = await fetchMcpAnswer(key, q, true);
      const off = await fetchMcpAnswer(key, q, false);
      const data = { tools, on, off };
      el.innerHTML = shell(key, ep, mcpSideHtml(data));
      return { key, ok: true, tools, on, off };
    } catch (e) {
      showError(el, key, ep, e.message);
      return { key, ok: false, error: e.message, tools: [] };
    }
  }

  async function runMcp() {
    const q = ($('#mc-q').value || '').trim() || '技能 9001 叫什么名字？它作用于什么目标类型？';

    // 三方并行取。裸协议那一路失败不算致命（可能只是 8099 没起，或 CORS 没放行），
    // 但**必须在页面上如实说出失败原因** —— 不能悄悄少画一列，
    // 那等于把「三方一致」偷偷降级成「两方一致」还不告诉人。
    const rawP = mcpToolsViaRawProtocol().then(
      tools => ({ ok: true, tools }),
      e => ({ ok: false, tools: [], error: e.message }));
    const sidesP = Promise.all(['p1', 'p2'].map(k => runMcpSide(k, q)));

    const [raw, sides] = await Promise.all([rawP, sidesP]);
    renderMcpTools(raw, sides[0], sides[1]);
    renderMcpVerdict(raw, sides[0], sides[1]);
  }

  /** 三列并排的工具清单 —— 这页最有说服力的一张图。 */
  function renderMcpTools(raw, a, b) {
    const cols = [
      { name: '裸协议 · 浏览器直连 8099', sub: '页面自己说 JSON-RPC，不借助任何框架', res: raw },
      { name: 'P1 · Spring AI', sub: 'spring-ai-starter-mcp-client（只改 application.yml）', res: a },
      { name: 'P2 · LangChain4j', sub: 'langchain4j-mcp（手写 McpClient + McpToolProvider）', res: b }
    ];

    const lists = cols.map(c => (c.res && c.res.ok ? c.res.tools : null));
    const key0 = lists[0] ? lists[0].join('|') : null;
    const same = !!key0 && lists.every(l => l && l.join('|') === key0);

    $('#mc-tools').innerHTML = `
      <div class="mcp-grid">
        ${cols.map((c, i) => {
          const l = lists[i];
          const agree = l && key0 && l.join('|') === key0;
          const body = l
            ? `<div class="mcp-tools">${l.map(n => `<span class="mcp-tool">${esc(n)}</span>`).join('')}</div>`
            : `<div class="mcp-err">拿不到清单：${esc((c.res && c.res.error) || '未知原因')}</div>`;
          return `
            <div class="mcp-col ${l ? (agree ? 'ok' : 'bad') : 'bad'}">
              <div class="mcp-col-head">${esc(c.name)}</div>
              <div class="mcp-col-sub">${esc(c.sub)}</div>
              ${body}
            </div>`;
        }).join('')}
      </div>
      <div class="verdict ${same ? 'hit' : 'miss'}" style="margin:12px 0 0">
        ${same
          ? '三方清单逐项相同 —— 而这三个视角在编译期都不知道对方有哪些工具'
          : '三方清单不一致（或有某一方拿不到）—— 看上面标红的那一列'}
      </div>
      <p class="muted" style="margin:10px 0 0">
        这份清单就是 MCP 与「框架自带 tool calling」的分水岭：进程内的 <code>@Tool</code> 方法
        只有同一个 JVM 看得见，换个框架、换个语言就没了；而 MCP 的清单是
        <b>运行时问回来的</b> —— 所以三方（乃至一个几十行的 Python 脚本）看到的是同一份东西。
      </p>`;
  }

  function renderMcpVerdict(raw, a, b) {
    const box = $('#mc-verdict');
    if (!a.ok && !b.ok) {
      box.innerHTML = `<p class="muted">两侧后端都没拿到数据（看上面的错误提示）。
        最可能的原因：<b>mcp-skill-server（:8099）没起</b> ——
        两个后端启动时就会去拉远端工具清单，它不在的话后端起不来。</p>`;
      return;
    }

    const state = side => {
      if (!side.ok) return null;
      const on = mcpHit(side.on.answer);
      const off = mcpHit(side.off.answer);
      const onHit = on.name && on.target;
      const offHit = off.name || off.target;
      return { onHit, offHit, closed: onHit && !offHit };
    };
    const sa = state(a);
    const sb = state(b);

    const mark = v => v === null || v === undefined ? '<span class="muted">—</span>'
      : v ? '<b class="txt-ok">成立</b>' : '<b class="txt-bad">不成立</b>';
    const num = side => side.ok ? `<b>${side.tools.length}</b> 个` : '<span class="muted">—</span>';
    const tm = side => side.ok ? ms(side.on.elapsedMs) : '—';
    const tmOff = side => side.ok ? ms(side.off.elapsedMs) : '—';

    const toolsSame = a.ok && b.ok && a.tools.join('|') === b.tools.join('|');
    const allClosed = sa && sb && sa.closed && sb.closed;

    box.innerHTML = `
      <table class="tbl">
        <thead><tr><th>检查项</th><th>P1 · Spring AI</th><th>P2 · LangChain4j</th></tr></thead>
        <tbody>
          <tr><td>远端工具数</td><td>${num(a)}</td><td>${num(b)}</td></tr>
          <tr><td>实验组答出锚点（必须）</td><td>${mark(sa && sa.onHit)}</td><td>${mark(sb && sb.onHit)}</td></tr>
          <tr><td>对照组答不出锚点（必须）</td><td>${mark(sa && !sa.offHit)}</td><td>${mark(sb && !sb.offHit)}</td></tr>
          <tr><td>耗时 · 实验组 / 对照组</td>
            <td>${tm(a)} / ${tmOff(a)}</td><td>${tm(b)} / ${tmOff(b)}</td></tr>
          <tr><td>两侧清单是否一致</td><td colspan="2">${a.ok && b.ok ? mark(toolsSame) : '<span class="muted">—</span>'}</td></tr>
        </tbody>
      </table>
      <div class="verdict ${allClosed ? 'hit' : 'miss'}" style="margin:12px 0 0">
        ${allClosed
          ? '两侧都是「挂上答得出、不挂答不出」—— MCP 调用链成立，锚点对照封闭'
          : '有实验未封闭 —— 见上面标「不成立」的行'}
      </div>
      <p class="muted" style="margin:12px 0 0">
        <b>「对照组答不出」这个前提本身必须是可信的。</b>本项目就踩过一次：P2 的对照组居然答出了锚点，
        看起来像「锚点无效，实验作废」。查下去才发现它<b>根本没调 MCP</b>
        （MCP server 侧 <code>tools/call</code> 计数只有 2，两侧实验组各一次），
        但它的请求体里带着<b>上一轮实验组的完整问答</b>，于是把答案复述了一遍。
        根因是「没有 <code>@MemoryId</code> 的方法会被分配默认记忆槽，而两个『独立』实例
        共用同一个 <code>ChatMemoryProvider</code>」——
        <b>记忆的边界不在服务实例上，在 Provider 和 id 上。</b><br>
        <b>为什么这条比别的坑重要</b>：假通过不会表现为「实验失败」，而会
        <b>伪装成「锚点无效，实验作废」</b> —— 一个看起来像结论的东西。
        假通过比假失败危险，因为它会把错误结论直接写进文档。
      </p>
      <p class="muted" style="margin:8px 0 0">
        <b>耗时怎么读</b>：实验组比对照组慢一倍多，不只是「网络慢」——
        实验组多了一整趟「模型决定调工具 → 跨进程 <code>tools/call</code> → 结果回灌 → 再生成」
        的往返。这也正好解释了<b>为什么简单工具不该上 MCP</b>：
        进程内的方法调用不需要这一趟。
      </p>`;
  }

  // ---------------- 启动 ----------------

  function bindActions() {
    $('#qa-go').addEventListener('click', () => withBusy($('#qa-go'), runQA));
    $('#st-go').addEventListener('click', () => withBusy($('#st-go'), runStream));
    $('#tk-go').addEventListener('click', () => withBusy($('#tk-go'), runThink));
    $('#mm-go').addEventListener('click', () => withBusy($('#mm-go'), runMemory));
    $('#mc-go').addEventListener('click', () => withBusy($('#mc-go'), runMcp));
    $('#btn-health').addEventListener('click', refreshHealth);

    const demoBtn = $('#btn-demo');
    if (demoBtn) {
      demoBtn.classList.toggle('is-on', demoOn);
      demoBtn.textContent = demoOn ? '退出演示模式' : '演示模式';
      demoBtn.addEventListener('click', () => setDemo(!demoOn));
    }

    // Ctrl / Cmd + Enter 发送当前页签
    const map = {
      'qa-q': '#qa-go', 'st-q': '#st-go', 'tk-q': '#tk-go',
      'mm-cid': '#mm-go', 'mc-q': '#mc-go'
    };
    Object.entries(map).forEach(([inputId, btnSel]) => {
      const input = document.getElementById(inputId);
      if (!input) return;
      input.addEventListener('keydown', ev => {
        if (ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) {
          ev.preventDefault();
          $(btnSel).click();
        }
      });
    });
  }

  async function withBusy(btn, fn) {
    const old = btn.textContent;
    btn.disabled = true;
    btn.textContent = '请求中…';
    try { await fn(); }
    finally { btn.disabled = false; btn.textContent = old; }
  }

  function initIdle() {
    showIdle($('#qa-p1'), 'p1', '/api/chat/agent');
    showIdle($('#qa-p2'), 'p2', '/api/chat');
    showIdle($('#st-p1'), 'p1', '/api/chat/stream');
    showIdle($('#st-p2'), 'p2', '/api/chat/stream');
    showIdle($('#tk-p1'), 'p1', '/api/chat/think');
    showIdle($('#tk-p2'), 'p2', '/api/chat/think');
    showIdle($('#mm-p1'), 'p1', '/api/chat/memory');
    showIdle($('#mm-p2'), 'p2', '/api/chat/memory');
    showIdle($('#mc-p1'), 'p1', '/api/chat/mcp');
    showIdle($('#mc-p2'), 'p2', '/api/chat/mcp');
  }

  function init() {
    bindTabs();
    bindPresets();
    bindThinkToggle();
    bindActions();
    initIdle();
    refreshHealth();

    const note = $('#foot-note');
    if (note) {
      note.textContent = `后端地址：${base('p1')} / ${base('p2')}（可用 ?p1=…&p2=… 覆盖）`;
    }

    // URL 参数 —— 让一条链接就能直达某个对照实验，也方便自动化截图：
    //   ?tab=think          直接切到「思考模式」页签
    //   &auto=1             进页面就自动跑一次
    //   &thinking=false     预置 Spring AI 侧的思考开关
    //   &q=自定义问题       覆盖输入框内容
    const qs = new URLSearchParams(location.search);
    const tab = qs.get('tab') || 'qa';

    const tabBtn = $(`.tab[data-panel="${tab}"]`);
    if (tabBtn) tabBtn.click();

    if (qs.get('thinking') === 'false') {
      const off = $('input[name="tk-thinking"][value="false"]');
      if (off) off.checked = true;
    }

    const customQ = qs.get('q');
    if (customQ) {
      // 记忆页签的「问题」其实是会话 ID —— 同一个参数名复用，方便一条链接直达实验
      const inputMap = { qa: '#qa-q', stream: '#st-q', think: '#tk-q', memory: '#mm-cid', mcp: '#mc-q' };
      const input = $(inputMap[tab]);
      if (input) input.value = customQ;
    }

    // &window=4 预置记忆窗口（只对记忆页签有意义），用来一键截「裁剪」那组图
    const customWindow = qs.get('window');
    if (customWindow) {
      const sel = $('#mm-window');
      if (sel) sel.value = customWindow;
    }

    if (qs.get('auto')) {
      // 稍等一下再跑，避开探活请求，免得两条链路互相抢带宽影响计时
      setTimeout(() => {
        const goMap = { qa: '#qa-go', stream: '#st-go', think: '#tk-go', memory: '#mm-go', mcp: '#mc-go' };
        const go = $(goMap[tab]);
        if (go) go.click();
      }, 700);
    }
  }

  // ---------------- 演示模式 ----------------
  /**
   * ?demo=1 时不打后端，直接替换 window.fetch，用本地合成响应喂给上面那套逻辑。
   *
   * <p>为什么用「拦截 fetch」而不是「另写一条渲染分支」：这样页面里所有真实逻辑
   * （SSE 解析、计时、token 归一化、DOM 渲染）都原样跑一遍，演示模式和真实模式
   * 走的是同一条代码路径，不会出现「演示好看、真实跑起来不对」的情况。
   *
   * <p>两个用途：没启动后端时也能看页面长什么样（给不熟悉环境的人演示）；
   * 以及自动化截图验证渲染 —— 合成响应的时序可控，不受真实网络抖动影响。
   */
  // 记住原始实现，退出演示模式时要还原（否则整页从此再也打不到真实后端）
  const realFetch = window.fetch.bind(window);
  const realNow = performance.now.bind(performance);
  let demoOn = false;

  // ---------------- 演示模式的「对话记忆」合成数据 ----------------

  /**
   * 第五个页签（对话记忆）在演示模式下的返回体。
   *
   * <p>数值全部取自 probe/check-memory.py 实测那一次运行 —— 曲线高度、结论表里的
   * 每条历史 token 成本都对得上，差别只有网络耗时抖动。回忆轮与对照轮的回答是实测原文；
   * 前三轮那句短回答是复述（脚本只打印回忆与对照两轮）。
   *
   * <p>为什么连「小窗口」那一份也做：这一页最值钱的一眼就是
   * **同一个问题、窗口从 20 压到 2，锚点就丢了** —— 演示模式必须能把这个对照演出来，
   * 否则没启动后端的人只会看到一条漂亮的增长曲线，看不到它的反面。
   */
  const MEMQ = {
    r1: '1区现在在线多少人？',
    r2: '记住这个热更批次号：LOONG-7749。收到请只回复「已记录」。',
    r3: '2区现在在线多少人？',
    recall: '请复述一下：我前面问过的区服的在线人数，以及我让你记住的热更批次号。'
  };

  /** 一份完整的 /api/chat/memory 返回体（spring = P1，否则 P2；win=2 即小窗口对照）。 */
  function memDemoData(spring, win) {
    const small = win === 2;
    const alt = n => Array.from({ length: n }, (_, i) => (i % 2 === 0 ? 'user' : 'assistant'));
    const tk = (p, c) => ({ prompt: p, completion: c, total: p + c });

    // P2（LangChain4j）记忆里连 system 和工具轮的 tool 中间消息一起存，
    // 所以角色序列长这样 —— 和 P1 的 alt(n) 正好形成对照。
    const P2R = {
      t1: ['system', 'user', 'assistant', 'tool', 'assistant'],
      t2: ['system', 'user', 'assistant', 'tool', 'assistant', 'user', 'assistant'],
      t3: ['system', 'user', 'assistant', 'tool', 'assistant', 'user', 'assistant',
           'user', 'assistant', 'tool', 'assistant'],
      recall: ['system', 'user', 'assistant', 'tool', 'assistant', 'user', 'assistant',
               'user', 'assistant', 'tool', 'assistant', 'user', 'assistant'],
      control: ['system', 'user', 'assistant']
    };

    const TXT = spring ? {
      r1: '1区当前在线玩家数为 3214 人。',
      r2: '已记录',
      r3: '2区当前在线玩家数为 1870 人。',
      recall: '- s1：3214 人\n- s2：1870 人\n- 热更批次号：LOONG-7749',
      recallSmall: '您前面只问过 **2区** 的在线人数，结果是 **1870 人**。\n\n'
                 + '至于热更批次号：您并没有让我记住过任何版本号，我也没有查询过热更状态。',
      control: '我这边没有保留之前的对话记录，所以无法复述你之前问过的区服在线人数或热更批次号。'
    } : {
      r1: '1区（s1）当前在线 3214 人。',
      r2: '已记录',
      r3: '2区（s2）当前在线 1870 人。',
      recall: '- 1区（s1）在线：**3214** 人\n- 2区（s2）在线：**1870** 人\n- 热更批次号：**LOONG-7749**',
      recallSmall: '这是本会话的第一条消息，在此之前我没有收到过任何区服查询，'
                 + '也没有你让我记住的热更批次号 —— 我这边没有可复述的内容。',
      control: '这是我们对话的第一条消息，我这边没有更早的会话记录，所以无法复述。'
    };

    // 实测的 prompt / completion（小窗口那一份是修复 advisor 累积之后重新测的）
    const N = spring
      ? (small
        ? { p: [906, 463, 960], r: [464, 44], c: [444, 59], ms: [1541, 363, 1138, 911, 656] }
        : { p: [906, 467, 1010], r: [521, 30], c: [444, 41], ms: [3145, 652, 1265, 567, 845] })
      : (small
        ? { p: [781, 473, 787], r: [493, 164], c: [411, 116], ms: [1798, 567, 2247, 1280, 836] }
        : { p: [907, 488, 1168], r: [597, 49], c: [411, 362], ms: [1969, 738, 1740, 797, 2583] });

    // P1 每轮往记忆里加 2 条（user+assistant）；P2 的工具轮会多出 tool 中间消息
    const roles = spring ? { t: [alt(2), alt(4), alt(6)], recall: alt(8), control: alt(2) }
                         : { t: [P2R.t1, P2R.t2, P2R.t3], recall: P2R.recall, control: P2R.control };
    const mem = small ? [0, 2, 2] : (spring ? [0, 2, 4] : [0, 5, 7]);
    const recallMem = small ? 2 : (spring ? 6 : 11);

    const turn = (i, q, a) => ({
      round: i + 1,
      question: q,
      answer: a,
      memoryMessages: mem[i],
      memoryRoles: roles.t[i],
      memoryFirstUser: small ? MEMQ.recall.slice(0, 24) + '…' : MEMQ.r1,
      tokens: tk(N.p[i], 53),
      elapsedMs: N.ms[i]
    });

    return {
      conversationId: 'demo-a',
      memoryWasReset: true,
      windowMaxMessages: small ? 2 : 20,
      windowSource: spring
        ? (small ? '本次请求自定义（MessageWindowChatMemory.builder().maxMessages(n)）'
                 : '自动配置（MessageWindowChatMemory 的 DEFAULT_MAX_MESSAGES = 20）')
        : (small ? '本次请求自定义（AiServices.builder() 现场构造 + 小窗口 Provider）'
                 : 'Bean 装配（config/MemoryConfig 里的 ChatMemoryProvider，窗口 20 条）'),
      turns: [turn(0, MEMQ.r1, TXT.r1), turn(1, MEMQ.r2, TXT.r2), turn(2, MEMQ.r3, TXT.r3)],
      recall: {
        round: 4,
        question: MEMQ.recall,
        answer: small ? TXT.recallSmall : TXT.recall,
        memoryMessages: recallMem,
        memoryRoles: roles.recall,
        memoryFirstUser: small ? MEMQ.recall.slice(0, 24) + '…' : MEMQ.r1,
        tokens: tk(N.r[0], N.r[1]),
        elapsedMs: N.ms[3]
      },
      control: {
        round: -1,
        question: MEMQ.recall,
        answer: TXT.control,
        memoryMessages: 0,
        memoryRoles: roles.control,
        memoryFirstUser: MEMQ.recall.slice(0, 24) + '…',
        tokens: tk(N.c[0], N.c[1]),
        elapsedMs: N.ms[4]
      },
      evidence: small
        ? {
          recallHit: false,
          anchorHit: false,
          hits: spring ? ['1870'] : [],
          misses: spring ? ['3214', 'LOONG-7749'] : ['3214', '1870', 'LOONG-7749'],
          controlHit: false,
          verdict: spring
            ? '锚点 LOONG-7749 没被复述出来，只答对 1/3（丢的是 [3214, LOONG-7749]）'
              + ' → 记忆没生效，或被窗口裁掉了'
            : '锚点 LOONG-7749 没被复述出来，三个事实全丢 → 记忆没生效，或被窗口裁掉了'
        }
        : {
          recallHit: true,
          anchorHit: true,
          hits: ['3214', '1870', 'LOONG-7749'],
          misses: [],
          controlHit: false,
          verdict: '记忆生效：锚点 LOONG-7749（工具产不出的值）被复述出来，'
                 + '并附带两个工具可查的数字，无记忆对照侧连锚点都答不出'
        },
      note: small ? '自定义小窗口：2 条消息 = 1 轮，最早的事实会被挤掉。'
                  : (spring ? '自动配置窗口 20 条消息 = 10 轮，本次 4 轮不会触发裁剪。'
                            : 'Bean 窗口 20 条消息 = 10 轮，本次 4 轮不会触发裁剪。')
    };
  }

  // ---------------- 演示模式的「MCP」合成数据 ----------------

  /**
   * MCP 页签在演示模式下的返回体。
   *
   * <p>耗时与回答原文都取自 {@code probe/run-mcp-check.ps1} 实测那一次 —— 包括
   * 两侧对照组**截然不同**的反应：P1 硬编成「烈焰斩」，P2 老实说「没有技能配置查询的接口」。
   * 这两种反应本身就很说明问题，所以原文照抄，不做美化。
   *
   * <p><b>token 留空是有意的</b>：那一次的日志只记了耗时和工具数，没记 token。
   * 演示数据一旦掺一个编出来的数字，就没法再当参照物用了 —— 宁可显示「—」。
   */
  const MCP_DEMO = {
    p1: {
      on: {
        answer: '技能 **9001** 名为 **龙血·磐石**。\n\n- 类型：ACTIVE，消耗 5，标签 [SPECIAL, SUMMON]\n'
              + '- 效果目标类型：**#8 REVEALED_AREA（已揭示的区域）**\n'
              + '- 效果：召唤 1 个磐石守卫，触发条件 TURN_INDEX >= 3（第 3 回合后才能使用）',
        elapsedMs: 3399, tokens: null
      },
      off: {
        answer: '技能 9001 的名称是“烈焰斩”，目标类型为敌方单体。',
        elapsedMs: 872, tokens: null
      }
    },
    p2: {
      on: {
        answer: '技能 **9001** 名为 **龙血·磐石**（ACTIVE，消耗 5，标签 SPECIAL / SUMMON）。\n\n'
              + '- **目标类型**：#8 **REVEALED_AREA（已揭示的区域）**\n'
              + '- 效果：在该区域召唤 1 个磐石守卫（不占手牌）\n'
              + '- 触发条件：TURN_INDEX >= 3（第 3 回合后才能使用）',
        elapsedMs: 3772, tokens: null
      },
      off: {
        answer: '我当前能用的工具只有两个：热更状态查询（hotfixStatus）和区服在线人数查询（onlinePlayers），'
              + '**没有技能配置查询的接口**，所以无法查到技能 9001 的名字和目标类型。',
        elapsedMs: 1603, tokens: null
      }
    }
  };

  function mcpDemoData(isSpring, withMcp) {
    const d = (isSpring ? MCP_DEMO.p1 : MCP_DEMO.p2)[withMcp ? 'on' : 'off'];
    return {
      answer: d.answer,
      withMcp,
      toolCount: MCP_EXPECTED.length,
      toolNames: MCP_EXPECTED.slice(),
      elapsedMs: d.elapsedMs,
      tokens: d.tokens
    };
  }

  function installDemoMode() {
    const enc = new TextEncoder();

    // 演示模式下既没有真实网络耗时，--virtual-time-budget 还会把定时器快进，
    // performance.now() 量出来的差值基本是 0。直接换成一个递增的假时钟，
    // 让「首字延迟 / 总耗时」显示成合理的数量级（否则那一栏永远是 0 ms，很出戏）。
    let clock = 0;
    const steps = [190, 260, 340, 430, 520];
    let si = 0;
    performance.now = () => (clock += steps[si++ % steps.length]);

    const jsonResponse = obj => new Response(JSON.stringify(obj), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });

    const sseResponse = frames => new Response(new ReadableStream({
      start(controller) {
        let i = 0;
        const tick = setInterval(() => {
          if (i >= frames.length) {
            controller.close();
            clearInterval(tick);
            return;
          }
          controller.enqueue(enc.encode(`data:${frames[i]}\n\n`));
          i++;
        }, 90);
      }
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

    const THINK = '这道题是求 13 的平方。直接算：13 × 13 = 169。'
                + '题目要求只回答数字，所以最终输出 169。';

    // ⚠️ opts 也要收：MCP 那一路（裸协议直连 8099）是「三种请求共用一个端点」，
    // 只能靠请求体里的 method 区分 initialize / notifications/initialized / tools/list ——
    // 签名里丢掉 opts 就分不出来了，表现为 tools/list 拿到 initialize 的返回。
    window.fetch = async (url, opts = {}) => {
      const u = String(url);
      const isSpring = u.includes('/api/health') ? u.includes('8081') : u.includes('8081');

      if (u.includes('/api/health')) {
        return jsonResponse(isSpring ? {
          app: 'spring-ai-demo',
          framework: 'Spring AI 2.0.1',
          bootVersion: '4.1.1',
          webStack: 'Spring MVC（servlet 栈）',
          javaVersion: '17.0.12',
          port: '8081',
          model: 'deepseek-flash',
          thinking: 'disabled',
          modelStarter: 'spring-ai-starter-model-deepseek'
        } : {
          app: 'langchain4j-demo',
          framework: 'LangChain4j 1.20.0',
          bootVersion: '3.5.16',
          webStack: 'Spring MVC（servlet 栈）',
          javaVersion: '17.0.12',
          port: '8082',
          model: 'deepseek-flash',
          baseUrl: 'https://api.deepseek.com/v1',
          returnThinking: 'true',
          reasoningEffort: '(未设置)',
          modelStarter: 'langchain4j-open-ai-spring-boot-starter'
        });
      }

      // ── 裸协议直连 MCP server（8099）：三种 JSON-RPC 共用一个端点，靠 body 里的 method 区分 ──
      if (u.includes(':8099/mcp')) {
        let method = '';
        try { method = JSON.parse((opts && opts.body) || '{}').method || ''; } catch (_) { /* 忽略 */ }
        if (method === 'initialize') {
          // 会话 id 走响应头 —— 真实服务端也这么发（而且要靠 WebConfig 的 exposedHeaders 才读得到）
          return new Response(JSON.stringify({
            jsonrpc: '2.0', id: 1,
            result: {
              protocolVersion: '2025-11-25',
              capabilities: { tools: {} },
              serverInfo: { name: 'skill-config-server', version: '1.0.0' }
            }
          }), {
            status: 200,
            headers: { 'Content-Type': 'application/json', 'Mcp-Session-Id': 'demo-session' }
          });
        }
        if (method === 'tools/list') {
          return jsonResponse({
            jsonrpc: '2.0', id: 2,
            result: {
              tools: MCP_EXPECTED.map(n => ({
                name: n, description: '（演示模式合成）', inputSchema: { type: 'object' }
              }))
            }
          });
        }
        // notifications/initialized：规范要求回 202 且不带 JSON-RPC body
        return new Response('', { status: 202 });
      }

      // MCP 页签。⚠️ /tools 必须先判 —— 它也是 /api/chat/mcp 的前缀，
      // 顺序反了就会把工具清单请求喂给「问答」分支，拿到一份答非所问的响应。
      if (u.includes('/api/chat/mcp/tools')) {
        return jsonResponse(MCP_EXPECTED.slice());
      }
      if (u.includes('/api/chat/mcp')) {
        // ⚠️ 这里必须判 withMcp=**true**。写成 /withMcp=false/.test(u) 再原样传给
        // mcpDemoData(...) 会把语义传反：实验组拿到对照组的答案、对照组拿到锚点答案。
        // 而且它在页面上**不会报错** —— 两侧卡片照常渲染，只是结论全反。
        // （这个 bug 就是被 probe/dom-assert.mjs 抠值抓出来的，肉眼看截图看不出来。）
        return jsonResponse(mcpDemoData(isSpring, /withMcp=true/.test(u)));
      }

      if (u.includes('/think')) {
        return jsonResponse({
          answer: '169',
          reasoningContent: isSpring ? THINK : THINK + ' 再核对一遍：13×10=130，13×3=39，130+39=169。',
          reasoningChars: isSpring ? 45 : 88,
          tokens: isSpring
            ? { prompt: 57, completion: 40, total: 97 }
            : { prompt: 398, completion: 39, total: 437 }
        });
      }

      if (u.includes('/stream')) {
        // 每帧都不能含裸换行 —— SSE 里换行是帧分隔符，帧内容含换行必须拆成多个
        // data 行（Spring 就是这么编码的）。这里直接按真实输出那样逐字分帧。
        return sseResponse(isSpring
          ? ['1', '，', '2', '，', '3', '，', '4', '，', '5', '。']
          : ['1', '、', '2', '、', '3', '、', '4', '、', '5']);
      }

      // 对话记忆页签：窗口下拉选了 2 就返回小窗口那一份，否则返回默认窗口。
      // 这样演示模式下「压窗口 → 锚点丢失」这个对照也能真的点出来。
      if (u.includes('/api/chat/memory')) {
        const m = u.match(/maxMessages=(\d+)/);
        const win = m ? Number(m[1]) : null;
        const d = memDemoData(isSpring, win);
        // 演示模式只内置了「窗口 20」与「窗口 2」两组实测数据。下拉里还有个 6 ——
        // 这里不编数据：仍按 20 那一组渲染，只把窗口数字跟上下拉，并在 note 里明说，
        // 免得有人以为这是 6 条的实测结果。
        if (win && win !== 2) {
          d.windowMaxMessages = win;
          d.note = '演示模式只内置了窗口 20 与 2 两组实测数据；这一组按窗口 20 渲染，仅窗口数字随参数变化。';
        }
        return jsonResponse(d);
      }

      return new Response(
        isSpring ? 's1 区服当前在线玩家数为 3214。' : 's1 区服当前在线玩家数为 3214 人。',
        { status: 200 }
      );
    };
  }

  /**
   * 运行时可切换的演示模式开关（顶部「演示模式」按钮）。
   *
   * <p>URL 参数只决定初始状态；进来之后还能随时切回真实后端，不必去改地址栏。
   * 关掉时必须把 fetch / performance.now 还原成真实实现，否则整页从此再也打不到后端。
   */
  function setDemo(on) {
    if (on === demoOn) return;
    demoOn = on;
    if (on) {
      installDemoMode();
    } else {
      window.fetch = realFetch;
      performance.now = realNow;
    }
    const db = $('#btn-demo');
    if (db) {
      db.classList.toggle('is-on', on);
      db.textContent = on ? '退出演示模式' : '演示模式';
    }
    refreshHealth();
  }

  // 必须在任何 fetch 触发之前装上，所以放在这里（而不是 init 里面）
  const demoRequested = !!new URLSearchParams(location.search).get('demo');
  if (demoRequested) {
    installDemoMode();
    demoOn = true;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
