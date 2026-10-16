// WebView 안에서 도는 문서 뷰어 화면. 네이티브 셸과는 WebMessagePort 하나로만 말한다(PRD SEC-4).
// 문서 해석·렌더는 engine.js(kordoc)가 Web Worker 에서 맡는다.
//
// 네이티브 → 뷰어 (port)
//   {type:"open", name, modes:{hwp:"reading",…}, fontStep, fontScale}   이어서 문서 바이트:
//       ArrayBuffer 한 번, 또는 {type:"chunk", data:base64}… {type:"end"}
//   {type:"mode", mode}  {type:"fontStep", step}  {type:"goto", page}  {type:"theme", dark}
//   {type:"find", query}  {type:"findNext", forward}  {type:"findClear"}
// 뷰어 → 네이티브 (port, JSON 문자열)
//   {type:"ready"}  {type:"opened", format, mode, pages, fallback, timing}  {type:"error", code}
//   {type:"mode", mode}  {type:"page", current, total}  {type:"found", current, total}
//   {type:"link", href}  {type:"tap"}  {type:"dbltap"}
import MarkdownIt from "markdown-it";

const ORIGINAL_FORMATS = new Set(["hwp", "hwpx"]);
const READING_FORMATS = new Set(["hwp", "hwpx", "hwp3", "hwpml", "pptx", "docx", "xlsx", "md"]);
const UNSAFE_TAGS = "script,iframe,object,embed,form,input,button,textarea,select,link,meta,base,style,foreignObject";
// kordoc 렌더러가 검색어 자리에 그리는 강조 사각형
const HIT_SELECTOR = 'rect[fill="#ffd54f"][fill-opacity="0.45"]';
const TAP_DELAY_MS = 280;

const $doc = document.getElementById("doc");
const $notice = document.getElementById("notice");
const $chip = document.getElementById("page-chip");

let port = null;
let state = null;
let generation = 0;
let pendingOpen = null;
let chunks = [];
let blobUrls = [];
let pageObserver = null;
const visiblePages = new Set();

// ── 엔진(Worker) ─────────────────────────────────────────────

const engine = createEngine();

function createEngine() {
  const pending = new Map();
  let nextId = 1;
  let transport = null;

  const viaWorker = () =>
    new Promise((resolve, reject) => {
      let worker;
      try {
        worker = new Worker("engine.js");
      } catch (err) {
        return reject(err);
      }
      worker.onerror = (e) => {
        e.preventDefault?.();
        worker.terminate();
        reject(new Error("worker failed"));
      };
      worker.onmessage = (e) => {
        const done = pending.get(e.data.id);
        pending.delete(e.data.id);
        done?.(e.data);
      };
      const send = (req, transfer) =>
        new Promise((res) => {
          pending.set(req.id, res);
          worker.postMessage(req, transfer);
        });
      send({ id: 0, op: "ping" }, []).then(() => resolve(send));
    });

  // Worker 를 못 쓰면 같은 엔진을 메인 스레드에 올린다
  const viaScript = () =>
    new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = "engine.js";
      s.onload = () => resolve((req) => globalThis.kordocEngine.request(req));
      s.onerror = reject;
      document.head.appendChild(s);
    });

  const ready = viaWorker().catch(viaScript).then((t) => (transport = t));

  return async function request(op, payload = {}, transfer = []) {
    await ready;
    const reply = await transport({ id: nextId++, op, ...payload }, transfer);
    if (!reply.ok) throw Object.assign(new Error(reply.error.message), { code: reply.error.code });
    return reply.result;
  };
}

// ── 네이티브 연결 ─────────────────────────────────────────────

window.addEventListener("message", (event) => {
  if (event.data !== "port" || !event.ports?.[0]) return;
  port = event.ports[0];
  port.onmessage = (e) => onNativeMessage(e.data);
  send({ type: "ready" });
});

function send(msg) {
  if (port) port.postMessage(JSON.stringify(msg));
  else window.__messages?.push(msg);
}

function onNativeMessage(data) {
  if (data instanceof ArrayBuffer) {
    if (pendingOpen) openDocument(pendingOpen, data);
    pendingOpen = null;
    return;
  }
  const msg = JSON.parse(data);
  switch (msg.type) {
    case "open":
      pendingOpen = msg;
      chunks = [];
      break;
    case "chunk":
      chunks.push(base64ToBytes(msg.data));
      break;
    case "end": {
      const bytes = concat(chunks);
      chunks = [];
      if (pendingOpen) openDocument(pendingOpen, bytes.buffer);
      pendingOpen = null;
      break;
    }
    case "mode":
      if (state) switchMode(msg.mode);
      break;
    case "fontStep":
      document.documentElement.dataset.fontStep = String(msg.step);
      break;
    case "goto":
      pageElement(msg.page)?.scrollIntoView();
      break;
    case "theme":
      document.documentElement.dataset.theme = msg.dark ? "dark" : "light";
      break;
    case "find":
      if (state) find(msg.query);
      break;
    case "findNext":
      if (state?.search) moveHit(msg.forward ? 1 : -1);
      break;
    case "findClear":
      if (state) clearSearch();
      break;
  }
}

