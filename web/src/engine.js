// kordoc 엔진 — 문서 판별·쪽 렌더·Markdown 변환. 화면이 멈추지 않도록 Web Worker 에서 돈다(PRD 8.5).
// Worker 를 만들 수 없는 환경에서는 같은 파일을 <script> 로 읽어 메인 스레드에서 돌린다(globalThis.kordocEngine).
//
// 요청 {id, op, ...} → 응답 {id, ok:true, result} | {id, ok:false, error:{code, message}}
//   op "ping"                       → "pong"   Worker 가 제대로 떴는지 확인용
//   op "load"   {name, bytes}       → {format}   형식: hwp·hwpx·hwp3·hwpml·pptx·docx·xlsx·xls·md·legacy-office·unknown
//   op "render" {highlights?}       → {pages:[{page,width,height}], svgs:[[page, svg]…], images:[{id, mimeType, data}],
//                                      hits?:[[page, count]…]}
//       쪽 SVG 안의 그림(href="data:…")은 href="kimg:<id>" 로 바꾸고 그림 바이트는 images 로 따로 한 번만 보낸다.
//       같은 그림이 여러 쪽에 나와도(머리글 로고 등) 한 벌만 오간다. 이미 보낸 그림은 다시 보내지 않는다.
//       highlights 가 있으면 검색어가 걸린 쪽만 svgs 에 담고, 쪽별 건수를 hits 로 준다.
//   op "parse"  {}                  → {markdown, images:[{filename, mimeType, data}]}
import "../shims/polyfills.js";
import { detectFormat, detectZipFormat, parse, renderDocumentToScene } from "kordoc";
import * as CFB from "cfb";

// kordoc 렌더러가 검색어 자리에 그리는 강조 사각형 (viewer.js 와 같은 값)
const HIT_MARK = 'fill="#ffd54f" fill-opacity="0.45"';
// 쪽 SVG 전체 글자 수 상한 — 그림을 뺀 뒤에도 이보다 크면 메모리 부족으로 렌더러가 죽기 전에 멈춘다
const MAX_SVG_CHARS = 64 * 1024 * 1024;
// kordoc 은 그림을 <image href="data:…"> 속성으로만 넣는다. 본문 글자의 따옴표는 &quot; 로 바뀌므로
// 속성 꼴(href="…")로 한정하면 문서 글자에 적힌 data URI 를 건드리지 않는다.
const DATA_IMAGE = /href="data:(image\/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)"/g;

let doc = null; // { name, buffer: ArrayBuffer, bytes: Uint8Array, format, imageIds: Map(그림 지문 → id) }

export async function handle(req) {
  switch (req.op) {
    case "ping":
      return { result: "pong" };
    case "load": {
      // 받은 버퍼를 그대로 쓴다 — 요청마다 문서를 복사하지 않는다
      const buffer = req.bytes;
      const loaded = { name: req.name, buffer, bytes: new Uint8Array(buffer), format: null, imageIds: new Map() };
      doc = loaded;
      loaded.format = await detect(loaded.bytes, loaded.buffer, req.name);
      return { result: { format: loaded.format } };
    }
    case "render": {
      requireDoc();
      // 렌더 도중 다른 문서가 열려도 이 문서의 그림 목록에만 기록한다
      const current = doc;
      const highlights = req.highlights?.length ? req.highlights : undefined;
      const { scene, pageSvgs } = await renderDocumentToScene(current.bytes, { highlights });
      const images = [];
      const svgs = [];
      const hits = [];
      const seen = new Map(); // 이번 렌더에서 이미 본 그림 — 쪽마다 반복되는 그림은 빠르게 알아본다
      let chars = 0;
      for (const [page, raw] of pageSvgs) {
        pageSvgs.delete(page); // 다룬 쪽의 원본 문자열은 바로 놓아 준다
        let n = 0;
        if (highlights) {
          // 검색 렌더: 걸리지 않은 쪽은 그림을 뽑을 필요도 없다(그림은 처음 렌더에서 이미 보냈다)
          n = countOccurrences(raw, HIT_MARK);
          if (n === 0) continue;
          hits.push([page, n]);
        }
        const svg = extractImages(raw, images, current.imageIds, seen);
        chars += svg.length;
        if (chars > MAX_SVG_CHARS) throw Object.assign(new Error("rendered pages too large"), { code: "TOO_LARGE" });
        svgs.push([page, svg]);
      }
      const result = { pages: scene.pages, svgs, images };
      if (highlights) result.hits = hits;
      return { result, transfer: images.map((i) => i.data.buffer) };
    }
    case "parse": {
      requireDoc();
      if (doc.format === "md") {
        return { result: { markdown: new TextDecoder().decode(doc.bytes), images: [] } };
      }
      const result = await parse(doc.buffer, { ocr: false });
      if (!result.success) throw Object.assign(new Error(result.error), { code: result.code });
      const images = (result.images ?? []).map((img) => ({ filename: img.filename, mimeType: img.mimeType, data: img.data }));
      return { result: { markdown: result.markdown, images }, transfer: images.map((i) => i.data.buffer) };
    }
    default:
      throw new Error(`unknown op: ${req.op}`);
  }
}

