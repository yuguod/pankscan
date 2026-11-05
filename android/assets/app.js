(function () {
  'use strict';

  var STORE_KEY = 'pankscan.v3';
  var HELP_KEY = 'pankscan.helpSeen';
  var DEFAULT_STATUSES = ['良品', '不良品', '待检'];
  var APP_INFO = {
    name: '盘库助手',
    version: '1.10',
    build: '2026-11-05',
    author: '雨过',
    pkg: 'com.pankscan.app',
    engine: 'ZXing 3.5.3'
  };
  var $ = function (id) { return document.getElementById(id); };

  // 装在 Android APK 里时，保存/分享走原生桥，浏览器里走 Web API
  var bridge = (window.AndroidBridge && typeof window.AndroidBridge.saveFile === 'function')
    ? window.AndroidBridge : null;
  // APK 提供了原生扫码接口时，扫码头改用原生相机
  var nativeScan = !!(bridge && typeof bridge.startScan === 'function');
  var resumeScan = false;

  function fileToBase64(file) {
    return new Promise(function (resolve, reject) {
      var rd = new FileReader();
      rd.onload = function () {
        var s = String(rd.result);
        resolve(s.slice(s.indexOf(',') + 1));
      };
      rd.onerror = reject;
      rd.readAsDataURL(file);
    });
  }

  function seedParts() {
    var src = window.PARTS_DEFAULT || [];
    return src.map(function (p) { return { code: p.code, name: p.name }; });
  }

  function partKey(p) { return (p.code || '') + '\u0001' + (p.name || ''); }

  function defaults() {
    return {
      parts: seedParts(),
      statuses: DEFAULT_STATUSES.slice(),
      records: [],
      current: { pallet: '', part: '', name: '', status: '良品' },
      opts: { qr: false, dedupe: true, sound: true, vibe: true, stripPrefix: true }
    };
  }

  function load() {
    var d = defaults();
    try {
      var raw = localStorage.getItem(STORE_KEY);
      if (!raw) return d;
      var s = JSON.parse(raw);
      return {
        parts: Array.isArray(s.parts) ? s.parts : d.parts,
        statuses: (Array.isArray(s.statuses) && s.statuses.length) ? s.statuses : d.statuses,
        records: Array.isArray(s.records) ? s.records : [],
        current: Object.assign({}, d.current, s.current || {}),
        opts: Object.assign({}, d.opts, s.opts || {})
      };
    } catch (e) { return d; }
  }

  var state = load();
  var uid = 1;

  // 名称里通常自带料号前缀（DSK05台式机5代），显示/导出时可按需去掉
  function shortName(code, name) {
    name = name || '';
    if (!state.opts || state.opts.stripPrefix === false) return name;
    if (code && name.indexOf(code) === 0) return name.slice(code.length).trim();
    return name;
  }

  function save() {
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify(state));
    } catch (e) {
      toast('本机存储已满，请先导出并清理记录');
    }
    syncSnHint();
  }

  /* ---------- 通用 UI ---------- */

  var toastTimer = null;
  function toast(msg, ms) {
    var el = $('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); }, ms || 1600);
  }

  /** 带一个按钮的 toast，用于「刚刚删掉的那条还能捡回来」 */
  function toastAction(msg, label, fn, ms) {
    var el = $('toast');
    el.innerHTML = '';
    var t = document.createElement('span');
    t.textContent = msg;
    el.appendChild(t);
    var b = document.createElement('button');
    b.className = 'undo';
    b.textContent = label;
    b.addEventListener('click', function () {
      el.classList.remove('show');
      clearTimeout(toastTimer);
      fn();
    });
    el.appendChild(b);
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      el.classList.remove('show');
      toastTimer = setTimeout(function () { el.innerHTML = ''; }, 400);
    }, ms || 5000);
  }

  /* 弹窗栈：返回键先收最上面一层。原生那边据此决定是关弹窗还是问「退出」，
     不然只是打开「操作说明」看一眼，按返回就把程序退掉了。 */
  var modalStack = [];
  function syncModalFlag() {
    try {
      if (bridge && typeof bridge.setModalOpen === 'function') {
        bridge.setModalOpen(modalStack.length > 0);
      }
    } catch (e) {}
  }
  window.__backToClose = function () {
    var fn = modalStack[modalStack.length - 1];
    if (!fn) return false;
    fn();
    return true;
  };

  function dialog(opts) {
    var mask = document.createElement('div');
    mask.className = 'modal-mask';
    var box = document.createElement('div');
    box.className = 'modal';
    if (opts.title) {
      var h = document.createElement('h3');
      h.textContent = opts.title;
      box.appendChild(h);
    }
    if (opts.sub) {
      var s = document.createElement('p');
      s.className = 'sub';
      s.textContent = opts.sub;
      box.appendChild(s);
    }
    var body = document.createElement('div');
    body.className = 'body';
    if (typeof opts.body === 'string') body.innerHTML = opts.body;
    else if (opts.body) body.appendChild(opts.body);
    box.appendChild(body);
    var foot = document.createElement('div');
    foot.className = 'foot';
    var closed = false;
    function close(val) {
      if (closed) return;
      closed = true;
      var i = modalStack.indexOf(close);
      if (i >= 0) modalStack.splice(i, 1);
      syncModalFlag();
      mask.remove();
      if (opts.onClose) opts.onClose(val);
    }
    (opts.buttons || []).forEach(function (b) {
      var btn = document.createElement('button');
      btn.className = 'btn ' + (b.cls || '');
      btn.textContent = b.label;
      btn.addEventListener('click', function () {
        if (b.onClick && b.onClick() === false) return;
        close(b.value === undefined ? null : b.value);
      });
      foot.appendChild(btn);
    });
    box.appendChild(foot);
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(null); });
    $('modalRoot').appendChild(mask);
    modalStack.push(close);
    syncModalFlag();
    return { mask: mask, box: box, body: body, close: close };
  }

  function textDialog(cfg) {
    return new Promise(function (resolve) {
      var wrap = document.createElement('div');
      var input = document.createElement('input');
      input.type = 'text';
      input.value = cfg.value || '';
      input.placeholder = cfg.placeholder || '';
      if (cfg.upper) input.setAttribute('autocapitalize', 'characters');
      wrap.appendChild(input);
      if (cfg.suggestions && cfg.suggestions.length) {
        var box = document.createElement('div');
        box.className = 'sugg';
        cfg.suggestions.slice(-10).forEach(function (v) {
          var c = document.createElement('button');
          c.className = 'chip';
          c.type = 'button';
          c.textContent = v;
          c.addEventListener('click', function () { input.value = v; });
          box.appendChild(c);
        });
        wrap.appendChild(box);
      }
      dialog({
        title: cfg.title,
        sub: cfg.sub,
        body: wrap,
        buttons: [
          { label: '取消', value: null },
          {
            label: '确定', cls: 'primary', value: 'ok',
            onClick: function () { if (!input.value.trim()) { input.focus(); return false; } }
          }
        ],
        onClose: function (v) {
          resolve(v === 'ok' ? input.value.trim() : null);
        }
      });
      setTimeout(function () { input.focus(); try { input.select(); } catch (e) {} }, 150);
    });
  }

  function confirmDialog(title, sub, okLabel, danger) {
    return new Promise(function (resolve) {
      dialog({
        title: title,
        sub: sub,
        buttons: [
          { label: '取消', value: null },
          { label: okLabel || '确定', cls: danger ? 'danger' : 'primary', value: 'ok' }
        ],
        onClose: function (v) { resolve(v === 'ok'); }
      });
    });
  }

  /* ---------- 操作说明 / 关于 ---------- */

  var HELP_HTML = ''
    + '<div class="help">'
    + '<p class="lead">盘库作业共五步，按顺序做一遍即可完成一个栈板。</p>'
    + '<ol class="steps">'
    + '<li><b>设置栈板编码</b><span>点顶部「栈板编码」手动输入，例如 栈板 1。换栈板必须先改这里，否则会继续记到上一个栈板。</span></li>'
    + '<li><b>选择料号</b><span>点「料号 / 名称」，输入关键字搜索后选中具体那一行。同一料号有多个型号（如 DSK05 有 1~5 代），注意选对行。</span></li>'
    + '<li><b>连续扫码</b><span>点「开始扫描」，把条码放进取景框中央，对准即出码，不用反复点击。默认只记录不重复的 SN。</span></li>'
    + '<li><b>核对明细</b><span>到「明细」页，记录按栈板分组。点某条 SN 可以改料号/名称/状态；右侧 × 删除后 5 秒内可撤销；每个栈板卡片右上角能改名、单独导出、整板删除。</span></li>'
    + '<li><b>导出表格</b><span>到「设置」→「导出 Excel」，得到与模板同列序的表格（栈板编码 / 状态 / 料号 / 名称 / SN），可保存到手机或直接分享。</span></li>'
    + '</ol>'
    + '<h4>提示音</h4>'
    + '<ul class="dots">'
    + '<li><b>单声短响</b>：记录成功，可接着扫下一个</li>'
    + '<li><b>连续三声</b>：重复 SN 被跳过，或还没设置栈板编码</li>'
    + '<li>扫描过程中，取景框下方的文字同步显示上一次的结果与码制</li>'
    + '</ul>'
    + '<h4>扫码要点</h4>'
    + '<ul class="dots">'
    + '<li>距离 10~25cm，条码占取景框一半宽度时最稳</li>'
    + '<li>塑封、亚银标签反光时，稍微侧一点角度避开灯管直射</li>'
    + '<li>现场以 Code 128 为主，其他码制会自动尝试</li>'
    + '</ul>'
    + '<h4>数据安全</h4>'
    + '<ul class="dots">'
    + '<li>记录只存在本机，不上传服务器</li>'
    + '<li>换手机、清理数据前，先在「导出与备份」里保存一份</li>'
    + '</ul>'
    + '</div>';

  function showHelp() {
    dialog({
      title: '操作说明',
      sub: '简版流程，约一分钟看完',
      body: HELP_HTML,
      buttons: [{ label: '知道了', cls: 'primary', value: 'ok' }]
    });
    try { localStorage.setItem(HELP_KEY, '1'); } catch (e) {}
  }

  function showAbout() {
    var rows = [
      ['版本', 'V' + APP_INFO.version + '（build ' + APP_INFO.build + '）'],
      ['作者', APP_INFO.author],
      ['形态', bridge ? 'Android 应用' : '网页版（可加到桌面）'],
      ['扫码引擎', APP_INFO.engine]
    ];
    if (bridge) rows.splice(2, 0, ['包名', APP_INFO.pkg]);
    var kv = rows.map(function (r) {
      return '<li><span>' + r[0] + '</span><b>' + r[1] + '</b></li>';
    }).join('');
    var body = ''
      + '<div class="about">'
      + '<div class="logo">'
      + '<svg width="36" height="26" viewBox="0 0 36 26" fill="none" aria-hidden="true">'
      + '<rect x="0" y="0" width="3" height="26" fill="#fff"/>'
      + '<rect x="5" y="0" width="1.6" height="26" fill="#fff"/>'
      + '<rect x="8.6" y="0" width="3.6" height="26" fill="#fff"/>'
      + '<rect x="14.2" y="0" width="1.4" height="26" fill="#fff"/>'
      + '<rect x="17.4" y="0" width="2.4" height="26" fill="#fff"/>'
      + '<rect x="21.6" y="0" width="1.4" height="26" fill="#fff"/>'
      + '<rect x="24.8" y="0" width="3.4" height="26" fill="#fff"/>'
      + '<rect x="30" y="0" width="1.4" height="26" fill="#fff"/>'
      + '<rect x="33" y="0" width="3" height="26" fill="#fff"/>'
      + '</svg>'
      + '</div>'
      + '<div class="aname">' + APP_INFO.name + '</div>'
      + '<div class="aver">扫码盘库 · 离线可用 · 一键导出 Excel</div>'
      + '<ul class="kv">' + kv + '</ul>'
      + '<p class="copy">数据仅保存在本机，不上传任何服务器。<br>© 2026 ' + APP_INFO.author + '　保留所有权利</p>'
      + '</div>';
    dialog({
      title: '关于',
      body: body,
      buttons: [{ label: '关闭', value: null }]
    });
  }

  /* ---------- 提示音 / 震动 ---------- */

  /* ---------- 更新与识别画像 ---------- */

  function checkUpdate() {
    if (!bridge || typeof bridge.checkUpdate !== 'function') {
      dialog({
        title: '检查更新',
        sub: '网页版没有安装包，刷新页面就是最新版。',
        buttons: [{ label: '知道了', cls: 'primary', value: 'ok' }]
      });
      return;
    }
    toast('正在检查更新…', 2500);
    try { bridge.checkUpdate(); } catch (e) { toast('检查失败：' + e.message); }
  }

  // 原生层回调：查到新版本 / 已是最新 / 出错
  window.__updateInfo = function (json) {
    var info = null;
    try { info = JSON.parse(json); } catch (e) {}
    if (!info || info.error) {
      dialog({
        title: '检查更新失败',
        sub: info && info.error ? String(info.error) : '网络不可用，稍后再试',
        buttons: [{ label: '知道了', value: 'ok' }]
      });
      return;
    }
    if (!info.hasUpdate) {
      dialog({
        title: '已是最新版本',
        sub: '当前 V' + APP_INFO.version + (info.versionName ? '（最新 V' + info.versionName + '）' : ''),
        buttons: [{ label: '好', cls: 'primary', value: 'ok' }]
      });
      return;
    }
    var note = String(info.notes || '稳定性与识别率改进').replace(/</g, '&lt;').replace(/\n/g, '<br>');
    dialog({
      title: '发现新版本 V' + info.versionName,
      sub: info.size ? ('安装包约 ' + info.size) : '',
      body: '<div class="help"><p class="lead">更新内容</p><p>' + note + '</p></div>',
      buttons: [
        { label: '以后再说', value: null },
        {
          label: '下载并安装', cls: 'primary', value: 'go',
          onClick: function () {
            toast('开始下载…', 2500);
            try { bridge.downloadUpdate(); } catch (e) { toast('下载失败：' + e.message); }
          }
        }
      ]
    });
  };

  window.__updateProgress = function (pct) {
    var el = $('toast');
    el.textContent = '下载中 ' + pct + '%';
    el.classList.add('show');
  };

  window.__updateDone = function (msg) {
    toast(msg || '下载完成', 3000);
  };

  function showScanProfile() {
    if (!bridge || typeof bridge.getScanProfile !== 'function') {
      dialog({
        title: '本机识别画像',
        sub: '网页版不经过原生解码，没有画像数据。',
        buttons: [{ label: '知道了', cls: 'primary', value: 'ok' }]
      });
      return;
    }
    var p = {};
    try { p = JSON.parse(bridge.getScanProfile() || '{}'); } catch (e) {}
    var rows = [
      ['机型', p.device || '-'],
      ['预览分辨率', p.preview || '-'],
      ['当前变焦', p.zoom || '-'],
      ['累计处理帧', p.frames || 0],
      ['快速通道命中', p.fast || 0],
      ['扫描带命中', p.band || 0],
      ['自动定位命中', p.located || 0],
      ['全幅兜底命中', p.multi || 0],
      ['平均出码耗时', (p.avgMs || 0) + ' ms']
    ];
    var html = '<div class="help"><ul class="kvl">' + rows.map(function (r) {
      return '<li><span>' + r[0] + '</span><b>' + r[1] + '</b></li>';
    }).join('') + '</ul>'
      + '<p class="lead">这些数字只存在这台手机上，用来决定下次先扫哪里——'
      + '每台手机各学各的，不会互相干扰。</p></div>';
    dialog({
      title: '本机识别画像',
      body: html,
      buttons: [
        { label: '重置', value: 'reset' },
        { label: '好', cls: 'primary', value: 'ok' }
      ],
      onClose: function (v) {
        if (v === 'reset' && typeof bridge.resetScanProfile === 'function') {
          try { bridge.resetScanProfile(); } catch (e) {}
          toast('已重置本机画像');
        }
      }
    });
  }

  /**
   * 从已有记录里学 SN 的「长度 + 前缀」，交给原生层放宽/收紧格式校验。
   * 只作为白名单的补充，不会把已经能读的码挡掉。
   */
  function syncSnHint() {
    if (!nativeScan || !bridge || typeof bridge.setSnHint !== 'function') return;
    var recent = state.records.slice(-60).filter(function (r) { return r.sn && r.sn.length >= 10; });
    if (recent.length < 5) return;
    var cnt = {}, best = '', bestN = 0;
    recent.forEach(function (r) {
      var k = r.sn.slice(0, 3);
      cnt[k] = (cnt[k] || 0) + 1;
      if (cnt[k] > bestN) { bestN = cnt[k]; best = k; }
    });
    var lenN = {}, len = 0, lenMax = 0;
    recent.forEach(function (r) {
      lenN[r.sn.length] = (lenN[r.sn.length] || 0) + 1;
      if (lenN[r.sn.length] > lenMax) { lenMax = lenN[r.sn.length]; len = r.sn.length; }
    });
    if (bestN < recent.length * 0.6) return;
    try { bridge.setSnHint(best, len); } catch (e) {}
  }

  var audioCtx = null;
  function beep(ok) {
    if (!state.opts.sound) return;
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
      var o = audioCtx.createOscillator();
      var g = audioCtx.createGain();
      o.type = 'square';
      o.frequency.value = ok ? 1180 : 320;
      g.gain.value = 0.05;
      o.connect(g); g.connect(audioCtx.destination);
      o.start();
      setTimeout(function () { o.stop(); }, ok ? 70 : 180);
    } catch (e) {}
  }

  function vibrate(pattern) {
    if (!state.opts.vibe) return;
    if (navigator.vibrate) { try { navigator.vibrate(pattern); } catch (e) {} }
  }

  /* ---------- 渲染 ---------- */

  function renderFields() {
    var c = state.current;
    var vp = $('valPallet');
    vp.textContent = c.pallet || '点击设置';
    vp.className = 'val' + (c.pallet ? '' : ' empty');
    var vt = $('valPart');
    vt.textContent = c.part ? (c.part + (c.name ? ' · ' + shortName(c.part, c.name) : '')) : '点击选择';
    vt.className = 'val' + (c.part ? '' : ' empty');
  }

  function renderStatusChips() {
    var box = $('statusChips');
    box.innerHTML = '';
    state.statuses.forEach(function (s) {
      var b = document.createElement('button');
      b.className = 'chip' + (state.current.status === s ? ' on' : '');
      b.textContent = s;
      b.addEventListener('click', function () {
        state.current.status = s;
        save();
        renderStatusChips();
        renderStatusEditor();
      });
      box.appendChild(b);
    });
  }

  function renderStatusEditor() {
    var box = $('statusEditor');
    box.innerHTML = '';
    state.statuses.forEach(function (s) {
      var b = document.createElement('button');
      b.className = 'chip';
      b.textContent = s + ' ×';
      b.title = '点击删除该状态';
      b.addEventListener('click', function () {
        if (state.statuses.length <= 1) { toast('至少保留一个状态'); return; }
        confirmDialog('删除状态', '确定删除「' + s + '」？已记录的历史数据不会改动。', '删除', true).then(function (ok) {
          if (!ok) return;
          state.statuses = state.statuses.filter(function (x) { return x !== s; });
          if (state.current.status === s) state.current.status = state.statuses[0];
          save(); renderStatusChips(); renderStatusEditor();
        });
      });
      box.appendChild(b);
    });
  }

  function emptyLi(text) {
    var li = document.createElement('li');
    li.style.cssText = 'display:block;padding:0;background:transparent;border-bottom:none;animation:none';
    var d = document.createElement('div');
    d.className = 'empty';
    d.textContent = text;
    li.appendChild(d);
    return li;
  }

  function fmtTime(ts) {
    if (!ts) return '';
    var d = new Date(ts);
    return (d.getMonth() + 1) + '-' + d.getDate() + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }

  /**
   * 删一条就记进撤销缓冲：扫错了手快点两下不至于丢数据。
   * 缓冲只留最近一次，5 秒内可撤销。
   */
  var undoBuf = null;

  function deleteRecord(r) {
    var i = -1;
    for (var k = 0; k < state.records.length; k++) {
      if (state.records[k].id === r.id) { i = k; break; }
    }
    if (i < 0) return;
    undoBuf = { rec: r, at: i };
    state.records.splice(i, 1);
    save(); renderAll();
    toastAction('已删除 ' + r.sn, '撤销', function () {
      if (!undoBuf || undoBuf.rec.id !== r.id) return;
      state.records.splice(Math.min(undoBuf.at, state.records.length), 0, undoBuf.rec);
      undoBuf = null;
      save(); renderAll();
      toast('已恢复 ' + r.sn);
    });
    try { if (nativeScan && bridge.setScanCount) bridge.setScanCount(palletCount()); } catch (e) {}
  }

  function recRow(r, idx, showMeta, editable) {
    var li = document.createElement('li');
    var n = document.createElement('span');
    n.className = 'idx';
    n.textContent = idx;
    var sn = document.createElement('span');
    sn.className = 'sn';
    sn.textContent = r.sn;
    if (showMeta) {
      var small = document.createElement('small');
      small.textContent = [r.pallet, r.part, shortName(r.part, r.name), r.status, fmtTime(r.ts)]
        .filter(Boolean).join(' · ');
      sn.appendChild(small);
    }
    if (editable) {
      li.classList.add('tappable');
      sn.addEventListener('click', function () { editRecord(r); });
    }
    var x = document.createElement('button');
    x.className = 'x';
    x.textContent = '×';
    x.addEventListener('click', function () { deleteRecord(r); });
    li.appendChild(n); li.appendChild(sn);
    if (editable) {
      var e = document.createElement('button');
      e.className = 'pen';
      e.textContent = '✎';
      e.addEventListener('click', function () { editRecord(r); });
      li.appendChild(e);
    }
    li.appendChild(x);
    return li;
  }

  function renderScanList() {
    var p = state.current.pallet;
    var ul = $('scanList');
    ul.innerHTML = '';
    var recs = state.records.filter(function (r) { return r.pallet === p; })
      .sort(function (a, b) { return a.ts - b.ts; });
    $('cntPallet').textContent = recs.length;
    $('palletLabel').textContent = p ? ('栈板 ' + p) : '未设置栈板';
    if (!p) { ul.appendChild(emptyLi('先填写栈板编码，再开始扫码')); return; }
    if (!recs.length) { ul.appendChild(emptyLi('还没有记录，对准条码开始扫描')); return; }
    for (var i = recs.length - 1; i >= 0; i--) {
      ul.appendChild(recRow(recs[i], i + 1, false));
    }
  }

  function renderTopCount() {
    $('topCount').textContent = state.records.length;
  }

  /* ---------- 明细：按栈板分组的管理 ---------- */

  /** 打开了哪些栈板卡片；默认只展开当前栈板 */
  var palletOpen = {};

  function usedPallets() {
    var out = [];
    state.records.forEach(function (r) {
      var k = r.pallet || '';
      if (k && out.indexOf(k) < 0) out.push(k);
    });
    out.sort();
    return out;
  }

  function groupByPallet(list) {
    var map = {}, order = [];
    list.forEach(function (r) {
      var k = r.pallet || '';
      if (!map[k]) { map[k] = []; order.push(k); }
      map[k].push(r);
    });
    order.sort(function (a, b) {
      return String(a).localeCompare(String(b), 'zh', { numeric: true });
    });
    return order.map(function (k) { return { pallet: k, recs: map[k] }; });
  }

  function statusSummary(recs) {
    var m = {}, order = [];
    recs.forEach(function (r) {
      var s = r.status || '未标';
      if (m[s] == null) { m[s] = 0; order.push(s); }
      m[s]++;
    });
    return order.map(function (s) { return s + ' ' + m[s]; }).join(' / ');
  }

  /** 改名：整板改，同时把重名的两板合并的判断交给用户 */
  function renamePallet(p) {
    var recs = state.records.filter(function (r) { return (r.pallet || '') === p; });
    textDialog({
      title: '修改栈板编码',
      sub: '这一板的 ' + recs.length + ' 条记录会一起改',
      value: p,
      placeholder: '例如：栈板 1 / PLT-001',
      suggestions: usedPallets()
    }).then(function (v) {
      if (v === null || v === p) return;
      var exist = state.records.some(function (r) { return (r.pallet || '') === v; });
      var doIt = function () {
        recs.forEach(function (r) { r.pallet = v; });
        if (state.current.pallet === p) state.current.pallet = v;
        palletOpen[v] = true;
        delete palletOpen[p];
        save(); renderAll(); renderFields();
        toast('已改为 ' + (v || '(空)'));
      };
      if (exist) {
        confirmDialog('合并栈板', '已经有一个叫「' + v + '」的栈板，改完两板会合并在一起。', '合并', true)
          .then(function (ok) { if (ok) doIt(); });
      } else doIt();
    });
  }

  function deletePallet(p) {
    var recs = state.records.filter(function (r) { return (r.pallet || '') === p; });
    if (!recs.length) return;
    confirmDialog('删除整个栈板',
      '「' + (p || '(空)') + '」下的 ' + recs.length + ' 条记录会全部删除，无法恢复。建议先导出这一板。',
      '确认删除', true).then(function (ok) {
      if (!ok) return;
      state.records = state.records.filter(function (r) { return (r.pallet || '') !== p; });
      delete palletOpen[p];
      save(); renderAll();
      try { if (nativeScan && bridge.setScanCount) bridge.setScanCount(palletCount()); } catch (e) {}
      toast('已删除栈板 ' + (p || '(空)') + '（' + recs.length + ' 条）');
    });
  }

  function exportPallet(p) {
    var recs = state.records.filter(function (r) { return (r.pallet || '') === p; })
      .sort(function (a, b) { return a.ts - b.ts; });
    if (!recs.length) { toast('这一板还没有记录'); return; }
    var file = makeXlsx(recs, '盘库_' + (p || '空栈板') + '_' + stamp() + '.xlsx');
    exportDialog(file, recs.length);
  }

  function editRecord(r) {
    var wrap = document.createElement('div');
    var inputs = {};
    [['sn', 'SN', true], ['part', '料号'], ['name', '名称']].forEach(function (f) {
      var lab = document.createElement('label');
      lab.className = 'fld';
      var s = document.createElement('span');
      s.textContent = f[1];
      var i = document.createElement('input');
      i.type = 'text';
      i.value = r[f[0]] || '';
      i.placeholder = f[1];
      if (f[2]) i.setAttribute('autocapitalize', 'characters');
      lab.appendChild(s);
      lab.appendChild(i);
      wrap.appendChild(lab);
      inputs[f[0]] = i;
    });

    var chips = document.createElement('div');
    chips.className = 'chips';
    chips.style.marginTop = '10px';
    state.statuses.forEach(function (st) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip' + (r.status === st ? ' on' : '');
      b.textContent = st;
      b.addEventListener('click', function () {
        Array.prototype.forEach.call(chips.children, function (c) { c.classList.remove('on'); });
        b.classList.add('on');
        inputs.status.value = st;
      });
      chips.appendChild(b);
    });
    var stInput = document.createElement('input');
    stInput.type = 'hidden';
    stInput.value = r.status || '';
    inputs.status = stInput;
    wrap.appendChild(chips);
    wrap.appendChild(stInput);

    dialog({
      title: '修改记录',
      sub: '栈板 ' + (r.pallet || '(空)') + ' · 扫于 ' + fmtTime(r.ts),
      body: wrap,
      buttons: [
        { label: '取消', value: null },
        {
          label: '保存', cls: 'primary', value: 'ok',
          onClick: function () {
            var sn = inputs.sn.value.trim();
            if (!sn) { inputs.sn.focus(); return false; }
            r.sn = sn;
            r.part = inputs.part.value.trim();
            r.name = inputs.name.value.trim();
            r.status = inputs.status.value.trim() || r.status;
            save(); renderAll();
            toast('已保存');
          }
        }
      ]
    });
  }

  function palletCard(g, searching) {
    var p = g.pallet;
    var recs = g.recs.slice().sort(function (a, b) { return b.ts - a.ts; });
    var open = searching ? true
      : (palletOpen[p] !== undefined ? palletOpen[p] : (p === state.current.pallet));

    var card = document.createElement('div');
    card.className = 'pcard' + (p === state.current.pallet ? ' cur' : '');

    var head = document.createElement('div');
    head.className = 'phead';

    var main = document.createElement('button');
    main.className = 'pmain';
    main.innerHTML = '';
    var t = document.createElement('div');
    t.className = 'ptitle';
    t.textContent = (p || '(空栈板)') + '  ·  ' + recs.length + ' 条';
    var sub = document.createElement('div');
    sub.className = 'psub';
    sub.textContent = statusSummary(recs);
    main.appendChild(t);
    main.appendChild(sub);
    main.addEventListener('click', function () {
      palletOpen[p] = !open;
      renderAllList();
    });

    var acts = document.createElement('div');
    acts.className = 'pact';
    [['改名', function () { renamePallet(p); }],
      ['导出', function () { exportPallet(p); }],
      ['删除', function () { deletePallet(p); }, 'danger']].forEach(function (a) {
      var b = document.createElement('button');
      b.className = 'mini' + (a[2] ? ' ' + a[2] : '');
      b.textContent = a[0];
      b.addEventListener('click', function (e) { e.stopPropagation(); a[1](); });
      acts.appendChild(b);
    });

    head.appendChild(main);
    head.appendChild(acts);
    card.appendChild(head);

    if (open) {
      var ul = document.createElement('ul');
      ul.className = 'rec-list';
      recs.forEach(function (r, i) { ul.appendChild(recRow(r, recs.length - i, false, true)); });
      card.appendChild(ul);
    }
    return card;
  }

  function renderAllList() {
    var sel = $('filterPallet');
    var cur = sel.value;
    var pallets = [];
    state.records.forEach(function (r) {
      var k = r.pallet || '';
      if (pallets.indexOf(k) < 0) pallets.push(k);
    });
    pallets.sort();
    sel.innerHTML = '';
    var optAll = document.createElement('option');
    optAll.value = '__all';
    optAll.textContent = '全部栈板（' + state.records.length + ' 条）';
    sel.appendChild(optAll);
    pallets.forEach(function (p) {
      var o = document.createElement('option');
      o.value = p;
      o.textContent = (p || '(空栈板)');
      sel.appendChild(o);
    });
    if (cur && cur !== '__all' && pallets.indexOf(cur) >= 0) sel.value = cur;
    else sel.value = '__all';

    var filter = sel.value;
    var q = (($('searchBox') && $('searchBox').value) || '').trim().toLowerCase();
    var recs = state.records.filter(function (r) {
      var pal = r.pallet || '';
      if (filter !== '__all' && pal !== filter) return false;
      if (!q) return true;
      return (r.sn || '').toLowerCase().indexOf(q) >= 0
        || (r.part || '').toLowerCase().indexOf(q) >= 0
        || (r.name || '').toLowerCase().indexOf(q) >= 0
        || pal.toLowerCase().indexOf(q) >= 0;
    });

    $('statTotal').textContent = state.records.length;
    $('statPallet').textContent = pallets.length;
    var kb = Math.round(JSON.stringify(state.records).length / 1024);
    $('statSize').textContent = kb + ' KB';

    var box = $('palletCards');
    box.innerHTML = '';
    if (!recs.length) {
      box.appendChild(emptyLi(state.records.length ? '没有匹配的记录' : '暂无记录'));
      return;
    }
    groupByPallet(recs).forEach(function (g) { box.appendChild(palletCard(g, !!q)); });
  }

  function renderParts() {
    $('partCount').textContent = state.parts.length + ' 项';
    var ul = $('partList');
    ul.innerHTML = '';
    if (!state.parts.length) {
      var li = document.createElement('li');
      li.innerHTML = '<span style="color:#A6AEBA;font-size:13px">还没有料号，点「导入表格」上传你的料号对照表</span>';
      ul.appendChild(li);
      return;
    }
    state.parts.slice().sort(function (a, b) {
      return a.code.localeCompare(b.code) || String(a.name).localeCompare(String(b.name), 'zh', { numeric: true });
    }).forEach(function (p) {
      var li = document.createElement('li');
      var c = document.createElement('span');
      c.className = 'code';
      c.textContent = p.code;
      var n = document.createElement('span');
      n.className = 'name';
      n.textContent = shortName(p.code, p.name);
      var x = document.createElement('button');
      x.className = 'x';
      x.textContent = '×';
      var k = partKey(p);
      x.addEventListener('click', function () {
        state.parts = state.parts.filter(function (it) { return partKey(it) !== k; });
        save(); renderParts();
      });
      li.appendChild(c); li.appendChild(n); li.appendChild(x);
      ul.appendChild(li);
    });
  }

  function renderOpts() {
    $('chkQr').checked = !!state.opts.qr;
    $('chkDedupe').checked = !!state.opts.dedupe;
    $('chkSound').checked = !!state.opts.sound;
    $('chkVibe').checked = !!state.opts.vibe;
    $('chkStrip').checked = state.opts.stripPrefix !== false;
  }

  function renderAll() {
    renderFields();
    renderStatusChips();
    renderStatusEditor();
    renderScanList();
    renderAllList();
    renderParts();
    renderTopCount();
  }

  /* ---------- 扫码 ---------- */

  var video = $('video');
  var stream = null;
  var reader = null;
  var scanning = false;
  var paused = false;
  var torchOn = false;
  var lastText = '';
  var lastTs = 0;

  function buildHints() {
    var B = ZXing.BarcodeFormat;
    var fmts = [B.CODE_128, B.CODE_39, B.CODE_93, B.EAN_13, B.EAN_8, B.ITF, B.CODABAR, B.UPC_A, B.UPC_E];
    if (state.opts.qr) fmts.push(B.QR_CODE);
    var hints = new Map();
    hints.set(ZXing.DecodeHintType.POSSIBLE_FORMATS, fmts);
    return hints;
  }

  function onDecode(text) {
    if (paused) return;
    text = String(text || '').trim();
    if (!text) return;
    var now = Date.now();
    if (text === lastText && now - lastTs < 2600) { nativeFeedback(3, text); return; }
    lastText = text; lastTs = now;

    if (!state.current.pallet) {
      if (!nativeScan) { beep(false); vibrate([90, 60, 90]); }
      nativeFeedback(2, text);
      toast('请先设置栈板编码');
      return;
    }

    if (state.opts.dedupe) {
      var dup = state.records.some(function (r) {
        return r.pallet === state.current.pallet && r.sn === text;
      });
      if (dup) {
        if (!nativeScan) { beep(false); vibrate([80, 50, 80, 50, 80]); }
        nativeFeedback(0, text);
        toast('重复 SN，已跳过：' + text, 2200);
        return;
      }
    }

    state.records.push({
      id: 'r' + (now) + '_' + (uid++),
      pallet: state.current.pallet,
      status: state.current.status,
      part: state.current.part,
      name: state.current.name,
      sn: text,
      ts: now
    });
    save();
    if (!nativeScan) { beep(true); vibrate(40); }
    nativeFeedback(1, text);
    renderScanList(); renderAllList(); renderTopCount();
    syncNativeCount();
    toast('已记录 ' + text, 1000);
  }

  // 把判定结果回传给原生层，由原生出提示音和扫框下方的文字
  // 1 已记录 / 0 重复 / 2 未设栈板 / 3 忽略
  function nativeFeedback(code, text) {
    if (!nativeScan || !bridge || typeof bridge.scanFeedback !== 'function') return;
    try { bridge.scanFeedback(code, String(text || '')); } catch (e) {}
  }

  function palletCount() {
    return state.records.filter(function (r) { return r.pallet === state.current.pallet; }).length;
  }

  function syncNativeCount() {
    if (nativeScan && bridge && bridge.setScanCount) {
      try { bridge.setScanCount(palletCount()); } catch (e) {}
    }
  }

  // 原生扫码层的回调入口
  window.__nativeScan = function (text) { onDecode(String(text || '')); };
  window.__scanStopped = function () { resetScanUI(); };
  window.__scanError = function (msg) {
    toast('扫码出错：' + msg, 3000);
    resetScanUI();
  };

  function startCamera() {
    if (scanning) return;

    // APK 里走原生相机，比 WebView 的 getUserMedia 稳定得多
    if (nativeScan) {
      scanning = true;
      syncNativeCount();
      $('scanState').innerHTML = '<p>相机已打开，正在连续扫描<br>扫完点「结束扫描」回到这里</p>';
      $('btnTorch').disabled = true;
      $('btnPause').disabled = true;
      try {
        if (bridge && typeof bridge.setFeedback === 'function') {
          bridge.setFeedback(!!state.opts.sound, !!state.opts.vibe);
        }
      } catch (e) {}
      try { bridge.startScan(!!state.opts.qr); } catch (e) {
        scanning = false;
        toast('相机启动失败：' + e.message, 3000);
      }
      return;
    }

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      $('scanState').innerHTML = '<p>当前环境不支持网页调用摄像头<br>建议用 Chrome 或 Edge 打开</p>'
        + '<button class="btn primary big" id="btnStart">重试</button>';
      $('btnStart').addEventListener('click', startCamera);
      return;
    }
    $('btnStart').textContent = '正在启动…';
    navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } }
    }).then(function (s) {
      stream = s;
      video.srcObject = s;
      return video.play();
    }).then(function () {
      scanning = true;
      $('scanner').classList.add('on');
      $('btnTorch').disabled = false;
      $('btnPause').disabled = false;
      $('btnPause').textContent = '暂停';
      paused = false;
      try {
        reader = new ZXing.BrowserMultiFormatReader(buildHints(), 300);
        var p = reader.decodeFromVideoElementContinuously(video, function (result) {
          if (result) onDecode(result.getText());
        });
        if (p && p.catch) p.catch(function () {});
      } catch (e) {
        toast('扫码引擎启动失败：' + e.message, 3000);
      }
      checkTorch();
    }).catch(function (err) {
      var msg = '摄像头打开失败';
      if (err && /NotAllowed|Permission|Security/i.test(err.name || '')) msg = '未授权摄像头权限';
      else if (err && /NotFound|Devices/i.test(err.name || '')) msg = '没有找到可用摄像头';
      else if (err && /NotReadable|Track/i.test(err.name || '')) msg = '摄像头被其他应用占用，请关掉后台的相机/视频通话再试';
      var detail = err && err.name ? '<br><small style="opacity:.55">' + err.name + (err.message ? ': ' + err.message : '') + '</small>' : '';
      $('scanState').innerHTML = '<p>' + msg + detail + '</p>'
        + '<button class="btn primary big" id="btnStart">重试</button>';
      $('btnStart').addEventListener('click', startCamera);
    });
  }

  function checkTorch() {
    try {
      var track = stream.getVideoTracks()[0];
      var caps = track.getCapabilities ? track.getCapabilities() : {};
      if (!caps || !caps.torch) { $('btnTorch').disabled = true; }
    } catch (e) { $('btnTorch').disabled = true; }
  }

  function resetScanUI() {
    scanning = false;
    torchOn = false;
    $('scanner').classList.remove('on');
    $('btnTorch').disabled = true;
    $('btnPause').disabled = true;
    var tip = nativeScan ? '点下方按钮打开相机，可连续扫' : '摄像头未开启';
    var label = nativeScan ? '开始扫描' : '开启摄像头';
    $('scanState').innerHTML = '<p>' + tip + '</p>'
      + '<button class="btn primary big" id="btnStart">' + label + '</button>';
    $('btnStart').addEventListener('click', startCamera);
  }

  function stopCamera() {
    if (nativeScan && bridge) { try { bridge.stopScan(); } catch (e) {} }
    if (reader) { try { reader.reset(); } catch (e) {} reader = null; }
    if (stream) {
      stream.getTracks().forEach(function (t) { try { t.stop(); } catch (e) {} });
      stream = null;
    }
    video.srcObject = null;
    resumeScan = false;
    resetScanUI();
  }

  /* ---------- 导出 ---------- */

  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function stamp() {
    var d = new Date();
    return d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) + '_' + pad(d.getHours()) + pad(d.getMinutes());
  }

  function buildAOA(recs) {
    var rows = [['栈板编码', '状态', '料号', '名称', 'SN']];
    recs.forEach(function (r) {
      rows.push([r.pallet, r.status, r.part, shortName(r.part, r.name), r.sn]);
    });
    return rows;
  }

  function makeXlsx(recs, filename) {
    var ws = XLSX.utils.aoa_to_sheet(buildAOA(recs));
    ws['!cols'] = [{ wch: 14 }, { wch: 10 }, { wch: 16 }, { wch: 24 }, { wch: 30 }];
    var wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, '盘库');
    var buf = XLSX.write(wb, { bookType: 'xlsx', type: 'array', compression: true });
    return new File([buf], filename, { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
  }

  function download(file) {
    if (bridge) {
      fileToBase64(file).then(function (b64) {
        var r = bridge.saveFile(b64, file.name, file.type || 'application/octet-stream');
        if (String(r).indexOf('ok') === 0) toast('已保存到手机的「下载」目录', 2600);
        else toast('保存失败：' + r, 3000);
      }).catch(function () { toast('保存失败'); });
      return;
    }
    var url = URL.createObjectURL(file);
    var a = document.createElement('a');
    a.href = url;
    a.download = file.name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 4000);
    toast('已保存到手机的「下载」目录', 2600);
  }

  function shareFile(file) {
    if (bridge) {
      fileToBase64(file).then(function (b64) {
        bridge.shareFile(b64, file.name, file.type || 'application/octet-stream');
      }).catch(function () { toast('分享失败'); });
      return;
    }
    if (navigator.canShare && navigator.canShare({ files: [file] })) {
      navigator.share({ files: [file], title: file.name }).catch(function () {});
    } else {
      toast('当前浏览器不支持直接分享，改为保存文件');
      download(file);
    }
  }

  function exportDialog(file, count) {
    dialog({
      title: '导出完成',
      sub: count + ' 条记录 · ' + file.name,
      body: '<p style="font-size:13px;color:#6B7480;line-height:1.7;margin:0">选「保存」存到手机下载目录；选「分享」可直接发到微信、企业微信或邮件。</p>',
      buttons: [
        { label: '取消', value: null },
        { label: '保存', value: 'save' },
        { label: '分享', cls: 'primary', value: 'share' }
      ],
      onClose: function (v) {
        if (v === 'save') download(file);
        else if (v === 'share') shareFile(file);
      }
    });
  }

  function exportXlsx() {
    if (!state.records.length) { toast('还没有可导出的记录'); return; }
    var pallets = [];
    state.records.forEach(function (r) { if (pallets.indexOf(r.pallet) < 0) pallets.push(r.pallet); });

    function go(recs, tag) {
      var file = makeXlsx(recs, '盘库_' + (tag ? tag + '_' : '') + stamp() + '.xlsx');
      exportDialog(file, recs.length);
    }

    if (pallets.length > 1) {
      dialog({
        title: '导出范围',
        sub: '共 ' + state.records.length + ' 条记录，' + pallets.length + ' 个栈板',
        buttons: [
          { label: '取消', value: null },
          { label: '当前栈板', value: 'cur' },
          { label: '全部导出', cls: 'primary', value: 'all' }
        ],
        onClose: function (v) {
          if (v === 'all') go(state.records.slice(), '');
          else if (v === 'cur') {
            var p = state.current.pallet;
            var recs = state.records.filter(function (r) { return r.pallet === p; });
            if (!recs.length) { toast('当前栈板还没有记录'); return; }
            go(recs, p);
          }
        }
      });
    } else {
      go(state.records.slice(), '');
    }
  }

  function exportCsv() {
    if (!state.records.length) { toast('还没有可导出的记录'); return; }
    var lines = ['栈板编码,状态,料号,名称,SN,记录时间'];
    state.records.slice().sort(function (a, b) { return a.ts - b.ts; }).forEach(function (r) {
      var d = new Date(r.ts);
      var t = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
      lines.push([r.pallet, r.status, r.part, r.name || '', r.sn, t].map(function (x) {
        x = String(x == null ? '' : x);
        return /[",\n]/.test(x) ? '"' + x.replace(/"/g, '""') + '"' : x;
      }).join(','));
    });
    var blob = new File(['\ufeff' + lines.join('\r\n')], '盘库备份_' + stamp() + '.csv', { type: 'text/csv' });
    download(blob);
  }

  /* ---------- 导入 ---------- */

  function parsePartRows(rows) {
    rows = rows.filter(function (r) { return r && r.some(function (c) { return String(c == null ? '' : c).trim() !== ''; }); });
    if (!rows.length) return [];
    var head = rows[0].map(function (c) { return String(c == null ? '' : c).trim(); });
    var ci = -1, ni = -1, start = 1;
    head.forEach(function (h, i) {
      if (ci < 0 && /料号|物料|编码|型号|part\s*no|partno|p\/n|item/i.test(h)) ci = i;
      if (ni < 0 && /名称|品名|描述|name|desc|description/i.test(h)) ni = i;
    });
    if (ci < 0) { ci = 0; start = 0; }
    if (ni < 0) ni = ci + 1;
    var out = [];
    for (var i = start; i < rows.length; i++) {
      var code = String(rows[i][ci] == null ? '' : rows[i][ci]).trim();
      if (!code) continue;
      if (/^栈板/.test(code)) continue;
      var name = String(rows[i][ni] == null ? '' : rows[i][ni]).trim();
      out.push({ code: code, name: name });
    }
    return out;
  }

  function importTable(file, mode) {
    var rd = new FileReader();
    rd.onload = function () {
      try {
        var wb = XLSX.read(new Uint8Array(rd.result), { type: 'array' });
        var ws = wb.Sheets[wb.SheetNames[0]];
        var rows = XLSX.utils.sheet_to_json(ws, { header: 1, defval: '' });

        if (mode === 'parts') {
          var parts = parsePartRows(rows);
          if (!parts.length) { toast('没识别到料号数据，请检查表格'); return; }
          var merged = state.parts.slice();
          var index = {};
          merged.forEach(function (p, i) { index[partKey(p)] = i; });
          var added = 0;
          parts.forEach(function (p) {
            var k = partKey(p);
            if (index[k] !== undefined) {
              if (p.name) merged[index[k]].name = p.name;
            } else {
              merged.push(p);
              index[k] = merged.length - 1;
              added++;
            }
          });
          state.parts = merged;
          save(); renderParts();
          toast('导入完成：新增 ' + added + ' 个料号，共 ' + merged.length + ' 个', 2600);
          return;
        }

        var recs = [];
        var body = rows.filter(function (r) { return r && r.some(function (c) { return String(c == null ? '' : c).trim() !== ''; }); });
        body.shift();
        body.forEach(function (r) {
          var sn = String((r[4] == null ? '' : r[4])).trim();
          if (!sn) return;
          recs.push({
            id: 'r' + Date.now() + '_' + (uid++),
            pallet: String(r[0] == null ? '' : r[0]).trim(),
            status: String(r[1] == null ? '' : r[1]).trim(),
            part: String(r[2] == null ? '' : r[2]).trim(),
            name: String(r[3] == null ? '' : r[3]).trim(),
            sn: sn,
            ts: Date.now()
          });
        });
        if (!recs.length) { toast('没识别到记录数据'); return; }
        var n = 0;
        recs.forEach(function (rec) {
          var dup = state.records.some(function (x) { return x.sn === rec.sn && x.pallet === rec.pallet; });
          if (!dup) { state.records.push(rec); n++; }
        });
        save(); renderAll();
        toast('导入 ' + n + ' 条记录（跳过重复 ' + (recs.length - n) + ' 条）', 2600);
      } catch (e) {
        toast('文件解析失败：' + e.message, 3000);
      }
    };
    rd.readAsArrayBuffer(file);
  }

  /* ---------- 事件绑定 ---------- */

  function setView(name) {
    document.querySelectorAll('.view').forEach(function (v) { v.classList.remove('active'); });
    var el = $('view-' + name);
    if (el) el.classList.add('active');
    document.querySelectorAll('.tab').forEach(function (t) {
      t.classList.toggle('active', t.getAttribute('data-view') === name);
    });
    if (name === 'list') renderAllList();
    if (name === 'setup') { renderParts(); renderStatusEditor(); renderOpts(); }
    if (name !== 'scan' && scanning) {
      paused = true;
      $('btnPause').textContent = '继续';
    }
  }

  function pickPallet() {
    var used = [];
    state.records.forEach(function (r) { if (r.pallet && used.indexOf(r.pallet) < 0) used.push(r.pallet); });
    textDialog({
      title: '栈板编码',
      sub: '手动输入当前扫描的栈板编号',
      value: state.current.pallet,
      placeholder: '例如：栈板 1 / PLT-001',
      upper: true,
      suggestions: used
    }).then(function (v) {
      if (v == null) return;
      state.current.pallet = v;
      save(); renderScanList(); renderFields();
      toast('已切换到栈板 ' + v);
    });
  }

  function pickPart() {
    var wrap = document.createElement('div');
    var search = document.createElement('input');
    search.type = 'search';
    search.placeholder = '搜索料号或名称';
    wrap.appendChild(search);
    var ul = document.createElement('ul');
    ul.className = 'opt-list';
    wrap.appendChild(ul);
    var picked = null;

    function render(kw) {
      ul.innerHTML = '';
      kw = (kw || '').toLowerCase();
      var list = state.parts.filter(function (p) {
        if (!kw) return true;
        return p.code.toLowerCase().indexOf(kw) >= 0 || (p.name || '').toLowerCase().indexOf(kw) >= 0;
      });
      if (!list.length) {
        var li = document.createElement('li');
        li.style.color = '#A6AEBA';
        li.textContent = state.parts.length ? '没有匹配的料号' : '还没有料号，请到「设置」导入料号表';
        ul.appendChild(li);
        return;
      }
      list.slice(0, 300).forEach(function (p) {
        var li = document.createElement('li');
        var c = document.createElement('span');
        c.className = 'code';
        c.textContent = p.code;
        var n = document.createElement('span');
        n.className = 'n';
        n.textContent = shortName(p.code, p.name);
        li.appendChild(c); li.appendChild(n);
        li.addEventListener('click', function () {
          picked = { code: p.code, name: p.name || '' };
          d.close('pick');
        });
        ul.appendChild(li);
      });
    }
    render('');
    search.addEventListener('input', function () { render(search.value); });

    var d = dialog({
      title: '选择料号',
      sub: '同一料号对应多个型号，请选中具体那一行',
      body: wrap,
      buttons: [
        { label: '取消', value: null },
        { label: '手动输入', value: 'manual' }
      ],
      onClose: function (v) {
        if (v === 'pick' && picked) {
          state.current.part = picked.code;
          state.current.name = picked.name;
          save(); renderFields();
          toast('料号：' + picked.code + (picked.name ? ' · ' + shortName(picked.code, picked.name) : ''));
        } else if (v === 'manual') {
          textDialog({
            title: '手动输入料号',
            sub: '输完料号后再输名称（可留空）',
            placeholder: '料号'
          }).then(function (code) {
            if (!code) return;
            textDialog({ title: '设备名称', sub: '料号 ' + code + ' 对应的名称', placeholder: '例如：台式机 5代' })
              .then(function (name) {
                state.current.part = code;
                state.current.name = name || '';
                var k = partKey({ code: code, name: name || '' });
                var exist = state.parts.some(function (p) { return partKey(p) === k; });
                if (!exist) { state.parts.push({ code: code, name: name || '' }); renderParts(); }
                save(); renderFields();
              });
          });
        }
      }
    });
  }

  function bind() {
    document.querySelectorAll('.tab').forEach(function (t) {
      t.addEventListener('click', function () { setView(t.getAttribute('data-view')); });
    });

    $('btnPallet').addEventListener('click', pickPallet);
    $('btnPart').addEventListener('click', pickPart);
    $('btnHelp').addEventListener('click', showHelp);
    $('btnHelp2').addEventListener('click', showHelp);
    $('btnAbout').addEventListener('click', showAbout);
    $('btnCheckUpdate').addEventListener('click', checkUpdate);
    $('btnScanProfile').addEventListener('click', showScanProfile);
    $('searchBox').addEventListener('input', renderAllList);
    $('filterPallet').addEventListener('change', renderAllList);
    if (nativeScan) resetScanUI();
    else $('btnStart').addEventListener('click', startCamera);

    $('btnManual').addEventListener('click', function () {
      textDialog({
        title: '手动输入 SN',
        sub: '条码磨损扫不出来时使用',
        placeholder: '输入或粘贴 SN',
        upper: true
      }).then(function (v) { if (v) onDecode(v); });
    });

    $('btnTorch').addEventListener('click', function () {
      if (!stream) return;
      var track = stream.getVideoTracks()[0];
      torchOn = !torchOn;
      track.applyConstraints({ advanced: [{ torch: torchOn }] }).then(function () {
        toast(torchOn ? '手电筒已开' : '手电筒已关', 900);
      }).catch(function () {
        torchOn = false;
        toast('该设备不支持手电筒');
      });
    });

    $('btnPause').addEventListener('click', function () {
      paused = !paused;
      $('btnPause').textContent = paused ? '继续' : '暂停';
      toast(paused ? '已暂停扫描' : '继续扫描', 900);
    });

    $('filterPallet').addEventListener('change', renderAllList);

    $('btnAddPart').addEventListener('click', function () {
      textDialog({ title: '添加料号', placeholder: '料号，例如 DSK05', upper: true }).then(function (code) {
        if (!code) return;
        textDialog({ title: '设备名称', sub: '料号 ' + code, placeholder: '例如：台式机 5代' }).then(function (name) {
          var k = partKey({ code: code, name: name || '' });
          if (state.parts.some(function (p) { return partKey(p) === k; })) { toast('该料号+名称已存在'); return; }
          state.parts.push({ code: code, name: name || '' });
          save(); renderParts();
          toast('已添加 ' + code + (name ? ' · ' + name : ''));
        });
      });
    });

    $('btnImportParts').addEventListener('click', function () {
      $('fileInput').dataset.mode = 'parts';
      $('fileInput').value = '';
      $('fileInput').click();
    });

    $('btnAddStatus').addEventListener('click', function () {
      textDialog({ title: '添加状态', placeholder: '例如：待检' }).then(function (v) {
        if (!v) return;
        if (state.statuses.indexOf(v) >= 0) { toast('该状态已存在'); return; }
        state.statuses.push(v);
        save(); renderStatusChips(); renderStatusEditor();
      });
    });

    $('chkQr').addEventListener('change', function () {
      state.opts.qr = this.checked;
      save();
      if (scanning) { stopCamera(); setTimeout(startCamera, 300); }
      toast(this.checked ? '已开启二维码识别' : '已关闭二维码识别', 1200);
    });
    $('chkDedupe').addEventListener('change', function () { state.opts.dedupe = this.checked; save(); });
    $('chkSound').addEventListener('change', function () { state.opts.sound = this.checked; save(); });
    $('chkVibe').addEventListener('change', function () { state.opts.vibe = this.checked; save(); });
    $('chkStrip').addEventListener('change', function () {
      state.opts.stripPrefix = this.checked;
      save();
      renderFields(); renderParts(); renderAllList();
      toast(this.checked ? '导出名称将去掉料号前缀' : '导出名称保持原样', 1500);
    });

    $('btnExportXlsx').addEventListener('click', exportXlsx);
    $('btnExportCsv').addEventListener('click', exportCsv);
    $('btnImportCsv').addEventListener('click', function () {
      $('fileInput').dataset.mode = 'records';
      $('fileInput').value = '';
      $('fileInput').click();
    });

    $('btnClearAll').addEventListener('click', function () {
      if (!state.records.length) { toast('没有记录'); return; }
      confirmDialog('清空全部记录', '将删除 ' + state.records.length + ' 条记录，无法恢复。建议先导出备份。', '确认清空', true)
        .then(function (ok) {
          if (!ok) return;
          state.records = [];
          save(); renderAll();
          toast('已清空');
        });
    });

    $('fileInput').addEventListener('change', function () {
      var f = this.files && this.files[0];
      if (!f) return;
      importTable(f, this.dataset.mode || 'parts');
      this.value = '';
    });

    document.addEventListener('visibilitychange', function () {
      if (nativeScan) return;
      if (document.hidden) {
        if (scanning) { stopCamera(); resumeScan = true; }
      } else if (resumeScan) {
        resumeScan = false;
        setTimeout(startCamera, 250);
      }
    });
  }

  /* ---------- 启动 ---------- */

  bind();
  renderAll();
  $('verText').textContent = 'V' + APP_INFO.version;

  // 首次打开自动弹一次简版说明，之后靠顶栏「?」和设置页进入
  try {
    if (!localStorage.getItem(HELP_KEY)) setTimeout(showHelp, 700);
  } catch (e) {}

  if (!bridge && 'serviceWorker' in navigator) {
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('./sw.js').catch(function () {});
    });
  }
})();
