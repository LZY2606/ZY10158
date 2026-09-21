'use strict';

const state = {
  captures: [],
  detail: null,
  captureId: null,
  visibleMs: null,
  playing: false,
  playTimer: null,
  speed: 1,
  filterText: '',
  errorsOnly: true,
  reviewFrameId: null,
};

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : {};
  if (!res.ok) {
    const err = new Error(body.message || (`HTTP ${res.status}`));
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return body;
}

async function loadCaptures(selectId) {
  const data = await api('/api/captures');
  state.captures = data.captures;
  $('clock').textContent = `虚拟时钟 ${data.serverTimeMs} ms`;
  const sel = $('captureSelect');
  const previous = state.captureId ? String(state.captureId) : sel.value;
  sel.innerHTML = '';
  if (state.captures.length === 0) {
    sel.innerHTML = '<option value="">（无捕获，请导入）</option>';
  }
  for (const c of state.captures) {
    const opt = document.createElement('option');
    opt.value = c.id;
    opt.textContent = `#${c.id} ${c.name} (${c.frameCount} 帧, r${c.fileSha256.slice(0, 8)})`;
    sel.appendChild(opt);
  }
  if (previous && state.captures.some((c) => String(c.id) === previous)) {
    sel.value = previous;
  }
  if (!state.captureId && state.captures.length) state.captureId = state.captures[0].id;
  if (state.captureId) sel.value = String(state.captureId);
}

async function loadDetail() {
  if (!state.captureId) return;
  state.detail = await api(`/api/captures/${state.captureId}`);
  const maxT = Math.max(0, ...state.detail.frames.map((f) => f.offsetMs));
  state.visibleMs = maxT;
  const tl = $('timeline');
  tl.min = 0;
  tl.max = maxT;
  tl.value = maxT;
  tl.disabled = false;
  ['playBtn', 'stepBtn', 'resetBtn'].forEach((id) => $(id).removeAttribute('disabled'));
  render();
}

function render() {
  if (!state.detail) return;
  renderMeta();
  renderFrames();
  renderMessages();
  renderDiagnostics();
  renderPairing();
  renderBoundaries();
}

function renderMeta() {
  const c = state.detail.capture;
  const r = state.detail.latestRun;
  $('captureMeta').innerHTML =
    `SHA-256 <b title="${c.fileSha256}">${c.fileSha256.slice(0, 12)}…</b><br>` +
    `帧 ${c.frameCount} · 审核版本 v${c.reviewVersion}<br>` +
    (r ? `解析 #${r.runNumber} (基于审核 v${r.reviewVersionAtRun}) ${r.published ? '已发布' : '未发布'}` : '尚无解析');
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (ch) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}

function matchesFilter(text) {
  if (!state.filterText) return true;
  return text.toLowerCase().includes(state.filterText.toLowerCase());
}

function renderFrames() {
  const tb = $('frameTable').querySelector('tbody');
  tb.innerHTML = '';
  const errorSeq = new Set();
  for (const d of state.detail.diagnostics) {
    if (d.frameSeq != null) errorSeq.add(d.frameSeq);
  }
  for (const f of state.detail.frames) {
    const future = f.offsetMs > state.visibleMs;
    if (state.errorsOnly && !future && !errorSeq.has(f.seq) && !(f.review || {}).rejected) continue;
    const rowText = `${f.canId} ${f.direction} ${f.frameType} ${f.dataHex} ${f.seq}`;
    if (!matchesFilter(rowText)) continue;
    const tr = document.createElement('tr');
    if (future) tr.className = 'future';
    if (f.rejected) tr.classList.add('rejected');
    const review = f.review;
    tr.innerHTML =
      `<td>${f.seq}</td><td>${f.offsetMs}</td><td>${esc(f.canId)}</td>` +
      `<td><span class="tag ${f.effectiveDirection}">${f.effectiveDirection}</span>` +
      (f.direction !== f.effectiveDirection ? ` <span class="dim">(${f.direction}→${f.effectiveDirection})</span>` : '') +
      `</td><td>${f.frameType}</td><td><span class="tag G">g${f.gen}</span></td>` +
      `<td>${esc(f.dataHex)}</td>` +
      `<td>${review ? `✓ v${review.version}${review.rejected ? ' 拒绝' : ''}` : '<a class="btnlink">判定</a>'}</td>`;
    tr.querySelector('td:last-child').addEventListener('click', () => openFrameDialog(f));
    tb.appendChild(tr);
  }
}

