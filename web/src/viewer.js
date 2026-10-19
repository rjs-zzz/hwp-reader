// WebView 안에서 도는 문서 뷰어 화면. 네이티브 셸과는 WebMessagePort 하나로만 말한다(PRD SEC-4).
// 문서 해석·렌더는 engine.js(kordoc)가 Web Worker 에서 맡는다.
//
// 네이티브 → 뷰어 (port)
//   {type:"open", name, size, modes:{hwp:"reading",…}, fontStep}
//       이어서 문서 바이트 조각들: ArrayBuffer, 또는 {type:"chunk", data:base64}. 끝은 {type:"end"}.
//   {type:"mode", mode}  {type:"fontStep", step}  {type:"fontScale", scale}  {type:"goto", page}  {type:"theme", dark}
//   {type:"find", query}  {type:"findNext", forward}  {type:"findClear"}
// 뷰어 → 네이티브 (port, JSON 문자열)
//   {type:"ready"}  {type:"opened", format, mode, pages, fallback, timing, engine, transfer}  {type:"error", code}
//       engine: "worker" | "main", transfer: "arraybuffer" | "base64" — 문서 정보 진단용
//   {type:"mode", mode, pages}   실제로 보여 주는 방식 — 전환이 실패하면 다른 방식으로 돌아온다
//   {type:"page", current, total}  {type:"found", current, total, readingTotal?}
//   {type:"link", href}  {type:"tap"}  {type:"dbltap"}
import "../shims/polyfills.js";
import MarkdownIt from "markdown-it";

const ORIGINAL_FORMATS = new Set(["hwp", "hwpx"]);
const READING_FORMATS = new Set(["hwp", "hwpx", "hwp3", "hwpml", "pptx", "docx", "xlsx", "md"]);
const UNSAFE_TAGS = "script,iframe,object,embed,form,input,button,textarea,select,link,meta,base,style,foreignObject";
// kordoc 렌더러가 검색어 자리에 그리는 강조 사각형 (engine.js 와 같은 값)
const HIT_SELECTOR = 'rect[fill="#ffd54f"][fill-opacity="0.45"]';
// engine.js 가 쪽 SVG 의 그림 자리에 넣는 표시 (kimg:<그림 key>)
const IMAGE_REF = /href="kimg:([0-9a-z:]+)"/g;
// 검색은 블록(문단·표 칸) 단위로 글자를 이어 붙여 찾는다 — 굵게·기울임으로 나뉜 글자도 한 낱말로 찾기 위해
const BLOCK_SELECTOR = "p,li,td,th,h1,h2,h3,h4,h5,h6,blockquote,pre,dt,dd,caption,figcaption";
const TAP_DELAY_MS = 280;
// 읽기 모드가 그리는 그림 형식 (engine.js 와 같은 값) — 엔진은 이 밖의 그림에는 Blob 을 만들지 않는다
const READING_IMAGE = /^image\/(png|jpeg|gif|bmp|webp)$/;

const $doc = document.getElementById("doc");
const $notice = document.getElementById("notice");
const $chip = document.getElementById("page-chip");

let port = null;
let state = null;
let generation = 0;
let pendingOpen = null;
let incoming = null; // 받는 중인 문서 바이트 { bytes, offset, overflow }
let readingUrls = []; // 읽기 모드 그림 blob URL
let imageUrls = new Map(); // 쪽 SVG 그림 key → blob URL (문서 하나 동안)
let pageObserver = null;
let searchSeq = 0; // 검색을 닫거나 새로 할 때마다 올린다 — 늦게 끝난 이전 검색 결과를 버리기 위해
const visiblePages = new Set();
// 문서 정보용 진단 값 — 엔진이 실제로 붙은 곳("worker" | "main")
let engineKind = null;

