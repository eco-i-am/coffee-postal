import { canEncode, code128Bars } from "./code128.js";
import Modal from "./Modal.jsx";

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

/** A printable barcode label (Code 128) for a product or item: the barcode if it has one, otherwise its SKU. */
export default function LabelModal({ name, sku, barcode, onClose }) {
  const code = barcode || sku || "";
  const ok = canEncode(code);
  const { bars, width } = ok ? code128Bars(code) : { bars: [], width: 0 };
  const quiet = 10;
  const svg = ok
    ? `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width + quiet * 2} 60" width="260" height="78" role="img" aria-label="Barcode ${esc(code)}"><rect width="100%" height="100%" fill="#fff"/>${bars.map(([x, w]) => `<rect x="${x + quiet}" y="4" width="${w}" height="52" fill="#000"/>`).join("")}</svg>`
    : "";

  function print() {
    const w = window.open("", "_blank", "width=420,height=320");
    if (!w) return;
    w.document.write(`<!doctype html><title>Label</title><body style="font-family:sans-serif;text-align:center;margin:16px"><div style="font-weight:700;margin-bottom:4px">${esc(name)}</div>${svg}<div style="font-family:monospace;letter-spacing:2px">${esc(code)}</div></body>`);
    w.document.close();
    w.focus();
    w.print();
  }

  return (
    <Modal title="Barcode label" onClose={onClose}>
      {ok ? (
        <div style={{ textAlign: "center" }}>
          <b>{name}</b>
          <div dangerouslySetInnerHTML={{ __html: svg }} />
          <code>{code}</code>
        </div>
      ) : (
        <p className="err" role="alert">
          {code ? "That code has characters a barcode cannot hold. Use letters, digits and common punctuation, up to 40." : "Add a SKU or barcode first, then print its label."}
        </p>
      )}
      <div className="opts">
        <button className="btn ghost" type="button" onClick={onClose}>Close</button>
        {ok && <button className="btn warm" type="button" onClick={print} style={{ flex: 1 }}>Print label</button>}
      </div>
    </Modal>
  );
}
