// WebView 안에서 도는 문서 뷰어. 네이티브 셸과는 WebMessagePort 하나로만 말한다(PRD SEC-4).
//
// 네이티브 → 뷰어 (port)
//   {type:"open", name, mode, fontStep}   이어서 문서 바이트: ArrayBuffer 한 번 또는 {type:"chunk", data:base64}… {type:"end"}
//   {type:"mode", mode}  {type:"fontStep", step}  {type:"goto", page}  {type:"theme", dark}
// 뷰어 → 네이티브 (port, JSON 문자열)
//   {type:"ready"}  {type:"opened", format, mode, pages, fallback, timing}  {type:"error", code}
//   {type:"page", current, total}  {type:"link", href}  {type:"tap"}
import { detectFormat, detectZipFormat, parse, renderDocumentToScene } from "kordoc";
import * as CFB from "cfb";
import MarkdownIt from "markdown-it";

const ORIGINAL_FORMATS = new Set(["hwp", "hwpx"]);
const READING_FORMATS = new Set(["hwp", "hwpx", "hwp3", "hwpml", "pptx", "docx", "xlsx", "md"]);
const UNSAFE_TAGS = "script,iframe,object,embed,form,input,button,textarea,select,link,meta,base,style";

const $doc = document.getElementById("doc");
const $notice = document.getElementById("notice");
const $chip = document.getElementById("page-chip");

let port = null;
let state = null; // { name, bytes, format, mode, scene, pageSvgs, reading }
let pendingOpen = null;
let chunks = [];
let blobUrls = [];
let pageObserver = null;

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
    if (pendingOpen) openDocument(pendingOpen, new Uint8Array(data));
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
      if (pendingOpen) openDocument(pendingOpen, bytes);
      pendingOpen = null;
      break;
    }
    case "mode":
      if (state) show(msg.mode);
      break;
    case "fontStep":
      document.documentElement.dataset.fontStep = String(msg.step);
      break;
    case "goto":
      document.querySelector(`.page[data-page="${msg.page}"]`)?.scrollIntoView();
      break;
    case "theme":
      document.documentElement.dataset.theme = msg.dark ? "dark" : "light";
      break;
  }
}

// ── 열기 ─────────────────────────────────────────────────────

async function openDocument(req, bytes) {
  reset();
  document.documentElement.dataset.fontStep = String(req.fontStep ?? 1);
  const t0 = performance.now();
  let format;
  try {
    format = await detect(bytes, req.name);
  } catch {
    return fail("CORRUPTED");
  }
  if (format === "legacy-office") return fail("LEGACY_OFFICE");
  if (!READING_FORMATS.has(format)) return fail("UNKNOWN_FORMAT");

  state = { name: req.name, bytes, format, mode: null, scene: null, pageSvgs: null, reading: null };
  const wanted = ORIGINAL_FORMATS.has(format) && req.mode !== "reading" ? "original" : "reading";
  const result = await show(wanted);
  if (!result) return;
  send({ type: "opened", format, mode: result.mode, pages: result.pages, fallback: result.fallback, timing: Math.round(performance.now() - t0) });
}

async function detect(bytes, name) {
  const buf = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  const kind = detectFormat(buf);
  if (kind === "hwpx") {
    const zip = await detectZipFormat(buf);
    return zip === "unknown" ? "unknown" : zip;
  }
  if (kind === "hwp") return detectOle(bytes);
  if (kind !== "unknown") return kind;
  return looksLikeMarkdown(bytes, name) ? "md" : "unknown";
}

// kordoc detectFormat 은 OLE 를 모두 hwp 로 돌려준다 — 스트림 이름으로 HWP5 와 구형 오피스를 가른다(PRD F2).
function detectOle(bytes) {
  const cfb = CFB.read(bytes, { type: "array" });
  const names = new Set(cfb.FileIndex.map((f) => f.name));
  if (names.has("FileHeader")) return "hwp";
  if (names.has("PowerPoint Document") || names.has("WordDocument")) return "legacy-office";
  if (names.has("Workbook") || names.has("Book")) return "xls";
  return "unknown";
}

function looksLikeMarkdown(bytes, name) {
  if (!/\.(md|markdown|txt)$/i.test(name ?? "")) return false;
  try {
    new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(0, 64 * 1024));
    return true;
  } catch {
    return false;
  }
}

// ── 보기 전환 ────────────────────────────────────────────────

async function show(mode) {
  let fallback = false;
  if (mode === "original" && !ORIGINAL_FORMATS.has(state.format)) mode = "reading";
  if (mode === "original") {
    try {
      await ensureScene();
      renderOriginal();
      state.mode = "original";
      return { mode, pages: state.scene.pages.length, fallback };
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
    return { mode, pages: state.scene?.pages.length ?? 0, fallback };
  } catch (err) {
    return fail(errorCode(err));
  }
}

async function ensureScene() {
  if (state.scene) return;
  const { scene, pageSvgs } = await renderDocumentToScene(state.bytes);
  state.scene = scene;
  state.pageSvgs = pageSvgs;
}

async function ensureReading() {
  if (state.reading) return;
  if (state.format === "md") {
    state.reading = { markdown: new TextDecoder().decode(state.bytes), images: [] };
    return;
  }
  const buf = state.bytes.buffer.slice(state.bytes.byteOffset, state.bytes.byteOffset + state.bytes.byteLength);
  const result = await parse(buf, { ocr: false });
  if (!result.success) {
    const err = new Error(result.error);
    err.code = result.code;
    throw err;
  }
  state.reading = { markdown: result.markdown, images: result.images ?? [] };
}

// ── 원본 보기: 쪽 SVG, 화면 근처 쪽만 DOM 에 붙인다(PRD 8.5) ─────────

function renderOriginal() {
  clearView();
  document.body.dataset.mode = "original";
  const frag = document.createDocumentFragment();
  for (const p of state.scene.pages) {
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
    if (entry.isIntersecting && !el.firstChild) {
      el.innerHTML = state.pageSvgs.get(Number(el.dataset.page)) ?? "";
      sanitize(el);
    } else if (!entry.isIntersecting && el.firstChild) {
      el.replaceChildren();
    }
  }
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
    if (!found || !/^image\/(png|jpeg|gif|bmp|webp|svg\+xml)$/.test(found.mimeType)) {
      img.replaceWith(Object.assign(document.createElement("span"), { className: "img-missing", textContent: "[그림]" }));
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
    const r = el.getBoundingClientRect();
    if (r.top <= mid) current = Number(el.dataset.page);
    else break;
  }
  $chip.textContent = `${current} / ${pages.length}`;
  $chip.hidden = false;
  clearTimeout(chipTimer);
  chipTimer = setTimeout(() => ($chip.hidden = true), 1200);
  send({ type: "page", current, total: pages.length });
}, { passive: true });

// 링크는 직접 열지 않고 네이티브에 넘겨 확인 창을 거친다. 그 밖의 탭은 몰입 보기 전환.
document.addEventListener("click", (e) => {
  const a = e.target.closest("a");
  if (a) {
    e.preventDefault();
    const href = a.getAttribute("href") ?? a.getAttribute("xlink:href") ?? "";
    if (/^(https?:|mailto:|tel:)/i.test(href)) send({ type: "link", href });
    return;
  }
  send({ type: "tap" });
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
window.__openForTest = (name, buffer, mode) => openDocument({ name, mode }, new Uint8Array(buffer));
window.__show = (mode) => show(mode);