// ── 엔진(Worker) ─────────────────────────────────────────────
// 무거운 작업(render·parse)이 끝나면 그 Worker 를 끝내고 곧바로 새 Worker 를 띄워 둔다(PRD R7).
// - kordoc 이 배치·변환하며 만든 중간 결과(그림 base64·쪽 SVG 문자열·XML 트리)는 그림이 많은 문서에서 100MB 를 넘는데,
//   작업이 끝나면 할당이 멈춰 V8 이 한참(실기기 10~30초) 거두지 않는다. 그 사이 검색·보기 전환이 겹치면 쓰레기가 쌓인다.
// - 끝낸 Worker 의 힙은 V8 이 프로세스 공용 메모리 풀에 넣어 두고, 어느 isolate 에서든 나중에 GC 가 돌 때 운영체제에 돌려준다.
//   Worker 를 끝내기만 하면 메인 스레드가 한가할 때 GC 가 없어 풀이 계속 남았다(실기기에서 60초 넘게). 새 Worker 를 바로
//   띄워 두면 그 Worker 의 GC 로 30초 안팎에 돌아온다. 새 Worker 는 풀의 페이지를 먼저 다시 쓰고, 다음 요청(검색·보기 전환)도
//   Worker 가 뜨기를 기다리지 않는다(데스크톱 70ms). 한가한 새 Worker 는 실기기에서 10~20MB 를 쓴다.
// - 새 Worker 에는 문서를 처음부터 다시 올린다. 문서 바이트는 Blob 으로 들고 있다가 넘긴다.
// Worker 를 못 써 메인 스레드에 올린 엔진은 버릴 수 없으므로 계속 쓴다.

const HEAVY_OPS = new Set(["render", "parse"]);
const engine = createEngine();

