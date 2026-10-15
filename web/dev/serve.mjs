// 개발용 정적 서버 — 앱 assets/viewer 와 표본 문서 폴더를 함께 띄운다.
//   node dev/serve.mjs [표본폴더]   (기본: web/samples, 환경변수 SAMPLES_DIR 도 가능)
// 표본 문서는 저장소에 넣지 않는다.
import http from "node:http";
import { readFile, readdir, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const viewerDir = path.resolve(root, "../../app/src/main/assets/viewer");
const samplesDir = path.resolve(process.argv[2] ?? process.env.SAMPLES_DIR ?? path.join(root, "../samples"));
const port = Number(process.env.PORT ?? 5173);
const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".json": "application/json" };

function safeJoin(base, rel) {
  const p = path.resolve(base, "." + path.sep + decodeURIComponent(rel));
  return p.startsWith(base) ? p : null;
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  try {
    let file;
    if (url.pathname === "/" || url.pathname === "/harness.html") file = path.join(root, "harness.html");
    else if (url.pathname === "/samples/") {
      const names = (await readdir(samplesDir)).filter((n) => !n.startsWith("."));
      const list = await Promise.all(names.map(async (name) => ({ name, size: (await stat(path.join(samplesDir, name))).size })));
      res.writeHead(200, { "content-type": types[".json"] });
      return res.end(JSON.stringify(list));
    } else if (url.pathname.startsWith("/samples/")) file = safeJoin(samplesDir, url.pathname.slice(9));
    else if (url.pathname.startsWith("/viewer/")) file = safeJoin(viewerDir, url.pathname.slice(8));
    if (!file) throw Object.assign(new Error("not found"), { code: "ENOENT" });
    const body = await readFile(file);
    res.writeHead(200, { "content-type": types[path.extname(file)] ?? "application/octet-stream" });
    res.end(body);
  } catch (err) {
    res.writeHead(err.code === "ENOENT" ? 404 : 500);
    res.end(String(err.message));
  }
}).listen(port, "127.0.0.1", () => console.log(`harness: http://localhost:${port}/  samples: ${samplesDir}`));