/**
 * 시스템 글꼴 크기(안드로이드 fontScale)를 읽기 모드 글자에만 곱한다 (PRD 6장 접근성).
 * 원본 보기 SVG 는 원본 글꼴 폭 기준으로 줄을 잡아 두었으므로 건드리지 않는다 — WebView textZoom 을 100 으로 고정한 이유.
 */
function setFontScale(scale) {
  const s = Number.isFinite(scale) ? Math.min(Math.max(scale, 0.85), 2) : 1;
  document.documentElement.style.setProperty("--font-scale", String(s));
}

// ── 열기 ─────────────────────────────────────────────────────

async function openDocument(req, buffer) {
  reset();
  const gen = ++generation;
  document.documentElement.dataset.fontStep = String(req.fontStep ?? 1);
  setFontScale(req.fontScale);
  const t0 = performance.now();
  let format;
  try {
    ({ format } = await engine("load", { name: req.name, bytes: buffer }, [buffer]));
  } catch {
    return fail("CORRUPTED");
  }
  if (gen !== generation) return;
  if (format === "legacy-office") return fail("LEGACY_OFFICE");
  if (!READING_FORMATS.has(format)) return fail("UNKNOWN_FORMAT");

  state = { gen, format, mode: null, pages: null, svgs: null, full: null, reading: null, search: null };
  const remembered = req.modes?.[format] ?? req.mode;
  const wanted = ORIGINAL_FORMATS.has(format) && remembered !== "reading" ? "original" : "reading";
  const result = await show(wanted);
  if (!result || gen !== generation) return;
  send({ type: "opened", format, mode: result.mode, pages: result.pages, fallback: result.fallback, timing: Math.round(performance.now() - t0) });
}

// ── 보기 전환 ────────────────────────────────────────────────

async function switchMode(mode) {
  if (state.search) clearSearch();
  const result = await show(mode);
  if (result) send({ type: "mode", mode: result.mode });
}

async function show(mode) {
  let fallback = false;
  if (mode === "original" && !ORIGINAL_FORMATS.has(state.format)) mode = "reading";
  if (mode === "original") {
    try {
      await ensureFirstPage();
      renderOriginal();
      state.mode = "original";
      return { mode, pages: state.pages.length, fallback };
    } catch (err) {
      const code = errorCode(err);
      if (code === "ENCRYPTED") return fail(code);
      fallback = true;
      mode = "reading";
    }
  }
  try {
    await ensureReading();
    renderReading();
    state.mode = "reading";
    if (fallback) showNotice("원본 모양을 그릴 수 없어 읽기 모드로 표시합니다.");
    return { mode, pages: state.pages?.length ?? 0, fallback };
  } catch (err) {
    return fail(errorCode(err));
  }
}

// 첫 쪽만 먼저 그려 바로 보여주고, 나머지 쪽은 이어서 그린다(PRD G2·R3).
// 첫 요청에서도 전 쪽의 크기는 받아 오므로 스크롤 길이는 처음부터 맞다.
async function ensureFirstPage() {
  if (state.pages) return;
  const first = await engine("render", { pages: [1] });
  state.pages = first.pages;
  state.svgs = new Map(first.svgs);
  const gen = state.gen;
  state.full =
    state.pages.length <= 1
      ? Promise.resolve()
      : engine("render", {})
          .then((all) => {
            if (gen !== generation) return;
            state.svgs = new Map(all.svgs);
            refreshVisiblePages();
          })
          .catch(() => {
            if (gen === generation) showNotice("일부 쪽을 그리지 못했습니다.");
          });
}

async function ensureReading() {
  if (!state.reading) state.reading = await engine("parse");
}

// ── 원본 보기: 쪽 SVG, 화면 근처 쪽만 DOM 에 붙인다(PRD 8.5) ─────────

function renderOriginal() {
  clearView();
  document.body.dataset.mode = "original";
  const frag = document.createDocumentFragment();
  for (const p of state.pages) {
    const el = document.createElement("section");
    el.className = "page";
    el.dataset.page = String(p.page);
    el.style.aspectRatio = `${p.width} / ${p.height}`;
    frag.appendChild(el);
  }
  $doc.appendChild(frag);

  pageObserver = new IntersectionObserver(onPagesVisible, { rootMargin: "200% 0px" });
  $doc.querySelectorAll(".page").forEach((el) => pageObserver.observe(el));
}