function createEngine() {
  let nextId = 1;
  let current = null; // Promise<지금 엔진> — 없으면 다음 요청 때 띄운다
  let doc = null; // { seq, name, blob, format } 열린 문서
  let docSeq = 0;

  // pending: 빌려 간 수(그동안은 끝내지 않는다 — 새 문서를 열 때만 이전 문서의 Worker 를 바로 끝낸다),
  // dirty: 무거운 작업을 했다, docSeq: 올라가 있는 문서
  const newEngine = (call, terminate, disposable) => ({ call, terminate, disposable, pending: 0, dirty: false, retired: false, docSeq: 0, loading: null });
  const failed = (id) => ({ id, ok: false, error: { code: null, message: "engine reset" } });

  const viaWorker = () =>
    new Promise((resolve, reject) => {
      let worker;
      try {
        worker = new Worker("engine.js");
      } catch (err) {
        return reject(err);
      }
      const waiting = new Map();
      let stopped = false;
      const call = (req, transfer = []) =>
        new Promise((res) => {
          if (stopped) return res(failed(req.id));
          waiting.set(req.id, res);
          worker.postMessage(req, transfer);
        });
      // 끝내는 순간 기다리던 요청은 실패로 돌려준다 — 그 결과를 기다리는 쪽(이전 문서)이 멈춰 있지 않게
      const terminate = () => {
        stopped = true;
        worker.terminate();
        waiting.forEach((done, id) => done(failed(id)));
        waiting.clear();
      };
      const e = newEngine(call, terminate, true);
      worker.onerror = (ev) => {
        ev.preventDefault?.();
        e.retired = true;
        terminate();
        reject(new Error("worker failed"));
      };
      worker.onmessage = (ev) => {
        const done = waiting.get(ev.data.id);
        waiting.delete(ev.data.id);
        done?.(ev.data);
      };
      call({ id: 0, op: "ping" }).then((r) => (r.ok ? resolve(e) : reject(new Error("worker failed"))));
    });

  // Worker 를 못 쓰면 같은 엔진을 메인 스레드에 올린다 — 버릴 수 없으므로 계속 쓴다
  let script = null;
  const viaScript = () => {
    script ??= new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = "engine.js";
      s.onload = resolve;
      s.onerror = (err) => {
        script = null;
        reject(err);
      };
      document.head.appendChild(s);
    });
    return script.then(() => newEngine((req) => globalThis.kordocEngine.request(req), () => {}, false));
  };

  const spawn = () => {
    const p = (engineKind === "main" ? viaScript() : viaWorker().catch(() => viaScript())).then((e) => {
      engineKind = e.disposable ? "worker" : "main";
      e.from = p;
      return e;
    });
    p.catch(() => current === p && (current = null)); // 다음 요청 때 다시 시도한다
    return p;
  };

  async function lease() {
    for (;;) {
      const p = (current ??= spawn());
      const e = await p;
      if (!e.retired) {
        e.pending++;
        return e;
      }
      if (current === p) current = null;
    }
  }

  function retire(e) {
    if (e.retired) return;
    e.retired = true;
    e.terminate();
    if (current === e.from) current = spawn(); // 바로 새로 띄워 둔다 — 머리말 참고
  }

  function release(e, heavy) {
    e.pending--;
    if (heavy && e.disposable) e.dirty = true;
    if (e.dirty && e.pending === 0) retire(e);
  }

  async function call(e, op, payload, transfer) {
    const reply = await e.call({ id: nextId++, op, ...payload }, transfer);
    if (!reply.ok) throw Object.assign(new Error(reply.error.message), { code: reply.error.code });
    return reply.result;
  }

  // 지금 문서가 이 엔진에 올라가 있게 한다 — 같은 엔진에 요청이 겹쳐도 한 번만 올린다
  function ensureLoaded(e, d) {
    if (e.docSeq !== d.seq) {
      e.docSeq = d.seq;
      e.loading = call(e, "load", { name: d.name, blob: d.blob, format: d.format });
      e.loading.catch(() => e.docSeq === d.seq && (e.docSeq = 0));
    }
    return e.loading;
  }

  current = spawn(); // 문서 바이트가 오는 동안 미리 띄워 둔다

  return {
    /** 새 문서를 연다 → {format}. 이전 문서를 올린 Worker 는 버린다 — 그 문서의 남은 요청은 실패로 끝난다. */
    async open(name, buffer) {
      const d = (doc = { seq: ++docSeq, name, blob: new Blob([buffer]), format: null });
      // lease() 가 같은 약속을 기다리기 전에 실행된다(먼저 건 then 이 먼저 돈다)
      current?.then((e) => e.disposable && e.docSeq && retire(e), () => {});
      const e = await lease();
      try {
        e.docSeq = d.seq;
        e.loading = call(e, "load", { name, bytes: buffer }, [buffer]);
        e.loading.catch(() => e.docSeq === d.seq && (e.docSeq = 0));
        const result = await e.loading;
        d.format = result.format;
        return result;
      } finally {
        release(e, false);
      }
    },
    /** 열린 문서에 대한 요청 */
    async request(op, payload = {}) {
      const d = doc;
      if (!d) throw new Error("no document");
      const e = await lease();
      let done = false;
      try {
        // Worker 를 기다리는 사이 다른 문서가 열렸으면 그만둔다 — 새 문서의 Worker 에 이전 문서를 올리지 않게
        if (d !== doc) throw Object.assign(new Error("engine reset"), { code: null });
        await ensureLoaded(e, d);
        const result = await call(e, op, payload);
        done = true;
        return result;
      } finally {
        // 끝까지 한 무거운 작업만 Worker 를 바꾼다. 실패하면 show() 가 곧바로 다른 보기로 다시 시도하므로
        // 문서가 올라가 있는 같은 Worker 를 쓰게 둔다 — 그 작업이 끝나면 바뀐다.
        release(e, done && HEAVY_OPS.has(op));
      }
    },
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
    receiveSlice(new Uint8Array(data), "arraybuffer");
    return;
  }
  const msg = JSON.parse(data);
  switch (msg.type) {
    case "open":
      startIncoming(msg);
      break;
    case "chunk":
      receiveSlice(base64ToBytes(msg.data), "base64");
      break;
    case "end":
      finishIncoming();
      break;
    case "mode":
      if (state) switchMode(msg.mode);
      break;
    case "fontStep":
      document.documentElement.dataset.fontStep = String(msg.step);
      break;
    case "fontScale":
      setFontScale(msg.scale);
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

// 문서 크기만큼 미리 잡아 두고 조각을 채운다 — 조각 목록과 합친 사본을 따로 두지 않는다
function startIncoming(msg) {
  pendingOpen = msg;
  incoming = null;
  try {
    incoming = { bytes: new Uint8Array(Math.max(0, msg.size | 0)), offset: 0, overflow: false, transfer: null };
  } catch {
    pendingOpen = null;
    fail("TOO_LARGE");
  }
}

/** transfer: 실제로 쓴 전달 방식("arraybuffer" | "base64") — 문서 정보 진단용 */
function receiveSlice(slice, transfer) {
  if (!incoming) return;
  incoming.transfer ??= transfer;
  if (incoming.offset + slice.length > incoming.bytes.length) {
    incoming.overflow = true;
    return;
  }
  incoming.bytes.set(slice, incoming.offset);
  incoming.offset += slice.length;
}

function finishIncoming() {
  const req = pendingOpen;
  const got = incoming;
  pendingOpen = null;
  incoming = null;
  if (!req || !got) return;
  // 보내는 사이 파일이 바뀌었으면 받은 바이트를 믿을 수 없다
  if (got.overflow || got.offset !== got.bytes.length) return fail("UNREADABLE");
  openDocument({ ...req, transfer: got.transfer }, got.bytes.buffer);
}

// ── 열기 ─────────────────────────────────────────────────────

async function openDocument(req, buffer) {
  reset();
  const gen = ++generation;
  document.documentElement.dataset.fontStep = String(req.fontStep ?? 1);
  // 시스템 글꼴 배율은 문서와 따로 fontScale 메시지로 온다 — 여기서 덮어쓰면 그 사이 바뀐 값을 잃는다
  const t0 = performance.now();
  let format;
  try {
    ({ format } = await engine.open(req.name, buffer));
  } catch {
    if (gen === generation) fail("CORRUPTED");
    return;
  }
  if (gen !== generation) return;
  if (format === "legacy-office") return fail("LEGACY_OFFICE");
  if (!READING_FORMATS.has(format)) return fail("UNKNOWN_FORMAT");

  state = { gen, format, mode: null, pages: null, svgs: null, sceneTask: null, reading: null, readingTask: null, readingText: null, search: null, switching: null };
  const remembered = req.modes?.[format] ?? req.mode;
  const wanted = ORIGINAL_FORMATS.has(format) && remembered !== "reading" ? "original" : "reading";
  const result = await show(wanted);
  if (!result || gen !== generation) return;
  send({
    type: "opened", format, mode: result.mode, pages: result.pages, fallback: result.fallback,
    timing: Math.round(performance.now() - t0), engine: engineKind, transfer: req.transfer ?? null,
  });
}

// ── 보기 전환 ────────────────────────────────────────────────

async function switchMode(mode) {
  clearSearch();
  const st = state;
  const task = show(mode);
  st.switching = task; // 전환 중에 온 검색은 전환이 끝난 뒤의 화면에서 한다
  const result = await task;
  if (st.switching === task) st.switching = null;
  if (result && st === state) send({ type: "mode", mode: result.mode, pages: result.pages });
}

/**
 * 원하는 방식으로 그리고, 실패하면 다른 방식으로 그린다.
 * 원본이 안 되면 읽기로, 읽기가 안 되면(원본이 있는 형식이면) 원본으로. 둘 다 안 될 때만 문서를 닫는다.
 */
async function show(mode) {
  const st = state;
  // 전환이 겹치면(빠른 연타, 큰 문서의 긴 변환) 가장 나중 요청만 그린다 — 먼저 시작해 늦게 끝난 전환이 덮어쓰지 않게
  const seq = (st.viewSeq = (st.viewSeq ?? 0) + 1);
  const superseded = () => st !== state || seq !== st.viewSeq;
  const canOriginal = ORIGINAL_FORMATS.has(st.format);
  if (mode === "original" && !canOriginal) mode = "reading";
  const order = mode === "original" ? ["original", "reading"] : canOriginal ? ["reading", "original"] : ["reading"];
  let lastError = null;
  let encrypted = false;
  for (const m of order) {
    if (m !== mode && m === st.mode && $doc.firstChild) {
      // 바꾸려던 방식이 안 돼 보던 방식에 남는다 — 다시 그리지 않아 읽던 위치를 지킨다
      showNotice(m === "reading" ? "원본 모양을 그릴 수 없어 읽기 모드로 표시합니다." : "읽기 모드로 바꿀 수 없어 원본 모양으로 표시합니다.");
      return { mode: m, pages: st.pages?.length ?? 0, fallback: true };
    }
    try {
      if (m === "original") await ensureScene(st);
      else await ensureReading(st);
      if (superseded()) return null; // 그 사이 다른 문서가 열렸거나 더 나중 전환이 들어왔다
      if (m === "original") renderOriginal();
      else renderReading();
      st.mode = m;
      if (m !== mode) {
        showNotice(m === "reading" ? "원본 모양을 그릴 수 없어 읽기 모드로 표시합니다." : "읽기 모드로 바꿀 수 없어 원본 모양으로 표시합니다.");
      }
      return { mode: m, pages: st.pages?.length ?? 0, fallback: m !== mode };
    } catch (err) {
      if (superseded()) return null;
      // 한쪽이 암호 오류여도 다른 쪽으로 열리면 연다. 둘 다 안 될 때 암호 문서였다고 알린다.
      if (errorCode(err) === "ENCRYPTED") encrypted = true;
      lastError = err;
    }
  }
  return fail(encrypted ? "ENCRYPTED" : errorCode(lastError));
}

// 문서 배치는 한 번만 한다 — kordoc 은 쪽을 골라도 문서 전체를 배치하므로 '첫 쪽 먼저' 요청은 이득이 없다.
// 같은 작업을 동시에 두 번 부르면(빠른 연타) 진행 중인 작업을 같이 기다린다.
function ensureScene(st) {
  st.sceneTask ??= engine.request("render", { known: [...imageUrls.keys()] }).then((r) => {
    if (st !== state) return;
    registerImages(r.images);
    st.svgs = new Map(r.svgs.map(([p, svg]) => [p, resolveImages(svg)]));
    st.pages = r.pages;
  });
  st.sceneTask.catch(() => (st.sceneTask = null)); // 실패하면 다음에 다시 시도할 수 있게
  return st.sceneTask;
}

function ensureReading(st) {
  st.readingTask ??= engine.request("parse").then((r) => (st.reading = r));
  st.readingTask.catch(() => (st.readingTask = null));
  return st.readingTask;
}

// 쪽 SVG 그림은 엔진이 문서마다 한 번만 Blob 으로 만들어 보낸다 — 모든 쪽이 같은 blob URL 을 같이 쓴다
function registerImages(images) {
  for (const img of images ?? []) {
    if (!imageUrls.has(img.key)) imageUrls.set(img.key, URL.createObjectURL(img.blob));
  }
}

function resolveImages(svg) {
  return svg.replace(IMAGE_REF, (_, key) => `href="${imageUrls.get(key) ?? ""}"`);
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
    // aspect-ratio(Chrome 88+) 대신 padding 비율로 쪽 높이를 잡는다
    el.style.setProperty("--ratio", `${((p.height / p.width) * 100).toFixed(4)}%`);
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
      if (!el.querySelector("svg")) attachPage(el, page);
    } else {
      visiblePages.delete(page);
      el.replaceChildren();
    }
  }
}