function renderMessages() {
  const host = $('messageList');
  host.innerHTML = '';
  const visible = state.detail.messages.filter((m) => m.startMs <= state.visibleMs);
  if (!visible.length) {
    host.innerHTML = '<p class="dim">当前时间窗内无重组消息</p>';
    return;
  }
  for (const m of visible) {
    const card = document.createElement('div');
    card.className = 'message-card';
    const pair = m.pairedLocalKey
      ? `↔ <span class="dim">${esc(m.pairedLocalKey)}</span>` : '';
    const cand = (m.candidateLocalKeys || []).filter(Boolean).length
      ? ` · <a class="btnlink" data-ambiguous="${esc(m.localKey)}">${m.candidateLocalKeys.length} 个会话候选</a>` : '';
    card.innerHTML =
      `<div class="head">
        <b>${esc(m.canId)}</b><span class="tag ${m.direction}">${m.direction}</span>
        <span class="tag G">g${m.gen}</span>
        <span class="badge ${m.status}">${m.status}</span>
        <span class="dim">帧 ${m.firstFrameSeq}${m.lastFrameSeq !== m.firstFrameSeq ? '–' + m.lastFrameSeq : ''}
          · ${m.startMs}–${m.endMs}ms${m.multiFrame ? ` · 期望 ${m.expectedLength}B / 收 ${m.payloadHex.length / 2}B` : ''}</span>
        ${pair}${cand}
      </div>` +
      (m.missingIndices && m.missingIndices.length
        ? `<div class="detail">缺失 CF 序号: <span class="missing">[${m.missingIndices.join(', ')}]</span></div>` : '') +
      `<div class="payload">${esc(m.payloadHex) || '<span class="dim">（无载荷）</span>'}</div>` +
      (m.statusDetail ? `<div class="detail">${esc(m.statusDetail)}</div>` : '');
    const link = card.querySelector('[data-ambiguous]');
    if (link) link.addEventListener('click', () => openAttrDialog(m.localKey));
    host.appendChild(card);
  }
}

function renderDiagnostics() {
  const host = $('diagList');
  host.innerHTML = '';
  const list = state.detail.diagnostics
    .filter((d) => d.offsetMs == null || d.offsetMs <= state.visibleMs)
    .filter((d) => matchesFilter(`${d.code} ${d.detail} ${d.canId || ''}`));
  if (!list.length) {
    host.innerHTML = '<p class="dim">当前时间窗内无诊断</p>';
    return;
  }
  for (const d of list) {
    const card = document.createElement('div');
    card.className = `diag-card ${d.severity}`;
    card.innerHTML =
      `<div><span class="sev ${d.severity}">${d.severity}</span>
        <span class="code">${d.code}</span>
        <span class="dim">${d.offsetMs != null ? `@${d.offsetMs}ms` : ''}
          ${d.canId ? esc(d.canId) : ''} ${d.direction || ''} ${d.gen != null ? 'g' + d.gen : ''}</span></div>
       <div>${esc(d.detail)}</div>`;
    host.appendChild(card);
  }
}

function renderPairing() {
  const host = $('pairingView');
  const msgs = state.detail.messages.filter((m) => m.startMs <= state.visibleMs);
  const byKey = new Map(msgs.map((m) => [m.localKey, m]));
  const requests = msgs.filter((m) => m.direction === 'TX' && /^7E[0-7]$/.test(m.canId));
  const rows = [];
  const seen = new Set();
  for (const req of requests) {
    seen.add(req.localKey);
    const resp = req.pairedLocalKey ? byKey.get(req.pairedLocalKey) : null;
    rows.push({ req, resp, candidates: req.candidateLocalKeys || [] });
  }
  // Orphan / replayed responses (unpaired RX)
  for (const m of msgs) {
    if (m.direction === 'RX' && /^7E[89A-F]$/.test(m.canId) && !m.pairedLocalKey) {
      rows.push({ req: null, resp: m, candidates: m.candidateLocalKeys || [] });
    }
  }
  if (!rows.length) {
    host.innerHTML = '<p class="dim">当前时间窗内无标准 UDS 物理请求/响应</p>';
    return;
  }
  host.innerHTML = rows.map(({ req, resp, candidates }) => {
    const left = req
      ? `<span class="tag TX">TX ${esc(req.canId)} g${req.gen}</span>
         <code>${esc(req.payloadHex.slice(0, 24))}${req.payloadHex.length > 24 ? '…' : ''}</code>
         <span class="dim">#${req.firstFrameSeq}</span>`
      : '<span class="dim">（无请求）</span>';
    const right = resp
      ? `<span class="tag RX">RX ${esc(resp.canId)} g${resp.gen}</span>
         <code>${esc(resp.payloadHex.slice(0, 24))}${resp.payloadHex.length > 24 ? '…' : ''}</code>
         <span class="dim">#${resp.firstFrameSeq}</span>`
      : '<span class="dim">（无响应 / 超时）</span>';
    const amb = candidates.length
      ? ` <a class="btnlink" data-attr="${esc((resp || req).localKey)}">会话候选 ×${candidates.length}</a>` : '';
    return `<div class="pair-row">${left}<span class="arrow">⇄</span>${right}${amb}</div>`;
  }).join('');
  host.querySelectorAll('[data-attr]').forEach((a) =>
    a.addEventListener('click', () => openAttrDialog(a.dataset.attr)));
}