function onPagesVisible(entries) {
  for (const entry of entries) {
    const el = entry.target;
    const page = Number(el.dataset.page);
    if (entry.isIntersecting) {
      visiblePages.add(page);
      if (!el.firstChild) attachPage(el, page);
    } else {
      visiblePages.delete(page);
      el.replaceChildren();
    }
  }
}

function svgFor(page) {
  return (state.search?.svgs ?? state.svgs).get(page);
}

function attachPage(el, page) {
  const svg = svgFor(page);
  if (!svg) return;
  el.innerHTML = svg;
  sanitize(el);
  if (state.search?.hits.length) markCurrentHit(el, page);
}

function refreshVisiblePages() {
  if (state?.mode !== "original") return;
  for (const page of visiblePages) {
    const el = pageElement(page);
    if (el) attachPage(el, page);
  }
}

function pageElement(page) {
  return $doc.querySelector(`.page[data-page="${page}"]`);
}

// ── 읽기 모드: Markdown → HTML ───────────────────────────────

const md = new MarkdownIt({ html: true, linkify: false, typographer: false });

function renderReading() {
  clearView();
  document.body.dataset.mode = "reading";
  const article = document.createElement("article");
  article.className = "reading";
  article.innerHTML = md.render(state.reading.markdown);
  sanitize(article);

  const byName = new Map(state.reading.images.map((img) => [img.filename, img]));
  article.querySelectorAll("img").forEach((img) => {
    const src = img.getAttribute("src") ?? "";
    const found = byName.get(src) ?? byName.get(src.split("/").pop());
    if (!found || !/^image\/(png|jpeg|gif|bmp|webp)$/.test(found.mimeType)) {
      // WMF·EMF 등 브라우저가 못 그리는 그림 (PRD 8.5)
      const kind = found?.mimeType?.split("/")[1]?.toUpperCase();
      img.replaceWith(Object.assign(document.createElement("span"), { className: "img-missing", textContent: kind ? `[그림: ${kind}]` : "[그림]" }));
      return;
    }
    const url = URL.createObjectURL(new Blob([found.data], { type: found.mimeType }));
    blobUrls.push(url);
    img.src = url;
    img.loading = "lazy";
  });
  article.querySelectorAll("table").forEach((table) => {
    const wrap = document.createElement("div");
    wrap.className = "table-wrap";
    table.replaceWith(wrap);
    wrap.appendChild(table);
  });
  $doc.appendChild(article);
}

// ── 문서 내 검색 (F9) ────────────────────────────────────────
// 읽기 모드: 본문 글자에 <mark> 를 씌운다.
// 원본 보기: kordoc highlights 옵션으로 쪽을 다시 그려 강조 사각형을 얻는다.

async function find(query) {
  clearSearch();
  query = query.trim();
  if (!query) return reportFound();
  const gen = state.gen;
  if (state.mode === "reading") {
    state.search = { query, hits: markText($doc.querySelector(".reading"), query), index: 0, svgs: null };
  } else {
    await state.full;
    const { svgs } = await engine("render", { highlights: [query] });
    if (gen !== generation) return;
    const map = new Map(svgs);
    const hits = [];
    for (const p of state.pages) {
      const n = countHits(map.get(p.page) ?? "");
      for (let i = 0; i < n; i++) hits.push({ page: p.page, nth: i });
    }
    state.search = { query, hits, index: 0, svgs: map };
    refreshVisiblePages();
  }
  if (state.search.hits.length) showHit();
  reportFound();
}

function moveHit(step) {
  const s = state.search;
  if (!s.hits.length) return reportFound();
  s.index = (s.index + step + s.hits.length) % s.hits.length;
  showHit();
  reportFound();
}

function showHit() {
  const s = state.search;
  const hit = s.hits[s.index];
  if (state.mode === "reading") {
    s.hits.forEach((m, i) => m.classList.toggle("current", i === s.index));
    hit.scrollIntoView({ block: "center" });
    return;
  }
  const el = pageElement(hit.page);
  if (!el) return;
  el.scrollIntoView({ block: "start" });
  // 쪽이 막 붙은 뒤에 강조 사각형 위치로 한 번 더 맞춘다
  requestAnimationFrame(() => {
    if (!el.firstChild) attachPage(el, hit.page);
    markCurrentHit(el, hit.page)?.scrollIntoView({ block: "center" });
  });
}

function markCurrentHit(el, page) {
  const s = state.search;
  const hit = s.hits[s.index];
  let current = null;
  el.querySelectorAll(HIT_SELECTOR).forEach((r, i) => {
    const on = hit.page === page && hit.nth === i;
    r.setAttribute("fill", on ? "#ff8a00" : "#ffd54f");
    r.setAttribute("fill-opacity", on ? "0.6" : "0.45");
    r.dataset.hit = "1";
    if (on) current = r;
  });
  return current;
}

