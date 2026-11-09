// 실기기 확인용 합성 시험 파일을 만든다 — 원내 문서·개인정보는 쓰지 않는다.
//   node dev/devtest.mjs [출력폴더]   (기본: web/samples/devtest — 저장소에 넣지 않는다)
// 각 파일과 기대 결과는 아래 FILES 주석 참고. 폰에 복사해 미디어 저장소 content:// URI 로 앱에 넘긴다(PRD 부록 B '방법').
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { markdownToHwpx } from "kordoc";
import CFB from "cfb";
import JSZip from "jszip";

const root = path.dirname(fileURLToPath(import.meta.url));
const out = path.resolve(process.argv[2] ?? path.join(root, "../samples/devtest"));
await mkdir(out, { recursive: true });

let md = "# 시험 문서\n\n";
for (let i = 1; i <= 120; i++) md += `## ${i}절\n\n이 문단은 실기기 확인용 합성 본문입니다. 번호 ${i}. 가나다라마바사 아자차카타파하.\n\n`;

const FILES = {
  // 여러 쪽 HWPX — 정상 표시, 쪽 칩, 쪽 이동(지금 쪽 번호가 전체 선택), 닫은 뒤 렌더러 종료
  "t01-long.hwpx": async () => new Uint8Array(await markdownToHwpx(md)),
  // 일반 텍스트 — '#' 줄이 제목이 되지 않고 <script> 줄이 사라지지 않으며 긴 줄은 화면 폭에서 접힌다
  "t02-plain.txt": () => "# 이건 제목이 아니다\n<script>지워지면 안 되는 줄</script>\n a*b*c 는 기울임이 아니다\n" + "아주긴줄".repeat(60) + "\n",
  // 구형 엑셀 — '구형 오피스 형식(.ppt·.doc·.xls)은 지원하지 않습니다.'
  "t03-legacy.xls": () => ole(["Workbook"]),
  // 암호 건 DOCX — '암호가 걸린 문서입니다…'
  "t04-secret.docx": () => ole(["EncryptionInfo", "EncryptedPackage"]),
  // 빈 파일 — '빈 파일입니다…'
  "t05-empty.hwp": () => new Uint8Array(0),
  // 300쪽 PDF — 쪽 크기를 읽는 동안 진행 표시가 돈다, 확대 전 글자가 또렷하다
  "t06-many.pdf": () => pdf(300),
  // 첫 항목에 PDF 머리가 든 ZIP — PDF 로 열리지 않고 '형식을 알 수 없습니다'
  "t07-zip-with-pdf.docx": async () => {
    const zip = new JSZip();
    zip.file("a.pdf", "%PDF-1.7\n" + "0".repeat(200), { compression: "STORE" });
    return zip.generateAsync({ type: "uint8array" });
  },
  // 본문에 '%PDF-' 를 적은 메모 — PDF 가 아니라 Markdown 으로 열린다
  "t08-notes.md": () => "# 메모\n\nPDF 파일은 %PDF-1.7 로 시작한다.\n",
};

function ole(streams) {
  const cfb = CFB.utils.cfb_new();
  for (const name of streams) CFB.utils.cfb_add(cfb, `/${name}`, Buffer.from("x".repeat(64)));
  return new Uint8Array(CFB.write(cfb, { type: "buffer" }));
}

/** 쪽마다 'Page N / 전체' 한 줄을 쓴 PDF (내장 Helvetica) */
function pdf(pages) {
  const objs = [];
  const add = (body) => objs.push(body);
  const catalog = add(null);
  const pagesObj = add(null);
  const font = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
  const kids = [];
  for (let i = 1; i <= pages; i++) {
    const text = `BT /F1 36 Tf 72 720 Td (Page ${i} / ${pages}) Tj ET\nBT /F1 12 Tf 72 690 Td (Synthetic test PDF for hwp-reader device check.) Tj ET`;
    const content = add(`<< /Length ${text.length} >>\nstream\n${text}\nendstream`);
    kids.push(add(`<< /Type /Page /Parent ${pagesObj} 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 ${font} 0 R >> >> /Contents ${content} 0 R >>`));
  }
  objs[catalog - 1] = `<< /Type /Catalog /Pages ${pagesObj} 0 R >>`;
  objs[pagesObj - 1] = `<< /Type /Pages /Kids [${kids.map((k) => `${k} 0 R`).join(" ")}] /Count ${pages} >>`;

  let s = "%PDF-1.4\n";
  const offsets = objs.map((body, i) => {
    const at = s.length;
    s += `${i + 1} 0 obj\n${body}\nendobj\n`;
    return at;
  });
  const xref = s.length;
  s += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  for (const o of offsets) s += `${String(o).padStart(10, "0")} 00000 n \n`;
  s += `trailer\n<< /Size ${objs.length + 1} /Root ${catalog} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(s, "latin1");
}

for (const [name, make] of Object.entries(FILES)) {
  await writeFile(path.join(out, name), await make());
}
console.log(`${Object.keys(FILES).length}개 → ${out}`);
