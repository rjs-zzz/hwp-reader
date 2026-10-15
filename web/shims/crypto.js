// Node `crypto` 대체 — 암호 문서(P2)에서만 쓰인다. v1 은 암호 문서를 열지 않으므로 호출되면 실패시킨다.
function unsupported() {
  const err = new Error("crypto is not available in the viewer bundle");
  err.code = "ERR_CRYPTO_UNSUPPORTED";
  throw err;
}

export const createHash = unsupported;
export const createCipheriv = unsupported;
export const createDecipheriv = unsupported;
export const pbkdf2Sync = unsupported;
export const timingSafeEqual = unsupported;
export const randomBytes = unsupported;
export const randomUUID = () => globalThis.crypto.randomUUID();

export default { createHash, createCipheriv, createDecipheriv, pbkdf2Sync, timingSafeEqual, randomBytes, randomUUID };
