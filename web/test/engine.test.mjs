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
let engine;
let nextId = 1;

before(async () => {
  vm.runInThisContext(await readFile(bundle, "utf8"), { filename: "engine.js" });
  engine = globalThis.kordocEngine;
  assert.ok(engine, "engine bundle did not register globalThis.kordocEngine");
});

async function call(op, payload = {}) {
  const reply = await engine.request({ id: nextId++, op, ...payload });
  if (!reply.ok) throw Object.assign(new Error(reply.error.message), { code: reply.error.code });
  return reply.result;
}

const load = (name, bytes) => call("load", { name, bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) });

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

test("render first page only still returns all page sizes", async () => {
  const md = Array.from({ length: 120 }, (_, i) => `문단 ${i} — ` + "가나다라마바사 ".repeat(20)).join("\n\n");
  await load("long.hwpx", new Uint8Array(await markdownToHwpx(md)));
  const first = await call("render", { pages: [1] });
  assert.ok(first.pages.length > 1, "expected a multi-page document");
  assert.equal(first.svgs.length, 1);
  assert.equal(first.svgs[0][0], 1);
});

test("highlights draw marker rects for search", async () => {
  await load("t.hwpx", new Uint8Array(await markdownToHwpx("누리호 발사 성공. 누리호 2차.")));
  const { svgs } = await call("render", { highlights: ["누리호"] });
  const all = svgs.map(([, s]) => s).join("");
  const hits = all.split('fill="#ffd54f" fill-opacity="0.45"').length - 1;
  assert.ok(hits >= 2, `expected >=2 highlight rects, got ${hits}`);
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

test("random bytes are unknown (e.g. DRM-wrapped files)", async () => {
  const junk = new Uint8Array(4096).map((_, i) => (i * 131 + 7) & 0xff);
  assert.deepEqual(await load("drm.hwp", junk), { format: "unknown" });
});

test("SAMPLES_DIR documents all render", { skip: !process.env.SAMPLES_DIR }, async () => {
  const dir = process.env.SAMPLES_DIR;
  const failures = [];
  for (const name of (await readdir(dir)).filter((n) => /\.(hwpx?)$/i.test(n) && !/password/i.test(n))) {
    try {
      const { format } = await load(name, new Uint8Array(await readFile(path.join(dir, name))));
      if (format === "hwp" || format === "hwpx") await call("render", { pages: [1] });
    } catch (err) {
      failures.push(`${name}: ${err.message}`);
    }
  }
  assert.deepEqual(failures, []);
});
