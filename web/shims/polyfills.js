// 번들 대상은 Chrome 80(=esbuild target)인데, kordoc·뷰어가 그보다 새 API 를 쓴다.
// 업데이트가 안 된 WebView 에서도 돌도록 빠진 것만 채운다. viewer.js·engine.js 맨 앞에서 import 한다.

// Chrome 93
if (!Object.hasOwn) {
  Object.defineProperty(Object, "hasOwn", {
    value: (obj, key) => Object.prototype.hasOwnProperty.call(Object(obj), key),
    configurable: true,
    writable: true,
  });
}

// Chrome 85
if (!String.prototype.replaceAll) {
  Object.defineProperty(String.prototype, "replaceAll", {
    value: function replaceAll(pattern, replacement) {
      if (pattern instanceof RegExp) {
        if (!pattern.global) throw new TypeError("replaceAll must be called with a global RegExp");
        return this.replace(pattern, replacement);
      }
      const needle = String(pattern);
      if (typeof replacement === "function") {
        return this.split(needle).reduce((acc, part, i) => (i === 0 ? part : acc + replacement(needle) + part), "");
      }
      // $& 등 치환 패턴은 replace 와 같은 뜻을 갖도록 한 번씩 위임한다
      return this.split(needle).reduce((acc, part, i) => (i === 0 ? part : acc + needle.replace(needle, replacement) + part), "");
    },
    configurable: true,
    writable: true,
  });
}

// Chrome 92
function at(index) {
  const len = this.length;
  let i = Math.trunc(index) || 0;
  if (i < 0) i += len;
  return i < 0 || i >= len ? undefined : this[i];
}
for (const proto of [Array.prototype, String.prototype, Object.getPrototypeOf(Int8Array.prototype)]) {
  if (!proto.at) Object.defineProperty(proto, "at", { value: at, configurable: true, writable: true });
}

// Chrome 85
if (typeof globalThis.AggregateError !== "function") {
  globalThis.AggregateError = class AggregateError extends Error {
    constructor(errors, message) {
      super(message);
      this.name = "AggregateError";
      this.errors = Array.from(errors ?? []);
    }
  };
}

// Chrome 92
if (globalThis.crypto?.getRandomValues && !globalThis.crypto.randomUUID) {
  globalThis.crypto.randomUUID = () => {
    const b = globalThis.crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = [...b].map((x) => x.toString(16).padStart(2, "0"));
    return `${h.slice(0, 4).join("")}-${h.slice(4, 6).join("")}-${h.slice(6, 8).join("")}-${h.slice(8, 10).join("")}-${h.slice(10).join("")}`;
  };
}

// Chrome 86 (뷰어 화면 쪽에서만 쓴다 — Worker 에는 DOM 이 없다)
if (typeof Element !== "undefined" && !Element.prototype.replaceChildren) {
  Element.prototype.replaceChildren = function replaceChildren(...nodes) {
    while (this.firstChild) this.removeChild(this.firstChild);
    if (nodes.length) this.append(...nodes);
  };
}
