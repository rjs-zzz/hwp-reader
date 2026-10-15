// 뷰어 경로에서 호출되지 않는 Node 전용 모듈(fs·path·os·child_process·url·OCR·PDF 래스터 등)의 빈 대체물.
// 실수로 호출되면 조용히 넘어가지 않고 실패하도록 함수는 모두 예외를 던진다.
function unavailable() {
  throw new Error("Node-only API is not available in the viewer bundle");
}

// fs · fs/promises
export const existsSync = () => false;
export const readFileSync = unavailable;
export const readlinkSync = unavailable;
export const readFile = unavailable;
export const writeFile = unavailable;
export const createReadStream = unavailable;
export const createWriteStream = unavailable;
export const mkdir = unavailable;
export const readdir = unavailable;
export const rename = unavailable;
export const stat = unavailable;
export const unlink = unavailable;
// path · url · os · child_process · stream/promises
export const fileURLToPath = () => "";
export const dirname = () => "";
export const basename = (p) => String(p).split(/[\\/]/).pop();
export const join = (...parts) => parts.join("/");
export const platform = () => "android";
export const homedir = () => "";
export const hostname = () => "";
export const execFileSync = unavailable;
export const pipeline = unavailable;
// pdfjs-dist — PDF 는 네이티브 PdfRenderer 가 맡는다
export const getDocument = unavailable;
export const GlobalWorkerOptions = {};
export const OPS = {};
export const ImageKind = {};
export const normalizeUnicode = (s) => s;

export default {};
