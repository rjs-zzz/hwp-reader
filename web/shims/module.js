// Node `module.createRequire` 대체 — kordoc 은 HWP5 OLE 컨테이너용 `cfb` 하나만 require 한다.
import * as CFB from "cfb";

const modules = { cfb: CFB.default ?? CFB };

export function createRequire() {
  return (name) => {
    const mod = modules[name];
    if (!mod) throw new Error(`module not bundled: ${name}`);
    return mod;
  };
}

export default { createRequire };