function svgFor(page) {
  const s = state.search;
  return (s?.kind === "original" ? s.svgs.get(page) : undefined) ?? state.svgs.get(page);
}

function attachPage(el, page) {
  const svg = svgFor(page);
  if (!svg) return;
  el.innerHTML = svg;
  sanitize(el);
  if (state.search?.kind === "original" && state.search.svgs.has(page)) {
    // 강조 사각형에 쪽 안 순번을 붙여 둔다 — 색을 바꿔도 순번은 그대로라 '현재 결과'를 정확히 찾는다
    el.querySelectorAll(HIT_SELECTOR).forEach((r, i) => (r.dataset.hit = String(i)));
    markHits(el, page);
  }
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
  article.innerHTML = md.render(state.reading.markdown ?? "");
  sanitize(article);

  const byName = new Map((state.reading.images ?? []).map((img) => [img.filename, img]));
  article.querySelectorAll("img").forEach((img) => {
    const src = img.getAttribute("src") ?? "";
    const found = byName.get(src) ?? byName.get(src.split("/").pop());
    if (!found || !READING_IMAGE.test(found.mimeType)) {
      // WMF·EMF 등 브라우저가 못 그리는 그림 (PRD 8.5)
      const kind = found?.mimeType?.split("/")[1]?.toUpperCase();
      img.replaceWith(Object.assign(document.createElement("span"), { className: "img-missing", textContent: kind ? `[그림: ${kind}]` : "[그림]" }));
      return;
    }
    const url = URL.createObjectURL(found.blob);
    readingUrls.push(url);
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
// 읽기 모드: 문단·표 칸 안에서 이어진 글자를 붙여 찾고(textSegments), 걸린 글자 조각마다 <mark> 를 씌운다.
//   검색어의 공백은 본문의 어떤 공백(줄바꿈·<br> 포함)과도 맞고, 굵게·기울임 경계를 넘는 문구도 찾는다.
// 원본 보기: kordoc highlights 옵션으로 쪽을 다시 그려 강조 사각형을 얻는다(검색어가 걸린 쪽만 받는다).
//   kordoc 은 한 줄·한 서식 조각 안에서만 찾으므로 줄·서식 경계에 걸친 문구는 놓친다. 그래서 읽기 모드 기준으로도
//   세어 '읽기 n건'으로 알려 주고, 원본에서 하나도 못 찾았는데 읽기 모드에 있으면 읽기 모드로 넘어가 찾는다.

async function find(query) {
  clearSearch();
  const token = searchSeq;
  const st = state;
  const q = normalizeQuery(query);
  if (!q) return reportFound();
  // 보기 전환 중에 온 검색은 전환이 끝난 화면에서 한다(전환이 잇따르면 마지막 전환까지 기다린다)
  while (st.switching) {
    await st.switching;
    if (token !== searchSeq || st !== state) return;
  }

  if (st.mode === "reading") {
    st.search = { kind: "reading", hits: markText($doc.querySelector(".reading"), q), index: 0 };
    if (st.search.hits.length) showHit();
    return reportFound();
  }

  let result = null;
  try {
    result = await engine.request("render", { highlights: [q], known: [...imageUrls.keys()] });
  } catch {
    result = null;
  }
  // 그 사이 검색을 닫았거나 다시 했거나, 보기 방식·문서가 바뀌었으면 버린다
  const stale = () => token !== searchSeq || st !== state || st.mode !== "original";
  if (stale()) return;

  const hits = [];
  for (const [page, n] of result?.hits ?? []) for (let i = 0; i < n; i++) hits.push({ page, nth: i });

  if (hits.length > 0) {
    registerImages(result.images);
    const svgs = new Map(result.svgs.map(([p, svg]) => [p, resolveImages(svg)]));
    st.search = { kind: "original", hits, index: 0, svgs, readingTotal: -1 };
    refreshVisiblePages();
    showHit();
    reportFound();
    // 읽기 모드에서 더 찾을 수 있는지는 결과를 먼저 보여 준 뒤에 알려 준다
    const readingTotal = await countReadingHits(st, q);
    if (stale() || st.search?.kind !== "original") return;
    st.search.readingTotal = readingTotal;
    if (readingTotal > hits.length) reportFound();
    return;
  }

  const readingTotal = await countReadingHits(st, q);
  if (stale()) return;
  if (readingTotal > 0) {
    const shown = await show("reading");
    if (!shown || token !== searchSeq || st !== state) return;
    send({ type: "mode", mode: shown.mode, pages: shown.pages });
    if (shown.mode !== "reading") return reportFound();
    showNotice("원본 보기에서 찾지 못해 읽기 모드에서 찾았습니다.");
    st.search = { kind: "reading", hits: markText($doc.querySelector(".reading"), q), index: 0 };
    if (st.search.hits.length) showHit();
    return reportFound();
  }
  st.search = { kind: "original", hits: [], index: 0, svgs: new Map(), readingTotal };
  reportFound();
}

function normalizeQuery(query) {
  return String(query ?? "").replace(/\s+/g, " ").trim();
}

/** 검색어 → 정규식. 검색어의 공백은 본문의 어떤 공백(줄바꿈 포함)과도 맞는다. 대소문자는 가리지 않는다. */
function queryPattern(q) {
  const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/ /g, "\\s+");
  return new RegExp(escaped, "gi");
}

/**
 * 글자를 '이어진 구간'으로 묶는다 → [{ text, nodes:[{node, start}] }].
 * - 같은 블록(문단·표 칸) 안에서 이어지는 글자 노드는 한 구간 — 굵게·기울임 경계를 넘는 문구도 찾는다.
 * - 블록이 바뀌면 새 구간 — 칸 안에 든 표처럼 다른 블록이 끼어들면 그 앞뒤를 잇지 않아 결과가 문서 순서를 따른다.
 * - <br> 은 구간 글자에 줄바꿈 한 글자로 넣는다(어느 노드에도 속하지 않아 강조되지 않는다). 표 칸의 줄바꿈이 대개 이것이다.
 */
function textSegments(root) {
  const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
  const segments = [];
  let current = null;
  let currentBlock = null;
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const isText = n.nodeType === Node.TEXT_NODE;
    if (!isText && n.nodeName !== "BR") continue;
    const block = (isText ? n.parentElement : n).closest(BLOCK_SELECTOR) ?? root;
    if (!current || block !== currentBlock) {
      current = { text: "", nodes: [] };
      currentBlock = block;
      segments.push(current);
    }
    if (isText) {
      current.nodes.push({ node: n, start: current.text.length });
      current.text += n.nodeValue;
    } else {
      current.text += "\n";
    }
  }
  return segments;
}

/** 구간 글자에서 검색어가 걸린 [시작, 끝) 목록 */
function matchRanges(text, re) {
  const ranges = [];
  re.lastIndex = 0;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    if (m[0].length === 0) {
      re.lastIndex++;
      continue;
    }
    ranges.push([m.index, m.index + m[0].length]);
  }
  return ranges;
}

