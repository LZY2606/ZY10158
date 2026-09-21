"use strict";

const state = {
  captureId: null,
  data: null,
  playhead: 0,
  playing: false,
  playTimer: null,
  frameVersions: {},
  dlg: { seq: null, version: null }
};

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

async function api(path, opts = {}) {
  const res = await fetch(path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(body.error || ("HTTP " + res.status));
    err.status = res.status; err.body = body;
    throw err;
  }
  return body;
}

function toast(msg, isErr = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "toast show" + (isErr ? " err" : "");
  clearTimeout(toast._tm);
  toast._tm = setTimeout(() => (t.className = "toast"), 3500);
}

const hex = (s) => s || "";
function esc(s) {
  return String(s ?? "").replace(/[&<>"]/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

async function loadCaptures() {
  const r = await api("/api/captures");
  const sel = $("#captureSelect");
  sel.innerHTML = r.captures.map((c) =>
    `<option value="${c.id}">#${c.id} ${esc(c.filename)} (${c.frameCount}帧, ${c.sha256.slice(0, 10)})</option>`
  ).join("");
  if (r.captures.length && !state.captureId) state.captureId = r.captures[0].id;
  if (state.captureId) sel.value = state.captureId;
}

async function loadState(head) {
  if (!state.captureId) return;
  const ph = head != null ? `?playhead=${head}` : "";
  state.data = await api(`/api/captures/${state.captureId}${ph}`);
  state.playhead = state.data.playhead;
  $("#playhead").max = Math.max(0, state.data.frameCount - 1);
  render();
}

function render() {
  renderFrames();
  renderAssemblies();
  renderLinks();
  renderGenerations();
  renderDiagnostics();
  renderConflicts();
  const f = state.data.frames[state.playhead];
  $("#clockText").textContent = "虚拟时钟 " + (f ? f.tsText : "—") + "s" +
    `  帧 ${state.playhead + 1}/${state.data.frameCount} · run#${state.data.machineRunId}`;
  $("#playhead").value = state.playhead;
}

function resetMarkersBefore(seq) {
  return state.data.resets.filter((r) => r.afterSeq < seq);
}

function renderFrames() {
  const tb = $("#frameTable tbody");
  const rows = [];
  let resetIdx = 0;
  state.data.frames.forEach((f, i) => {
    const visible = i <= state.playhead;
    state.frameVersions[f.seq] = f.version ?? null;
    const reset = state.data.resets.find((r) => r.afterSeq === f.seq - 1);
    if (reset && i <= state.playhead) {
      rows.push(`<tr class="reset-row"><td colspan="10">⟲ ECU 重启边界（${reset.origin}${reset.canId ? "，ID " + reset.canId : "，全局"}）@${(reset.tsUs / 1e6).toFixed(6)}</td></tr>`);
    }
    const pciBadge = `<span class="pci-${f.pciClass}">${f.pciClass}</span>`;
    const marks = [
      f.rejected ? '<span class="badge rej">已拒绝</span>' : "",
      f.declaredDirection !== f.direction ? `<span class="badge" title="角色已修正">角色修正→${f.direction}</span>` : "",
      f.assemblyId != null ? `<span class="pill">asm#${f.assemblyId}</span>` : ""
    ].join(" ");
    rows.push(`<tr data-seq="${f.seq}" class="${visible ? "" : "hidden-frame"} ${i === state.playhead ? "current" : ""} ${f.rejected ? "rejected" : ""}">
      <td>${f.seq}</td>
      <td>${f.tsText}</td>
      <td>${f.canId}${f.ext ? " (ext)" : ""}</td>
      <td class="dir-${f.direction}">${f.direction}</td>
      <td>G${f.generation}</td>
      <td>${pciBadge}${f.sn != null ? "·SN" + f.sn : ""}${f.fcStatus ? "·" + f.fcStatus : ""}</td>
      <td class="data-cell" title="${esc(f.data)}">${esc(f.data)}</td>
      <td>${f.sn ?? ""}</td>
      <td>${f.assemblyId ?? ""}</td>
      <td>${marks}</td>
    </tr>`);
  });
  tb.innerHTML = rows.join("");
  $$("#frameTable tbody tr[data-seq]").forEach((tr) =>
    tr.addEventListener("click", () => openFrameDialog(Number(tr.dataset.seq)))
  );
  const cur = tb.querySelector("tr.current");
  if (cur) cur.scrollIntoView({ block: "nearest" });
}

function statusBadge(s) { return `<span class="status s-${s}">${s}</span>`; }

function renderAssemblies() {
  const host = $("#asmList");
  const visibleAsms = state.data.assemblies;
  host.innerHTML = visibleAsms.map((a) => {
    const missing = a.missing.length
      ? `<div class="missing-chips">缺失 SN: ${a.missing.map((m) => `<span>${m.sn}(${m.expectedLen}B)</span>`).join("")}</div>` : "";
    const ev = a.evidence.length
      ? `<ul class="evidence">${a.evidence.map((e) => `<li>[帧${e.frameSeq}] <b>${e.kind}</b> — ${esc(e.detail)}</li>`).join("")}</ul>` : "";
    return `<div class="card">
      <h4>${a.canId} <span class="dir-${a.direction}">${a.direction}</span> G${a.generation}
        ${statusBadge(a.status)} ${a.rejected ? '<span class="badge rej">含拒绝帧</span>' : ""}
        <span class="pill">asm#${a.id}</span>
        ${a.serviceId ? `<span class="pill">SID ${a.serviceId}</span>` : ""}
      </h4>
      <div class="meta">帧 ${a.startFrameSeq}→${a.endFrameSeq ?? "…"} · ${a.receivedLen}/${a.declaredLen ?? "?"}B · ${(a.startTsUs / 1e6).toFixed(6)}→${a.endTsUs != null ? (a.endTsUs / 1e6).toFixed(6) : "…"}${a.errorCode ? " · " + a.errorCode : ""}</div>
      <pre>${esc(a.payload)}</pre>
      ${missing}${ev}
    </div>`;
  }).join("") || '<p class="muted">暂无重组</p>';
}

function renderLinks() {
  const host = $("#linkList");
  host.innerHTML = state.data.links.map((l) => {
    const cands = (l.candidates || []).map((c, i) =>
      `<div class="cand">候选${i + 1}: asm#${c.requestAssemblyId}（评分 ${c.score}）— ${esc(c.reason)}</div>`
    ).join("");
    return `<div class="card">
      <h4>${statusBadge(l.status)} G${l.generation}
        <span class="pill">link#${l.id}</span>
        ${l.serviceId ? `<span class="pill">SID ${l.serviceId}</span>` : ""}
        ${l.nrc != null ? `<span class="badge rej">NRC ${l.nrc}</span>` : ""}
      </h4>
      <div class="meta">请求 asm#${l.requestAssemblyId ?? "—"} → 响应 asm#${l.responseAssemblyId ?? "—"}</div>
      ${cands ? `<div class="candidates">${cands}</div>` : ""}
      <div class="meta">人工选择: asm#${l.chosenRequestAssemblyId ?? "（未介入）"} ${l.keepCandidates ? "· 保留双候选" : ""} ${l.note ? "· " + esc(l.note) : ""}</div>
      <div class="link-actions">
        ${(l.candidates || []).slice(0, 2).map((c, i) =>
          `<button class="btn" data-linkpick="${l.id}" data-asm="${c.requestAssemblyId}">选定候选${i + 1}</button>`
        ).join("")}
        <label class="check"><input type="checkbox" data-keep="${l.id}" ${l.keepCandidates ? "checked" : ""}/> 保留两个归属候选</label>
      </div>
    </div>`;
  }).join("") || '<p class="muted">暂无配对</p>';

  $$("[data-linkpick]").forEach((btn) =>
    btn.addEventListener("click", () => submitLinkAdj(
      Number(btn.dataset.linkpick), Number(btn.dataset.asm), false))
  );
  $$("[data-keep]").forEach((cb) =>
    cb.addEventListener("change", () => submitLinkAdj(
      Number(cb.dataset.keep), null, cb.checked))
  );
}

async function submitLinkAdj(linkId, chosen, keep) {
  try {
    const r = await api(`/api/captures/${state.captureId}/links/adjudication`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ linkId, chosenRequestAssemblyId: chosen, keepCandidates: keep, note: "UI 判定" })
    });
    toast(`链接判定已保存（版本 ${r.version}）`);
    await loadState(state.playhead);
  } catch (e) {
    toast(e.status === 409 ? "并发判定冲突：该链接已被他人更新，冲突已记录（见“判定冲突”）" : e.message, true);
    await loadState(state.playhead);
  }
}

