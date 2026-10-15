// kordoc 엔진 — 문서 판별·쪽 렌더·Markdown 변환. 화면이 멈추지 않도록 Web Worker 에서 돈다(PRD 8.5).
// Worker 를 만들 수 없는 환경에서는 같은 파일을 <script> 로 읽어 메인 스레드에서 돌린다(globalThis.kordocEngine).
//
// 요청 {id, op, ...} → 응답 {id, ok:true, result} | {id, ok:false, error:{code, message}}
//   op "ping"                             → "pong"   Worker 가 제대로 떴는지 확인용
//   op "load"   {name, bytes}          → {format}   형식: hwp·hwpx·hwp3·hwpml·pptx·docx·xlsx·xls·md·legacy-office·unknown
//   op "render" {pages?, highlights?}     → {pages:[{page,width,height}], svgs:[[page, svg]…]}
//   op "parse"  {}                        → {markdown, images:[{filename, mimeType, data}]}
import { detectFormat, detectZipFormat, parse, renderDocumentToScene } from "kordoc";
import * as CFB from "cfb";

let doc = null; // { name, bytes: Uint8Array, format }

export async function handle(req) {
  switch (req.op) {
    case "ping":
      return { result: "pong" };
    case "load": {
      const bytes = new Uint8Array(req.bytes);
      doc = { name: req.name, bytes, format: await detect(bytes, req.name) };
      return { result: { format: doc.format } };
    }
    case "render": {
      requireDoc();
      const { scene, pageSvgs } = await renderDocumentToScene(doc.bytes, { pages: req.pages, highlights: req.highlights });
      return { result: { pages: scene.pages, svgs: [...pageSvgs] } };
    }
    case "parse": {
      requireDoc();
      if (doc.format === "md") {
        return { result: { markdown: new TextDecoder().decode(doc.bytes), images: [] } };
      }
      const result = await parse(toArrayBuffer(doc.bytes), { ocr: false });
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

function toArrayBuffer(bytes) {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

async function detect(bytes, name) {
  const buf = toArrayBuffer(bytes);
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
