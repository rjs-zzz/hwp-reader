// Node `zlib` 대체 — kordoc 이 쓰는 동기 API 만 pako 로 구현한다.
// maxOutputLength 를 넘으면 해제를 즉시 멈춰 압축 폭탄으로 메모리가 터지지 않게 한다.
import { Inflate, deflate, deflateRaw } from "pako";
import { Buffer } from "buffer";

function run(data, raw, opts) {
  const max = opts?.maxOutputLength ?? Infinity;
  const inflater = new Inflate({ raw });
  const chunks = [];
  let total = 0;
  inflater.onData = (chunk) => {
    total += chunk.length;
    if (total > max) {
      const err = new RangeError("Cannot create a Buffer larger than maxOutputLength");
      err.code = "ERR_BUFFER_TOO_LARGE";
      throw err;
    }
    chunks.push(chunk);
  };
  inflater.push(data instanceof Uint8Array ? data : new Uint8Array(data), true);
  if (inflater.err) {
    const err = new Error(inflater.msg || "zlib inflate error");
    err.code = "Z_DATA_ERROR";
    throw err;
  }
  return Buffer.concat(chunks.map((c) => Buffer.from(c.buffer, c.byteOffset, c.length)), total);
}

export function inflateRawSync(data, opts) {
  return run(data, true, opts);
}

export function inflateSync(data, opts) {
  return run(data, false, opts);
}

export function deflateRawSync(data) {
  const out = deflateRaw(data instanceof Uint8Array ? data : new Uint8Array(data));
  return Buffer.from(out.buffer, out.byteOffset, out.length);
}

export function deflateSync(data) {
  const out = deflate(data instanceof Uint8Array ? data : new Uint8Array(data));
  return Buffer.from(out.buffer, out.byteOffset, out.length);
}

export default { inflateRawSync, inflateSync, deflateRawSync, deflateSync };