function renderGenerations() {
  const host = $("#genList");
  host.innerHTML = state.data.generations.map((g) =>
    `<div class="card"><h4>${g.canId} ${g.direction ?? "（双方向）"}
      <span class="badge">第 ${g.generation} 代</span></h4>
      <div class="meta">边界位于帧 ${g.startedAfterSeq} 之后 · ${(g.startTsUs / 1e6).toFixed(6)}s</div></div>`
  ).join("") || '<p class="muted">无重启边界（全部为第 0 代）</p>';
}

function renderDiagnostics() {
  const host = $("#diagList");
  host.innerHTML = state.data.diagnostics.map((d) =>
    `<div class="card">
      <h4><span class="sev-${d.severity}">${d.severity}</span>
        <code>${d.code}</code>
        <span class="pill">帧 ${d.frameSeq ?? "—"}</span>
        ${d.canId ? `<span class="pill">${d.canId}</span>` : ""}
        ${d.direction ? `<span class="pill dir-${d.direction}">${d.direction}</span>` : ""}
        ${d.generation != null ? `<span class="pill">G${d.generation}</span>` : ""}
      </h4>
      <div>${esc(d.message)}</div>
    </div>`
  ).join("") || '<p class="muted">无诊断</p>';
}

async function renderConflicts() {
  const r = await api(`/api/captures/${state.captureId}/conflicts`);
  $("#conflictBadge").textContent = r.conflicts.length;
  $("#conflictList").innerHTML = r.conflicts.map((c) =>
    `<div class="card">
      <h4 class="s-SN_CONFLICT">${c.kind} 判定冲突 #${c.id}</h4>
      <div class="meta">目标 ${c.targetKey} · 期望版本 ${c.expectedVersion ?? "—"} / 实际版本 ${c.actualVersion ?? "—"} · ${new Date(c.createdAtMs).toLocaleString()}</div>
      <pre>${esc(JSON.stringify(c.payload, null, 2))}</pre>
    </div>`
  ).join("") || '<p class="muted">无并发冲突记录</p>';
}