function countHits(svg) {
  let n = 0;
  for (let i = svg.indexOf('fill="#ffd54f" fill-opacity="0.45"'); i !== -1; i = svg.indexOf('fill="#ffd54f" fill-opacity="0.45"', i + 1)) n++;
  return n;
}

function clearSearch() {
  if (!state.search) return;
  if (state.mode === "reading") {
    state.search.hits.forEach((m) => m.replaceWith(document.createTextNode(m.textContent)));
    $doc.querySelector(".reading")?.normalize();
  }
  state.search = null;
  refreshVisiblePages();
}

function reportFound() {
  const s = state?.search;
  send({ type: "found", current: s?.hits.length ? s.index + 1 : 0, total: s?.hits.length ?? 0 });
}

function markText(root, query) {
  if (!root) return [];
  const needle = query.toLowerCase();
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const nodes = [];
  for (let n = walker.nextNode(); n; n = walker.nextNode()) nodes.push(n);
  const marks = [];
  for (const node of nodes) {
    const text = node.nodeValue;
    const lower = text.toLowerCase();
    let at = lower.indexOf(needle);
    if (at === -1) continue;
    const frag = document.createDocumentFragment();
    let last = 0;
    while (at !== -1) {
      frag.appendChild(document.createTextNode(text.slice(last, at)));
      const mark = document.createElement("mark");
      mark.textContent = text.slice(at, at + needle.length);
      frag.appendChild(mark);
      marks.push(mark);
      last = at + needle.length;
      at = lower.indexOf(needle, last);
    }
    frag.appendChild(document.createTextNode(text.slice(last)));
    node.replaceWith(frag);
  }
  return marks;
}

// ── 공통 ─────────────────────────────────────────────────────

function sanitize(root) {
  root.querySelectorAll(UNSAFE_TAGS).forEach((el) => el.remove());
  root.querySelectorAll("*").forEach((el) => {
    for (const attr of [...el.attributes]) {
      if (/^on/i.test(attr.name)) el.removeAttribute(attr.name);
    }
  });
}

function clearView() {
  pageObserver?.disconnect();
  pageObserver = null;
  visiblePages.clear();
  $doc.replaceChildren();
  $notice.hidden = true;
  blobUrls.forEach((u) => URL.revokeObjectURL(u));
  blobUrls = [];
  window.scrollTo(0, 0);
}

function reset() {
  clearView();
  state = null;
}

function showNotice(text) {
  $notice.textContent = text;
  $notice.hidden = false;
}

function fail(code) {
  clearView();
  state = null;
  send({ type: "error", code });
  return null;
}

function errorCode(err) {
  const code = err?.code;
  if (code === "ENCRYPTED" || code === "ERR_CRYPTO_UNSUPPORTED" || /암호|password|encrypt/i.test(err?.message ?? "")) return "ENCRYPTED";
  if (code === "DRM_PROTECTED") return "UNKNOWN_FORMAT";
  if (code === "UNSUPPORTED_FORMAT") return "UNSUPPORTED";
  return "CORRUPTED";
}

// 쪽 표시 칩 — 스크롤할 때만 잠깐 뜬다
let chipTimer = 0;
window.addEventListener("scroll", () => {
  if (state?.mode !== "original") return;
  const pages = $doc.querySelectorAll(".page");
  const mid = window.innerHeight / 2;
  let current = 1;
  for (const el of pages) {
    if (el.getBoundingClientRect().top <= mid) current = Number(el.dataset.page);
    else break;
  }
  $chip.textContent = `${current} / ${pages.length}`;
  $chip.hidden = false;
  clearTimeout(chipTimer);
  chipTimer = setTimeout(() => ($chip.hidden = true), 1200);
  send({ type: "page", current, total: pages.length });
}, { passive: true });

// 링크는 직접 열지 않고 네이티브에 넘겨 확인 창을 거친다.
// 그 밖의 한 번 탭은 몰입 보기 전환, 두 번 탭은 폭 맞춤(네이티브가 WebView 배율을 조정) — 두 번 탭과 겹치지 않게 잠깐 기다린다.
let tapTimer = 0;
document.addEventListener("click", (e) => {
  const a = e.target.closest("a");
  if (a) {
    e.preventDefault();
    const href = a.getAttribute("href") ?? a.getAttribute("xlink:href") ?? "";
    if (/^(https?:|mailto:|tel:)/i.test(href)) send({ type: "link", href });
    return;
  }
  clearTimeout(tapTimer);
  tapTimer = setTimeout(() => send({ type: "tap" }), TAP_DELAY_MS);
});
document.addEventListener("dblclick", (e) => {
  e.preventDefault();
  clearTimeout(tapTimer);
  send({ type: "dbltap" });
});

function base64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function concat(parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

// 개발용: 네이티브 없이 데스크톱 브라우저에서 확인할 때 쓴다. 보낸 메시지는 __messages 에 쌓인다.
window.__messages = [];
