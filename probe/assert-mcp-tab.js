/*
 * 喂给 probe/dom-assert.mjs 的求值表达式：把「MCP 页签」渲染后的关键值抠出来并判定。
 *
 * 为什么要抠值而不是看图：这一页的结论是「成立 / 不成立」这种二元判定，
 * 截图缩到能看全时字号已经小到读不准；而判定一旦错了，
 * 它表现的恰恰是「看起来像个正常结论」—— 属于最危险的那种错。
 * （本项目就真被它抓到过一次：演示模式里实验组/对照组的答案取反了，
 *   两侧卡片照常渲染、外观完全正常，只是结论全反。）
 *
 * 用法（demo 模式，不需要后端）：
 *   node probe/dom-assert.mjs --url "file:///.../index.html?demo=1&tab=mcp&auto=1" \
 *        --settle 6000 --expr-file probe/assert-mcp-tab.js
 * 用法（真实后端，由 run-dashboard-check.ps1 调用）：
 *   node probe/dom-assert.mjs --url "http://localhost:8090/index.html?tab=mcp&auto=1" \
 *        --settle 35000 --expr-file probe/assert-mcp-tab.js
 */
(() => {
  const txt = el => (el ? el.textContent.replace(/\s+/g, ' ').trim() : null);
  // ⚠️ root 参数不能省：早先这里写成单参 `sel => [...document.querySelectorAll(sel)]`，
  // 调用处却传了 `all('.mcp-anchor .fact', c)` —— 第二个参数被默默丢掉，
  // 于是「每张卡片自己的锚点标记」变成了「全页 4 张卡片共 8 个标记」，
  // 断言读到的是别人的数据。这类"作用域静默失效"只有抠值才看得见。
  const all = (sel, root = document) => [...root.querySelectorAll(sel)];

  // ── 抠值 ───────────────────────────────────────────────────────────

  const tabs = all('.tab').map(t => t.textContent.trim());

  const svc = {
    p1: txt(document.querySelector('#svc-p1 .svc-text')),
    p2: txt(document.querySelector('#svc-p2 .svc-text')),
    mcp: txt(document.querySelector('#svc-mcp .svc-text'))
  };

  const toolsCols = all('.mcp-col').map(c => ({
    cls: c.className.replace('mcp-col', '').trim(),
    head: txt(c.querySelector('.mcp-col-head')),
    n: c.querySelectorAll('.mcp-tool').length,
    tools: all('.mcp-tool', c).map(x => x.textContent.trim())
  }));

  const toolsVerdict = {
    cls: (document.querySelector('#mc-tools .verdict')?.className || '').replace('verdict', '').trim(),
    text: txt(document.querySelector('#mc-tools .verdict'))
  };

  const cases = all('.mcp-case').map(c => ({
    title: txt(c.querySelector('.mm-round-no')),
    withMcp: txt(c.querySelector('.mm-round-q')),
    pass: txt(c.querySelector('.mcp-pass')),
    answer: txt(c.querySelector('.mm-round-a')).slice(0, 70),
    facts: all('.mcp-anchor .fact', c).map(f => ({
      cls: f.className.replace('fact', '').trim(),
      label: txt(f)
    }))
  }));

  const verdictRows = all('#mc-verdict tr').map(tr =>
    all('th,td', tr).map(c => txt(c)));

  const sideVerdicts = all('.side .verdict').map(v => ({
    cls: v.className.replace('verdict', '').trim(),
    text: txt(v)
  }));

  // ── 判定 ───────────────────────────────────────────────────────────

  const EXPECT = ['explain_effect_target', 'get_skill', 'list_skills', 'validate_condition'];
  const asserts = [];
  const add = (name, ok, detail = '') => asserts.push({ name, ok, detail });

  add('顶部有 6 个页签且含「MCP 远程工具」',
      tabs.length === 6 && tabs.includes('MCP 远程工具'), tabs.join(' / '));

  add('三个服务指示灯都在线',
      /在线/.test(svc.p1 || '') && /在线/.test(svc.p2 || '') && /在线/.test(svc.mcp || ''),
      `P1=${svc.p1} P2=${svc.p2} MCP=${svc.mcp}`);

  add('三方清单画出了 3 列', toolsCols.length === 3, toolsCols.map(c => c.head).join(' | '));

  const listKey = toolsCols.map(c => c.tools.join('|'));
  const allFour = toolsCols.every(c => c.n === 4 && c.tools.join('|') === EXPECT.join('|'));
  add('三方各报出同样的 4 个工具', toolsCols.length === 3 && allFour,
      toolsCols.map(c => `${c.n}个`).join(' / ') + (allFour ? '' : '  ← 清单不一致或数量不对'));

  add('三方清单一致结论条是「一致」', toolsVerdict.cls === 'hit', toolsVerdict.text || '(无结论条)');

  // 每侧：实验组必须答出锚点
  const onCases = cases.filter(c => c.withMcp === 'withMcp=true');
  add('实验组两组都「符合预期」（挂上答得出）',
      onCases.length === 2 && onCases.every(c => c.pass === '符合预期'),
      onCases.map(c => `${c.title}=${c.pass}`).join(' / '));

  // 每侧：对照组必须答不出锚点
  const offCases = cases.filter(c => c.withMcp === 'withMcp=false');
  add('对照组两组都「符合预期」（不挂答不出）',
      offCases.length === 2 && offCases.every(c => c.pass === '符合预期'),
      offCases.map(c => `${c.title}=${c.pass}`).join(' / ') + '  ← 若为「不符合预期」：信息泄漏或记忆串槽，锚点无效');

  add('两侧 side 结论条都是「锚点对照封闭」',
      sideVerdicts.length === 2 && sideVerdicts.every(v => v.cls === 'hit'),
      sideVerdicts.map(v => v.cls).join(' / '));

  // 结论表：不应出现「不成立」
  const flat = verdictRows.map(r => r.join(' | '));
  const hasBadRow = flat.some(r => r.includes('不成立'));
  add('结论表里没有「不成立」的行', verdictRows.length > 0 && !hasBadRow, flat.join('  ||  '));

  return {
    asserts,
    summary: {
      tabs,
      svc,
      activePanel: txt(document.querySelector('.panel.is-on')).slice(0, 30),
      toolsVerdict,
      toolsCols,
      cases,
      verdictRows,
      sideVerdicts
    }
  };
})()
