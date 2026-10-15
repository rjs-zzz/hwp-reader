// kordoc 브라우저 번들 빌드 — 결과물은 앱 assets/viewer/ 로 간다.
// kordoc 소스는 고치지 않는다. Node 내장 모듈은 shims/ 로 바꿔 끼운다(PRD 8.3).
import * as esbuild from "esbuild";
import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.dirname(fileURLToPath(import.meta.url));
const outdir = path.resolve(root, "../app/src/main/assets/viewer");
const shim = (name) => path.join(root, "shims", name);
const watch = process.argv.includes("--watch");

// Node 내장 모듈과 뷰어에서 쓰지 않는 무거운 선택 의존성(OCR·PDF 래스터·헤드리스 브라우저)
const aliases = {
  zlib: shim("zlib.js"),
  crypto: shim("crypto.js"),
  module: shim("module.js"),
  stream: "readable-stream",
  events: "events",
  buffer: "buffer",
  jszip: path.join(root, "node_modules/jszip/lib/index.js"),
};
const emptyModules = [
  "fs", "fs/promises", "path", "os", "url", "util", "child_process", "stream/promises",
  "worker_threads", "http", "https", "net", "tls", "string_decoder",
  "pdfjs-dist/legacy/build/pdf.mjs", "pdfjs-dist/legacy/build/pdf.worker.mjs",
  "sharp", "puppeteer-core", "onnxruntime-node", "@hyzyla/pdfium", "@huggingface/transformers",
  "@modelcontextprotocol/sdk", "commander",
];

const nodeShims = {
  name: "node-shims",
  setup(build) {
    const strip = (p) => p.replace(/^node:/, "");
    build.onResolve({ filter: /.*/ }, (args) => {
      if (args.pluginData?.shimmed) return undefined; // build.resolve 재진입 방지
      const id = strip(args.path);
      if (emptyModules.includes(id) || id.startsWith("@modelcontextprotocol/")) return { path: shim("empty.js") };
      if (id in aliases) {
        const target = aliases[id];
        return path.isAbsolute(target) ? { path: target } : build.resolve(target, { resolveDir: root, kind: args.kind, pluginData: { shimmed: true } });
      }
      return undefined;
    });
  },
};

const options = {
  entryPoints: { viewer: path.join(root, "src/viewer.js") },
  outdir,
  bundle: true,
  platform: "browser",
  format: "iife",
  target: ["chrome80"],
  minify: !watch,
  sourcemap: watch ? "inline" : false,
  legalComments: "external",
  inject: [shim("globals.js")],
  define: { "import.meta.url": '"file:///kordoc/"', global: "globalThis" },
  plugins: [nodeShims],
  logLevel: "info",
  logLimit: 0,
  metafile: true,
};

// 번들에 실제로 들어간 패키지의 라이선스 원문을 모아 앱 고지 화면용 licenses.txt 로 낸다 (PRD 11장, F8)
async function writeLicenses(metafile) {
  const pkgDirs = new Set();
  for (const input of Object.keys(metafile.inputs)) {
    const m = input.replace(/\\/g, "/").match(/^(.*node_modules\/(?:@[^/]+\/)?[^/]+)\//);
    if (m) pkgDirs.add(m[1]);
  }
  const sections = [];
  for (const dir of [...pkgDirs].sort()) {
    const abs = path.join(root, dir);
    const pkg = JSON.parse(await readFile(path.join(abs, "package.json"), "utf8"));
    const files = (await readdir(abs, { withFileTypes: true }))
      .filter((e) => e.isFile() && /^(licen[cs]e|copying|notice|third_party)/i.test(e.name))
      .map((e) => e.name)
      .sort();
    const texts = await Promise.all(files.map((f) => readFile(path.join(abs, f), "utf8")));
    sections.push(`${"=".repeat(72)}\n${pkg.name} ${pkg.version} — ${pkg.license ?? "see below"}\n${"=".repeat(72)}\n\n${texts.join("\n\n").trim()}\n`);
  }
  const header = "jszip 은 MIT 또는 GPL-3.0 중 MIT 를 선택해 사용한다.\n\n";
  await writeFile(path.join(outdir, "licenses.txt"), header + sections.join("\n"));
}

await rm(outdir, { recursive: true, force: true });
await mkdir(outdir, { recursive: true });
await cp(path.join(root, "public"), outdir, { recursive: true });

if (watch) {
  const ctx = await esbuild.context(options);
  await ctx.watch();
} else {
  const result = await esbuild.build(options);
  await esbuild.analyzeMetafile(result.metafile).then((text) => {
    const top = text.split("\n").filter((l) => l.trim()).slice(0, 15).join("\n");
    console.log(top);
  });
  await writeLicenses(result.metafile);
  const js = await readFile(path.join(outdir, "viewer.js"));
  const kb = (n) => `${Math.round(n / 1024)}KB`;
  console.log(`viewer.js ${kb((await stat(path.join(outdir, "viewer.js"))).size)} (gzip ${kb(gzipSync(js).length)})`);
}
