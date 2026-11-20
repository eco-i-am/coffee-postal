import { useRef, useState } from "react";
import { api } from "./api.js";
import { shrink } from "./images.js";

/** Small square photo, or a plain box when there is none. */
export function Thumb({ url, alt = "", size = 36 }) {
  return url
    ? <img className="thumb" src={url} alt={alt} width={size} height={size} loading="lazy" decoding="async" />
    : <span className="thumb none" style={{ width: size, height: size }} aria-hidden="true" />;
}

/**
 * Choose, replace or remove the photo of one product or item. type is "product" or "item".
 * The photo is shrunk in the browser first, then sent on its own (separate from the other fields).
 */
export default function ImageField({ type, id, url, onChanged }) {
  const input = useRef(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const path = `/api/admin/${type === "product" ? "products" : "items"}/${id}/image`;

  async function pick(e) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setErr(""); setBusy(true);
    try {
      const blob = await shrink(file);
      await api(path, { method: "PUT", blob });
      await onChanged();
    } catch (e2) {
      setErr(e2.message || "Could not save that photo.");
    } finally { setBusy(false); }
  }

  async function remove() {
    setErr(""); setBusy(true);
    try { await api(path, { method: "DELETE" }); await onChanged(); }
    catch (e2) { setErr(e2.message); }
    finally { setBusy(false); }
  }

  return (
    <div className="imgfield">
      <Thumb url={url} size={72} alt="Current photo" />
      <div className="imgbtns">
        <span className="lbl">Photo</span>
        {id == null
          ? <p className="hint">Save first, then edit it to add a photo.</p>
          : (
            <>
              <div className="opts">
                <button type="button" className="btn ghost sm" disabled={busy} onClick={() => input.current?.click()}>{busy ? "Saving..." : url ? "Change photo" : "Add photo"}</button>
                {url && <button type="button" className="btn ghost sm" disabled={busy} onClick={remove}>Remove</button>}
              </div>
              <input ref={input} type="file" accept="image/*" hidden onChange={pick} />
            </>
          )}
        {err && <p className="err" role="alert">{err}</p>}
      </div>
    </div>
  );
}
