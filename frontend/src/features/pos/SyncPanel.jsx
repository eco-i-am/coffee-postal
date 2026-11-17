import { useSync } from "../../app/SyncContext.jsx";
import { peso } from "../../shared/money.js";

/** What the cashier needs to know about offline selling: are we offline, what is waiting, what needs a person. */
export default function SyncPanel({ menuSavedAt }) {
  const s = useSync();
  return (
    <>
      {!s.online && (
        <p className="notice offline" role="status">
          No internet. You can keep selling. Orders are saved on this device and upload by themselves when it is back. Do not clear the browser's data or sign out of this device.
        </p>
      )}
      {menuSavedAt && (
        <p className="notice" role="status">
          Showing the menu saved on {new Date(menuSavedAt).toLocaleString("en-PH", { dateStyle: "medium", timeStyle: "short" })}. Prices and items may have changed since.
        </p>
      )}
      {s.pending > 0 && (
        <p className="notice" role="status">
          {s.pending} {s.pending === 1 ? "order is" : "orders are"} waiting to upload.{" "}
          <button className="btn ghost sm" onClick={s.syncNow} disabled={s.syncing || !s.online}>{s.syncing ? "Uploading..." : "Upload now"}</button>
        </p>
      )}
      {s.notes.length > 0 && (
        <div className="notice" role="status">
          {s.notes.map((n, i) => <p key={i}>{n}</p>)}
          <button className="btn ghost sm" onClick={s.dismissNotes}>OK</button>
        </div>
      )}
      {s.rejected.length > 0 && (
        <div className="notice bad" role="alert">
          <b>{s.rejected.length === 1 ? "1 order was not accepted" : `${s.rejected.length} orders were not accepted`}.</b> The customer was already served, so tell your manager and write these down.
          {s.rejected.map((e) => (
            <div key={e.id} className="rejected">
              <div>
                <b>{e.ref}</b> &middot; {peso(e.expectedTotal)} &middot; {new Date(e.queuedAt).toLocaleString("en-PH", { dateStyle: "medium", timeStyle: "short" })}
                <br /><small>{e.error}</small>
                <br /><small>{e.request.lines.length} {e.request.lines.length === 1 ? "line" : "lines"}, paid by {e.request.paymentMethod}</small>
              </div>
              <div className="rowbtns">
                <button className="btn ghost sm" onClick={() => s.retry(e.id)}>Try again</button>
                <button className="btn ghost sm" onClick={() => window.confirm(`Remove ${e.ref} from this device? Only do this once it is written down.`) && s.discard(e.id)}>Remove</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
