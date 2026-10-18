// 빌드된 엔진 번들(app/src/main/assets/viewer/engine.js)을 Node 에서 그대로 돌려 본다.
// 먼저 `npm run build` 가 필요하다. 표본은 kordoc·cfb 로 즉석에서 만든다.
// SAMPLES_DIR 을 주면 그 폴더의 hwp·hwpx 도 모두 렌더해 본다.
import { test, before } from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import { markdownToHwpx } from "kordoc";
import CFB from "cfb";

const root = path.dirname(fileURLToPath(import.meta.url));
const bundle = path.resolve(root, "../../app/src/main/assets/viewer/engine.js");
const HIT_MARK = 'fill="#ffd54f" fill-opacity="0.45"';
// 1×1 PNG
const PNG = Uint8Array.from(atob("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="), (c) => c.charCodeAt(0));
let bundleCode;
let engine;
let nextId = 1;

before(async () => {
  bundleCode = await readFile(bundle, "utf8");
  vm.runInThisContext(bundleCode, { filename: "engine.js" });
  engine = globalThis.kordocEngine;
  assert.ok(engine, "engine bundle did not register globalThis.kordocEngine");
});

async function call(op, payload = {}, target = engine) {
  const reply = await target.request({ id: nextId++, op, ...payload });
  if (!reply.ok) throw Object.assign(new Error(reply.error.message), { code: reply.error.code });
  return reply.result;
}

const toBuffer = (bytes) => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
const load = (name, bytes, target) => call("load", { name, bytes: toBuffer(bytes) }, target);
const longText = (n) => Array.from({ length: n }, (_, i) => `문단 ${i} — ` + "가나다라마바사 ".repeat(20)).join("\n\n");

function oleFile(streams) {
  const cfb = CFB.utils.cfb_new();
  for (const name of streams) CFB.utils.cfb_add(cfb, `/${name}`, Buffer.from("x".repeat(64)));
  return new Uint8Array(CFB.write(cfb, { type: "buffer" }));
}

test("ping", async () => {
  assert.equal(await call("ping"), "pong");
});

test("hwpx: detect, render all pages, parse to markdown", async () => {
  const md = "# 제목\n\n본문 문단입니다. 누리호 발사.\n\n| 가 | 나 |\n| --- | --- |\n| 1 | 2 |\n";
  const hwpx = new Uint8Array(await markdownToHwpx(md));
  assert.deepEqual(await load("t.hwpx", hwpx), { format: "hwpx" });

  const render = await call("render", {});
  assert.ok(render.pages.length >= 1);
  assert.equal(render.svgs.length, render.pages.length);
  assert.match(render.svgs[0][1], /^<svg/);

  const parsed = await call("parse");
  assert.match(parsed.markdown, /제목/);
  assert.match(parsed.markdown, /누리호/);
});

