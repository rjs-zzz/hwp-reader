// esbuild `inject` — Node 전역(Buffer·process)을 번들 안에서만 제공한다.
import { Buffer } from "buffer";

export const process = { env: {}, platform: "android", getuid: undefined, nextTick: (fn, ...args) => queueMicrotask(() => fn(...args)) };
export { Buffer };