/** 걸린 자리 수 — kordoc 처럼 맞붙은 결과는 한 자리로 센다('하하하' 에서 '하' 는 한 자리) */
function countPlaces(text, re) {
  let n = 0;
  let end = -1;
  for (const [s, e] of matchRanges(text, re)) {
    if (s > end) n++;
    end = Math.max(end, e);
  }
  return n;
}

/**
 * 읽기 모드에서 찾을 수 있는 자리 수 — 변환에 실패하면 -1.
 * 읽기 모드 강조(markText)와 같은 구간 단위로, 원본 보기(kordoc)와 같은 방식(맞붙은 결과는 하나)으로 센다.
 * 그래야 '읽기 n건'이 원본 보기와 견줄 수 있고, 실제로 읽기 모드에서 보인다.
 */
async function countReadingHits(st, q) {
  try {
    if (st.readingText == null) {
      const reading = await ensureReading(st);
      // 화면에 붙이지 않는 문서로 해석한다 — 그림을 불러오지 않는다
      const parsed = new DOMParser().parseFromString(md.render(reading.markdown ?? ""), "text/html");
      st.readingText = textSegments(parsed.body).map((s) => s.text);
    }
    const re = queryPattern(q);
    return st.readingText.reduce((n, text) => n + countPlaces(text, re), 0);
  } catch {
    return -1;
  }
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
  if (s.kind === "reading") {
    // 결과 하나가 여러 <mark> 조각일 수 있다(서식 경계를 넘는 문구)
    s.hits.forEach((pieces, i) => pieces.forEach((m) => m.classList.toggle("current", i === s.index)));
    hit[0].scrollIntoView({ block: "center" });
    return;
  }
  const el = pageElement(hit.page);
  if (!el) return;
  if (!el.querySelector("svg")) attachPage(el, hit.page);
  // 붙어 있는 모든 쪽을 다시 칠한다 — 앞서 다른 쪽에 있던 '현재 결과' 표시를 지우기 위해
  $doc.querySelectorAll(".page").forEach((p) => {
    if (p.querySelector("rect[data-hit]")) markHits(p, Number(p.dataset.page));
  });
  (el.querySelector(`rect[data-hit="${hit.nth}"]`) ?? el).scrollIntoView({ block: "center" });
}