/* ---------- 帧判定对话框 ---------- */

function openFrameDialog(seq) {
  const f = state.data.frames.find((x) => x.seq === seq);
  state.dlg.seq = seq;
  $("#dlgFrameSeq").textContent = `帧 #${seq} @ ${f.tsText}s · ${f.canId} ${f.direction}`;
  $("#dlgFrameMeta").textContent = `原始行只读：${f.rawLine}`;
  $("#dlgRejected").checked = !!f.rejected;
  $("#dlgReason").value = f.rejectReason || "";
  $("#dlgRole").value = f.declaredDirection === f.direction ? "" : f.direction;
  $("#dlgVersion").textContent = "新判定";
  $("#frameDialog").showModal();
}

$("#dlgCancel").addEventListener("click", () => $("#frameDialog").close());
$("#dlgSave").addEventListener("click", async () => {
  try {
    const body = {
      frameSeq: state.dlg.seq,
      rejected: $("#dlgRejected").checked,
      rejectReason: $("#dlgReason").value || null,
      roleOverride: $("#dlgRole").value || null
    };
    const r = await api(`/api/captures/${state.captureId}/frames/adjudication`, {
      method: "PUT", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    $("#frameDialog").close();
    toast(`帧判定已保存（版本 ${r.version}），重组已在新机器运行中重放`);
    await loadState(state.playhead);
  } catch (e) {
    $("#frameDialog").close();
    toast(e.status === 409 ? "判定版本冲突：该帧已被他人更新，冲突未丢失（见“判定冲突”标签）" : e.message, true);
    await loadState(state.playhead);
  }
});

/* ---------- 回放控制 ---------- */

$("#playhead").addEventListener("input", async (e) => {
  stopPlay();
  await loadState(Number(e.target.value));
});
$("#stepBack").addEventListener("click", () => seek(0));
$("#stepPrev").addEventListener("click", () => seek(state.playhead - 1));
$("#stepNext").addEventListener("click", () => seek(state.playhead + 1));
$("#playBtn").addEventListener("click", () => state.playing ? stopPlay() : startPlay());

async function seek(i) {
  stopPlay();
  const target = Math.max(0, Math.min(state.data.frameCount - 1, i));
  await loadState(target);
}

function startPlay() {
  state.playing = true;
  $("#playBtn").textContent = "⏸ 暂停";
  const delay = Number($("#speedSel").value);
  const tick = async () => {
    if (!state.playing) return;
    if (state.playhead >= state.data.frameCount - 1) { stopPlay(); return; }
    await loadState(state.playhead + 1);
    if (delay === 0) { state.playTimer = setTimeout(tick, 0); }
    else { state.playTimer = setTimeout(tick, delay); }
  };
  tick();
}
function stopPlay() {
  state.playing = false;
  $("#playBtn").textContent = "▶ 回放";
  clearTimeout(state.playTimer);
}

$("#captureSelect").addEventListener("change", async (e) => {
  state.captureId = Number(e.target.value);
  await loadState(0);
});

$("#importBtn").addEventListener("click", () => $("#importFile").click());
$("#importFile").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const text = await file.text();
  try {
    const r = await api(`/api/captures?filename=${encodeURIComponent(file.name)}`, {
      method: "POST", body: text
    });
    toast(r.idempotent ? "相同哈希：导入幂等，复用既有捕获" : `导入成功 capture#${r.captureId}`);
    state.captureId = r.captureId;
    await loadCaptures();
    $("#captureSelect").value = r.captureId;
    await loadState(0);
  } catch (err2) { toast(err2.message, true); }
  e.target.value = "";
});

$("#resetBoundaryBtn").addEventListener("click", async () => {
  const afterSeq = state.playhead;
  const canIdStr = prompt("输入要应用重启的 CAN ID（留空表示全局）", "");
  if (canIdStr === null) return;
  let canId = null;
  if (canIdStr.trim()) canId = parseInt(canIdStr.trim().replace(/^0x/, ""), 16);
  try {
    const r = await api(`/api/captures/${state.captureId}/reset-boundary`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ afterSeq, canId })
    });
    toast(`人工重启边界已记录 #${r.resetId}，重组已重放`);
    await loadState(state.playhead);
  } catch (e) { toast(e.message, true); }
});

$$(".tab").forEach((t) => t.addEventListener("click", () => {
  $$(".tab").forEach((x) => x.classList.remove("active"));
  $$(".tabpanel").forEach((x) => x.classList.remove("active"));
  t.classList.add("active");
  $("#tab-" + t.dataset.tab).classList.add("active");
}));

(async function init() {
  await loadCaptures();
  if (state.captureId) await loadState(0);
})();