function renderBoundaries() {
  const host = $('boundaryList');
  const items = [
    ...state.detail.restartDirectives.map((d) => ({ ...d, deletable: false })),
    ...state.detail.restartMarks.map((m) => ({ ...m, deletable: true })),
  ].sort((a, b) => a.offsetMs - b.offsetMs);
  host.innerHTML = items.length ? items.map((b) =>
    `<div class="b"><span>@${b.offsetMs}ms ${b.scopeCanId ? esc(b.scopeCanId) : '全总线'}
       ${b.source === 'HUMAN' ? '🧑' : '📄'} ${esc(b.note || '')}</span>
       ${b.deletable ? `<button data-del="${b.id}">删除</button>` : ''}</div>`).join('')
    : '<p class="dim">无重启边界</p>';
  host.querySelectorAll('[data-del]').forEach((btn) =>
    btn.addEventListener('click', async () => {
      await api(`/api/captures/${state.captureId}/restart-marks/${btn.dataset.del}`, { method: 'DELETE' });
      await loadDetail();
    }));
}

// ---------- replay ----------

function stepFrame() {
  if (!state.detail) return;
  const times = state.detail.frames.map((f) => f.offsetMs);
  const next = times.find((t) => t > state.visibleMs);
  if (next == null) return;
  state.visibleMs = next;
  $('timeline').value = next;
  render();
}

function play() {
  if (state.playing) {
    state.playing = false;
    clearInterval(state.playTimer);
    $('playBtn').textContent = '▶ 播放';
    return;
  }
  state.playing = true;
  $('playBtn').textContent = '⏸ 暂停';
  const tl = $('timeline');
  const tickMs = 25;
  state.playTimer = setInterval(() => {
    const step = 10 / state.speed;
    state.visibleMs = Math.min(Number(tl.max), state.visibleMs + step);
    tl.value = state.visibleMs;
    render();
    if (state.visibleMs >= Number(tl.max)) play();
  }, tickMs);
}

// ---------- frame review dialog ----------

async function openFrameDialog(f) {
  state.reviewFrameId = f;
  $('frameDialogInfo').textContent =
    `帧 #${f.seq} @${f.offsetMs}ms · ${f.canId} · ${f.direction} · ${f.frameType} · ${f.dataHex} · 代次 g${f.gen}`;
  const r = f.review || {};
  $('rvRejected').checked = !!r.rejected;
  $('rvRole').value = r.roleOverride || '';
  $('rvNote').value = r.note || '';
  $('rvVersion').value = r.version || '';
  $('rvConflict').hidden = true;
  $('frameDialog').showModal();
}

$('frameReviewForm').addEventListener('submit', async (e) => {
  if (e.submitter && e.submitter.value === 'cancel') return;
  e.preventDefault();
  const f = state.reviewFrameId;
  if (!f) return;
  const body = {
    captureId: state.captureId,
    rejected: $('rvRejected').checked,
    roleOverride: $('rvRole').value || null,
    note: $('rvNote').value,
  };
  const v = $('rvVersion').value;
  if (v) body.expectedVersion = Number(v);
  try {
    await api(`/api/frames/${f.id}/review`, { method: 'POST', body: JSON.stringify(body) });
    $('frameDialog').close();
    await loadDetail();
  } catch (err) {
    if (err.status === 409) {
      $('rvConflict').textContent = `并发冲突：${err.message}。请刷新后基于最新版本重试，判定未被覆盖。`;
      $('rvConflict').hidden = false;
    } else {
      alert(err.message);
    }
  }
});

// ---------- attribution dialog ----------

function currentAmbiguity(localKey) {
  const view = (state.detail.ambiguities || []).find((a) => a.messageLocalKey === localKey);
  const msg = state.detail.messages.find((m) => m.localKey === localKey);
  return { view, msg };
}