function requireDoc() {
  if (!doc) throw new Error("no document loaded");
}

/**
 * 쪽 SVG 의 그림을 href="kimg:<id>" 로 바꾼다. 처음 보는 그림만 out 에 담는다. 깨진 base64 는 그대로 둔다.
 * - imageIds: 문서 전체의 그림 지문 → id (렌더가 여러 번이어도 같은 그림은 같은 id, 한 번만 보낸다)
 * - seen: 이번 렌더 안에서 본 그림. 쪽마다 같은 그림이 반복되면(머리글 로고, 그림 채우기 무늬)
 *   표본 몇 글자로 후보를 찾고 문자열을 통째로 견줘 확인한다 — 수 MB 지문을 쪽마다 다시 계산하지 않는다.
 */
function extractImages(svg, out, imageIds, seen) {
  return svg.replace(DATA_IMAGE, (match, mimeType, b64) => {
    const quick = sampleKey(b64);
    const candidates = seen.get(quick);
    const known = candidates?.find((c) => c.b64 === b64);
    if (known) return `href="kimg:${known.id}"`;

    const key = `${b64.length}:${fingerprint(b64)}`;
    let id = imageIds.get(key);
    if (id === undefined) {
      let data;
      try {
        data = base64ToBytes(b64);
      } catch {
        return match;
      }
      id = imageIds.size + 1;
      imageIds.set(key, id);
      out.push({ id, mimeType, data });
    }
    if (candidates) candidates.push({ b64, id });
    else seen.set(quick, [{ b64, id }]);
    return `href="kimg:${id}"`;
  });
}

/** 길이와 고르게 뽑은 256 글자로 만든 빠른 열쇠 — 후보를 고를 때만 쓰고, 같은지는 문자열 비교로 확인한다 */
function sampleKey(s) {
  const step = Math.max(1, Math.floor(s.length / 256));
  let h = 0x811c9dc5 ^ s.length;
  for (let i = 0; i < s.length; i += step) h = Math.imul(h ^ s.charCodeAt(i), 0x01000193);
  return `${s.length}:${(h >>> 0).toString(36)}`;
}

/** 두 개의 32비트 FNV-1a 를 이어 붙인 지문 — 같은 그림 판별용 */
function fingerprint(s) {
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193 ^ s.length;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 0x01000193);
    h2 = Math.imul(h2 ^ c, 0x5bd1e995);
  }
  return `${(h1 >>> 0).toString(36)}${(h2 >>> 0).toString(36)}`;
}

function base64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function countOccurrences(s, needle) {
  let n = 0;
  for (let i = s.indexOf(needle); i !== -1; i = s.indexOf(needle, i + needle.length)) n++;
  return n;
}

async function detect(bytes, buffer, name) {
  const kind = detectFormat(buffer);
  if (kind === "hwpx") {
    const zip = await detectZipFormat(buffer);
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

export function looksLikeMarkdown(bytes, name) {
  if (!/\.(md|markdown|txt)$/i.test(name ?? "")) return false;
  try {
    // 앞부분만 본다. stream:true — 잘린 자리의 반쪽 글자(한글은 3바이트)를 오류로 치지 않는다
    new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(0, 64 * 1024), { stream: true });
    return true;
  } catch {
    return false;
  }
}

async function respond(req) {
  try {
    const { result, transfer = [] } = await handle(req);
    return { msg: { id: req.id, ok: true, result }, transfer };
  } catch (err) {
    return { msg: { id: req.id, ok: false, error: { code: err?.code ?? null, message: String(err?.message ?? err) } }, transfer: [] };
  }
}

if (typeof importScripts === "function") {
  self.onmessage = async (e) => {
    const { msg, transfer } = await respond(e.data);
    self.postMessage(msg, transfer);
  };
} else {
  globalThis.kordocEngine = { request: async (req) => (await respond(req)).msg };
}