/** 붙어 있는 쪽의 강조 사각형 색을 다시 칠한다 — 현재 결과만 주황 */
function markHits(el, page) {
  const s = state.search;
  if (s?.kind !== "original") return;
  const current = s.hits[s.index];
  el.querySelectorAll("rect[data-hit]").forEach((r) => {
    const on = current != null && current.page === page && Number(r.dataset.hit) === current.nth;
    r.setAttribute("fill", on ? "#ff8a00" : "#ffd54f");
    r.setAttribute("fill-opacity", on ? "0.6" : "0.45");
  });
}

function clearSearch() {
  searchSeq++;
  const s = state?.search;
  if (!s) return;
  state.search = null;
  if (s.kind === "reading") {
    const marks = new Set();
    const parents = new Set();
    for (const pieces of s.hits) {
      for (const m of pieces) {
        if (!m.isConnected) continue;
        marks.add(m);
        parents.add(m.parentNode);
      }
    }
    parents.forEach((p) => unmark(p, marks));
  } else {
    refreshVisiblePages();
  }
}

/**
 * 부모 하나의 자식들을 한 번에 다시 짠다 — <mark> 와 이어진 글자 노드를 글자 하나로 합친다.
 * <mark> 를 하나씩 풀고 normalize() 하면 결과가 많은 긴 문단에서 느리다.
 */