test("an image repeated on many pages is sent once, not inlined per page", async () => {
  const md = Array.from({ length: 6 }, (_, i) => `![로고](logo.png)\n\n${longText(30)}\n\n끝 ${i}`).join("\n\n");
  await load("img.hwpx", new Uint8Array(await markdownToHwpx(md, { images: { "logo.png": PNG } })));
  const r = await call("render", {});
  assert.ok(r.pages.length > 1, "expected a multi-page document");
  const all = r.svgs.map(([, s]) => s).join("");
  assert.doesNotMatch(all, /data:image\//, "page SVGs must not inline image bytes");
  const refs = new Set([...all.matchAll(/kimg:(\d+)/g)].map((m) => Number(m[1])));
  assert.ok(refs.size >= 1, "expected image references");
  assert.equal(r.images.length, refs.size, "each distinct image sent exactly once");
  for (const img of r.images) {
    assert.equal(img.mimeType, "image/png");
    assert.deepEqual([...img.data.subarray(0, 4)], [0x89, 0x50, 0x4e, 0x47]);
  }
  // 같은 문서를 다시 그리면(검색 등) 이미 보낸 그림은 다시 보내지 않는다
  const again = await call("render", { highlights: ["끝"] });
  assert.equal(again.images.length, 0);
});

test("a data URI written in the document text is left alone", async () => {
  const text = "참고 주소 data:image/png;base64,iVBORw0KGgo= 는 그림이 아니다";
  await load("text.hwpx", new Uint8Array(await markdownToHwpx(text)));
  const r = await call("render", {});
  const all = r.svgs.map(([, s]) => s).join("");
  assert.equal(r.images.length, 0);
  assert.doesNotMatch(all, /kimg:/);
  assert.match(all, /data:image\/png;base64,iVBORw0KGgo=/);
});

test("search render returns only pages with hits, with per-page counts", async () => {
  const md = `${longText(40)}\n\n누리호 발사 성공. 누리호 2차.\n\n${longText(40)}\n\n누리호 3차.`;
  await load("search.hwpx", new Uint8Array(await markdownToHwpx(md)));
  const all = await call("render", {});
  assert.ok(all.pages.length > 2, "expected several pages");
  const r = await call("render", { highlights: ["누리호"] });
  assert.ok(r.svgs.length >= 1 && r.svgs.length < all.pages.length, `got ${r.svgs.length} of ${all.pages.length} pages`);
  const total = r.hits.reduce((n, [, c]) => n + c, 0);
  assert.ok(total >= 3, `expected >=3 hits, got ${total}`);
  for (const [page, svg] of r.svgs) {
    const count = svg.split(HIT_MARK).length - 1;
    assert.equal(count, r.hits.find(([p]) => p === page)[1]);
  }
  const none = await call("render", { highlights: ["없는검색어zz"] });
  assert.deepEqual([none.svgs.length, none.hits.length], [0, 0]);
});

test("legacy PowerPoint / Word OLE files are told apart from HWP5", async () => {
  assert.deepEqual(await load("a.ppt", oleFile(["PowerPoint Document"])), { format: "legacy-office" });
  assert.deepEqual(await load("a.doc", oleFile(["WordDocument"])), { format: "legacy-office" });
  assert.deepEqual(await load("a.hwp", oleFile(["FileHeader"])), { format: "hwp" });
});

test("markdown only by extension and valid UTF-8", async () => {
  const bytes = new TextEncoder().encode("# 메모\n\n- 항목");
  assert.deepEqual(await load("memo.md", bytes), { format: "md" });
  assert.match((await call("parse")).markdown, /메모/);
  assert.deepEqual(await load("memo.bin", bytes), { format: "unknown" });
  assert.deepEqual(await load("bad.md", new Uint8Array([0xff, 0xfe, 0xfd])), { format: "unknown" });
});

test("Korean markdown over 64KB is recognized whatever the byte alignment", async () => {
  // 64KB 에서 자를 때 한글(3바이트) 중간이 끊겨도 md 로 알아봐야 한다
  for (let pad = 0; pad < 3; pad++) {
    const text = "a".repeat(pad) + "# 긴 메모\n\n" + "가나다라마바사아자차카타파하 ".repeat(8000);
    const bytes = new TextEncoder().encode(text);
    assert.ok(bytes.length > 64 * 1024);
    assert.deepEqual(await load("long.md", bytes), { format: "md" }, `alignment ${pad}`);
  }
});

test("random bytes are unknown (e.g. DRM-wrapped files)", async () => {
  const junk = new Uint8Array(4096).map((_, i) => (i * 131 + 7) & 0xff);
  assert.deepEqual(await load("drm.hwp", junk), { format: "unknown" });
});

test("bundle works without APIs newer than Chrome 80 (old WebView)", async () => {
  // 새 API 를 지운 별도 실행 환경에서 번들을 돌린다 — 폴리필이 채워야 한다
  // Chrome 80 WebView 에도 있는 웹 API 만 넘긴다
  const ctx = vm.createContext({
    TextDecoder, TextEncoder, atob, btoa, console, setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask,
    AbortController, AbortSignal, EventTarget, Event, URL, URLSearchParams, Blob, performance,
    crypto: { getRandomValues: (a) => globalThis.crypto.getRandomValues(a) },
  });
  vm.runInContext(
    "delete Object.hasOwn; delete String.prototype.replaceAll; delete Array.prototype.at; delete String.prototype.at;" +
      "delete Object.getPrototypeOf(Int8Array.prototype).at; delete globalThis.AggregateError;",
    ctx,
  );
  assert.equal(vm.runInContext("typeof Object.hasOwn", ctx), "undefined");
  vm.runInContext(bundleCode, ctx, { filename: "engine.js" });
  const old = vm.runInContext("globalThis.kordocEngine", ctx);
  assert.equal(vm.runInContext("typeof Object.hasOwn + typeof ''.replaceAll + typeof [].at + typeof AggregateError + typeof crypto.randomUUID", ctx), "functionfunctionfunctionfunctionfunction");
  assert.equal(vm.runInContext("'a-b-c'.replaceAll('-', '+') + [1,2,3].at(-1) + Object.hasOwn({x:1}, 'x')", ctx), "a+b+c3true");

  const md = "# 제목\n\n수식 $x^2$ 과 <꺾쇠> 가 있는 본문.\n\n| 가 | 나 |\n| --- | --- |\n| 1 | 2 |\n";
  // 다른 실행 환경의 객체라 프로토타입이 달라 펼쳐서 비교한다
  assert.deepEqual({ ...(await load("old.hwpx", new Uint8Array(await markdownToHwpx(md)), old)) }, { format: "hwpx" });
  const r = await call("render", {}, old);
  assert.ok(r.svgs.length >= 1);
  assert.match((await call("parse", {}, old)).markdown, /제목/);
});

test("SAMPLES_DIR documents all render", { skip: !process.env.SAMPLES_DIR && "SAMPLES_DIR not set" }, async () => {
  const dir = process.env.SAMPLES_DIR;
  const failures = [];
  for (const name of (await readdir(dir)).filter((n) => /\.(hwpx?)$/i.test(n) && !/password/i.test(n))) {
    try {
      const { format } = await load(name, new Uint8Array(await readFile(path.join(dir, name))));
      if (format === "hwp" || format === "hwpx") {
        const r = await call("render", {});
        if (r.svgs.some(([, s]) => s.includes("data:image/"))) failures.push(`${name}: inlined image left in SVG`);
      }
    } catch (err) {
      failures.push(`${name}: ${err.message}`);
    }
  }
  assert.deepEqual(failures, []);
});