async function openAttrDialog(localKey) {
  const { view, msg } = currentAmbiguity(localKey);
  if (!msg) return;
  $('attrInfo').textContent =
    `响应 ${msg.canId} g${msg.gen} @${msg.startMs}ms · SID ${msg.sid || '?'} · 帧#${msg.firstFrameSeq}`;
  const candidates = (view ? view.candidateLocalKeys : msg.candidateLocalKeys) || [];
  const byKey = new Map(state.detail.messages.map((m) => [m.localKey, m]));
  const review = view ? view.review : null;
  $('attrChoices').innerHTML = candidates.map((key, idx) => {
    const r = byKey.get(key);
    if (!r) return '';
    const checked = review && !review.keepBoth && review.choiceLocalKey === key ? 'checked' : '';
    return `<label class="choice"><input type="radio" name="attrChoice" value="${esc(key)}" ${checked}>
      请求 ${esc(r.canId)} g${r.gen} @${r.endMs}ms 帧#${r.firstFrameSeq}
      <code>${esc(r.payloadHex.slice(0, 24))}</code> ${idx === 0 ? '<span class="dim">（机器默认最近者）</span>' : ''}</label>`;
  }).join('');
  $('attrKeepBoth').checked = !!(review && review.keepBoth);
  $('attrNote').value = review ? review.note : '';
  $('attrVersion').value = review ? review.version : '';
  $('attrConflict').hidden = true;
  $('attrForm').dataset.localKey = localKey;
  $('attrDialog').showModal();
}

$('attrForm').addEventListener('submit', async (e) => {
  if (e.submitter && e.submitter.value === 'cancel') return;
  e.preventDefault();
  const localKey = $('attrForm').dataset.localKey;
  const body = {
    messageLocalKey: localKey,
    keepBoth: $('attrKeepBoth').checked,
    choiceLocalKey: null,
    note: $('attrNote').value,
  };
  const chosen = document.querySelector('input[name=attrChoice]:checked');
  if (chosen && !body.keepBoth) body.choiceLocalKey = chosen.value;
  const v = $('attrVersion').value;
  if (v) body.expectedVersion = Number(v);
  try {
    await api(`/api/captures/${state.captureId}/attribution`, {
      method: 'POST', body: JSON.stringify(body),
    });
    $('attrDialog').close();
    await loadDetail();
  } catch (err) {
    if (err.status === 409) {
      $('attrConflict').textContent = `并发冲突：${err.message}。仲裁未保存，请刷新后重试。`;
      $('attrConflict').hidden = false;
    } else alert(err.message);
  }
});

// ---------- boundary form / import / controls ----------

$('boundaryForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const scope = $('bScope').value.trim();
  const body = {
    offsetMs: Number($('bOffset').value),
    scopeCanId: scope || null,
    note: $('bNote').value,
  };
  try {
    await api(`/api/captures/${state.captureId}/restart-marks`, {
      method: 'POST', body: JSON.stringify(body),
    });
    $('boundaryForm').reset();
    await loadDetail();
  } catch (err) { alert(err.message); }
});

$('importFile').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const content = await file.text();
  try {
    const r = await api('/api/captures', {
      method: 'POST', body: JSON.stringify({ name: file.name, content }),
    });
    state.captureId = r.capture.id;
    await loadCaptures();
    await loadDetail();
  } catch (err) { alert('导入失败：' + err.message); }
  e.target.value = '';
});

$('captureSelect').addEventListener('change', async () => {
  state.captureId = Number($('captureSelect').value);
  await loadDetail();
});
$('rerunBtn').addEventListener('click', async () => {
  if (!state.captureId) return;
  await api(`/api/captures/${state.captureId}/rerun`, { method: 'POST' });
  await loadDetail();
});
$('timeline').addEventListener('input', () => {
  state.visibleMs = Number($('timeline').value);
  render();
});
$('playBtn').addEventListener('click', play);
$('stepBtn').addEventListener('click', () => { if (state.playing) play(); stepFrame(); });
$('resetBtn').addEventListener('click', () => {
  if (state.playing) play();
  state.visibleMs = 0;
  $('timeline').value = 0;
  render();
});
$('speed').addEventListener('change', () => { state.speed = Number($('speed').value); });

async function clockAction(body) {
  const r = await api('/api/clock', { method: 'POST', body: JSON.stringify(body) });
  $('clockMode').textContent = `模式：${r.clockMode} · t=${r.nowMs} ms`;
  $('manualClockControls').hidden = r.clockMode !== 'MANUAL';
  $('clock').textContent = `虚拟时钟 ${r.nowMs} (${r.clockMode})`;
}
$('clockManual').addEventListener('click', () => clockAction({ mode: 'MANUAL' }));
$('clockSystem').addEventListener('click', () => clockAction({ mode: 'SYSTEM' }));
$('clockStep').addEventListener('click', () => clockAction({ mode: 'MANUAL', advanceMs: 100 }));
$('clockApply').addEventListener('click', () => {
  const v = Number($('clockSet').value);
  if (Number.isFinite(v)) clockAction({ mode: 'MANUAL', setMs: v });
});
$('fText').addEventListener('input', () => { state.filterText = $('fText').value; render(); });
$('fErrors').addEventListener('change', () => { state.errorsOnly = $('fErrors').checked; render(); });

(async function init() {
  await loadCaptures();
  if (state.captureId) await loadDetail();
})();