function unmark(parent, marks) {
  const children = [];
  let text = null;
  for (const child of parent.childNodes) {
    if (child.nodeType === Node.TEXT_NODE || marks.has(child)) {
      text = (text ?? "") + child.textContent;
    } else {
      if (text !== null) children.push(document.createTextNode(text));
      text = null;
      children.push(child);
    }
  }
  if (text !== null) children.push(document.createTextNode(text));
  parent.replaceChildren(...children);
}

function reportFound() {
  const s = state?.search;
  const msg = { type: "found", current: s?.hits.length ? s.index + 1 : 0, total: s?.hits.length ?? 0 };
  if (s?.kind === "original" && s.readingTotal >= 0) msg.readingTotal = s.readingTotal;
  send(msg);
}

/**
 * 검색어에 <mark> 를 씌운다. 결과마다 <mark> 조각 배열을 문서 순서로 돌려준다.
 * 글자 노드마다 한 번만 다시 만든다 — 같은 노드를 결과마다 쪼개면 긴 글에서 느려진다.
 */
function markText(root, query) {
  if (!root) return [];
  const re = queryPattern(query);
  const hits = [];
  for (const seg of textSegments(root)) {
    const ranges = matchRanges(seg.text, re);
    if (!ranges.length) continue;
    // 노드별로 걸린 조각 [a, b, 결과 번호] 를 모은다. 결과도 노드도 앞에서부터 — 순서가 곧 문서 순서다.
    const perNode = new Map();
    let k = 0;
    for (const [s, e] of ranges) {
      const hitIndex = hits.push([]) - 1;
      while (k < seg.nodes.length && seg.nodes[k].start + seg.nodes[k].node.nodeValue.length <= s) k++;
      for (let j = k; j < seg.nodes.length && seg.nodes[j].start < e; j++) {
        const { node, start } = seg.nodes[j];
        const a = Math.max(s, start) - start;
        const b = Math.min(e, start + node.nodeValue.length) - start;
        if (b <= a) continue;
        if (!perNode.has(node)) perNode.set(node, []);
        perNode.get(node).push([a, b, hitIndex]);
      }
    }
    for (const [node, parts] of perNode) {
      const text = node.nodeValue;
      const frag = document.createDocumentFragment();
      let last = 0;
      for (const [a, b, hitIndex] of parts) {
        if (a > last) frag.appendChild(document.createTextNode(text.slice(last, a)));
        const mark = document.createElement("mark");
        mark.textContent = text.slice(a, b);
        frag.appendChild(mark);
        hits[hitIndex].push(mark);
        last = b;
      }
      if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
      node.replaceWith(frag);
    }
  }
  return hits.filter((pieces) => pieces.length);
}

