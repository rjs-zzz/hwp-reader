// shims/zlib.js 가 Node zlib 과 같은 결과를 내고, 압축 폭탄 상한을 지키는지 확인한다.
import { test } from "node:test";
import assert from "node:assert/strict";
import zlib from "node:zlib";
import { deflateRawSync, deflateSync, inflateRawSync, inflateSync } from "../shims/zlib.js";

const text = Buffer.from("한글 문서 ".repeat(5000));

test("raw inflate matches node zlib", () => {
  const packed = zlib.deflateRawSync(text);
  assert.deepEqual(Buffer.from(inflateRawSync(packed)), text);
});

test("zlib-wrapped inflate matches node zlib", () => {
  const packed = zlib.deflateSync(text);
  assert.deepEqual(Buffer.from(inflateSync(packed)), text);
});

test("deflate output is readable by node zlib", () => {
  assert.deepEqual(zlib.inflateRawSync(deflateRawSync(text)), text);
  assert.deepEqual(zlib.inflateSync(deflateSync(text)), text);
});

test("returns Buffer so kordoc can call Buffer methods", () => {
  const out = inflateRawSync(zlib.deflateRawSync(text));
  assert.ok(Buffer.isBuffer(out));
  assert.equal(typeof out.readUInt32LE, "function");
});

test("stops at maxOutputLength like node (decompression bomb)", () => {
  const bomb = zlib.deflateRawSync(Buffer.alloc(8 * 1024 * 1024));
  assert.throws(() => inflateRawSync(bomb, { maxOutputLength: 1024 * 1024 }), /maxOutputLength/);
});

test("corrupt data throws", () => {
  assert.throws(() => inflateRawSync(Buffer.from([0xff, 0xff, 0xff, 0xff, 0x00])));
});