// ── 공통 ─────────────────────────────────────────────────────

/**
 * 시스템 글꼴 크기(안드로이드 fontScale)를 읽기 모드 글자에만 곱한다 (PRD 6장 접근성).
 * 원본 보기 SVG 는 원본 글꼴 폭 기준으로 줄을 잡아 두었으므로 건드리지 않는다 — WebView textZoom 을 100 으로 고정한 이유.
 */
function setFontScale(scale) {
  const s = Number(scale);
  const clamped = Number.isFinite(s) ? Math.min(Math.max(s, 0.85), 2) : 1;
  document.documentElement.style.setProperty("--font-scale", String(clamped));
}

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
  readingUrls.forEach((u) => URL.revokeObjectURL(u));
  readingUrls = [];
  window.scrollTo(0, 0);
}

function reset() {
  clearView();
  searchSeq++;
  state = null;
  imageUrls.forEach((u) => URL.revokeObjectURL(u));
  imageUrls = new Map();
}

function showNotice(text) {
  $notice.textContent = text;
  $notice.hidden = false;
}

function fail(code) {
  reset();
  send({ type: "error", code });
  return null;
}

function errorCode(err) {
  const code = err?.code;
  if (code === "ENCRYPTED" || code === "ERR_CRYPTO_UNSUPPORTED" || /암호|password|encrypt/i.test(err?.message ?? "")) return "ENCRYPTED";
  if (code === "TOO_LARGE") return "TOO_LARGE";
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

// 개발용: 네이티브 없이 데스크톱 브라우저에서 확인할 때 쓴다. 보낸 메시지는 __messages 에 쌓인다.
window.__messages = [];
