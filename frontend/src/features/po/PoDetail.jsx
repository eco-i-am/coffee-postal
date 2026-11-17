import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { PO_STATUS, dateTimeOf, poNo, poTone } from "../../shared/format.js";
import { useAuth } from "../../app/AuthContext.jsx";

/**
 * One purchase order. side="branch" can cancel and receive; side="hq" can approve (fewer if needed), reject and mark as sent.
 * Quantities typed here are only suggestions: the server checks every rule.
 */
export default function PoDetail({ id, side, onClose, onChanged }) {
  const { can } = useAuth();
  const base = side === "hq" ? "/api/admin/branch-pos" : "/api/branch/purchase-orders";
  const load = useCallback(() => api(`${base}/${id}`), [base, id]);
  const { data: d, error, reload } = useLoad(load);

  const [qty, setQty] = useState({});     // lineId -> typed quantity
  const [reason, setReason] = useState("");
  const [rejecting, setRejecting] = useState(false);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function act(path, body, doneMsg) {
    setBusy(true); setErr("");
    try {
      await api(`${base}/${id}/${path}`, { method: "POST", body });
      onChanged(doneMsg);
      await reload();
      setQty({}); setRejecting(false);
    } catch (e) { setErr(e.message); }
    finally { setBusy(false); }
  }

  if (error) return <Shell onClose={onClose}><p className="err" role="alert">{error}</p></Shell>;
  if (!d) return <Shell onClose={onClose}><p role="status">Loading...</p></Shell>;

  const { po, lines } = d;
  const status = po.status;
  const typed = (l, fallback) => (qty[l.id] !== undefined ? qty[l.id] : String(fallback));
  const asNumbers = (fallbackOf) => lines.map((l) => ({ lineId: l.id, qty: Number(typed(l, fallbackOf(l))) }));
  const bad = (n, max) => !Number.isInteger(n) || n < 0 || n > max;

  const canApprove = side === "hq" && status === "REQUESTED" && can("branch_po_admin_edit");
  const canShip = side === "hq" && status === "APPROVED" && can("branch_po_admin_edit");
  const canCancel = side === "branch" && status === "REQUESTED" && can("branch_po_edit");
  const canReceive = side === "branch" && status === "SHIPPED" && can("branch_po_edit");

  const approveLines = asNumbers((l) => l.qtyOrdered);
  const approveBad = approveLines.some((x, i) => bad(x.qty, lines[i].qtyOrdered)) || approveLines.every((x) => x.qty === 0);
  const receiveLines = asNumbers((l) => l.qtyApproved);
  const receiveBad = receiveLines.some((x, i) => bad(x.qty, lines[i].qtyApproved));

  const shownQty = (l) => (status === "REQUESTED" || status === "CANCELLED" || status === "REJECTED") ? l.qtyOrdered : (l.qtyApproved ?? l.qtyOrdered);

  return (
    <Shell onClose={onClose}>
      <h2>{poNo(po.id)} <span className={`tag ${poTone(status)}`}>{PO_STATUS[status]}</span></h2>
      <p className="hint">
        {po.branch} &middot; asked by {d.requestedBy} on {dateTimeOf(po.requestedAt)}
        {d.shippedAt && <> &middot; sent {dateTimeOf(d.shippedAt)}</>}
        {d.receivedAt && <> &middot; received {dateTimeOf(d.receivedAt)}</>}
      </p>
      {d.note && <p><b>Note:</b> {d.note}</p>}
      {d.rejectReason && <p className="notice" role="status">HQ said no: {d.rejectReason}</p>}

      <div className="tw">
        <table>
          <thead>
            <tr>
              <th>Item</th><th className="n">Ordered</th>
              {(status !== "REQUESTED" && status !== "CANCELLED" && status !== "REJECTED") && <th className="n">Approved</th>}
              {(status === "SHIPPED" || status === "RECEIVED") && <th className="n">Received</th>}
              <th className="n">Cost</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.id}>
                <td><b>{l.name}</b> <small>({l.unit})</small></td>
                <td className="n">
                  {canApprove
                    ? <input className="qtyin" aria-label={`Approve ${l.name}`} inputMode="numeric" value={typed(l, l.qtyOrdered)} onChange={(e) => setQty({ ...qty, [l.id]: e.target.value })} />
                    : l.qtyOrdered}
                  {canApprove && <small> of {l.qtyOrdered}</small>}
                </td>
                {(status !== "REQUESTED" && status !== "CANCELLED" && status !== "REJECTED") && <td className="n">{l.qtyApproved}</td>}
                {(status === "SHIPPED" || status === "RECEIVED") && (
                  <td className="n">
                    {canReceive
                      ? <input className="qtyin" aria-label={`Received ${l.name}`} inputMode="numeric" value={typed(l, l.qtyApproved)} onChange={(e) => setQty({ ...qty, [l.id]: e.target.value })} />
                      : <span className={l.qtyReceived != null && l.qtyReceived < l.qtyApproved ? "short" : ""}>{l.qtyReceived}</span>}
                  </td>
                )}
                <td className="n">{peso(l.unitCost * (canApprove ? Number(typed(l, l.qtyOrdered)) || 0 : (status === "RECEIVED" ? l.qtyReceived : shownQty(l))))}</td>
              </tr>
            ))}
          </tbody>
          <tfoot><tr><td colSpan="9" className="n"><b>Total {peso(po.value)}</b></td></tr></tfoot>
        </table>
      </div>
      {status === "RECEIVED" && lines.some((l) => l.qtyReceived < l.qtyApproved) && (
        <p className="hint">Some items arrived short. The branch only pays for what was received.</p>
      )}

      {rejecting && (
        <div className="addr"><label htmlFor="rj">Why is HQ saying no?</label>
          <input id="rj" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus /></div>
      )}
      {err && <p className="err" role="alert">{err}</p>}

      <div className="opts">
        <button className="btn ghost" onClick={onClose}>Close</button>
        {canCancel && <button className="btn ghost" disabled={busy} onClick={() => act("cancel", undefined, `${poNo(po.id)} cancelled.`)}>Cancel order</button>}
        {canReceive && <button className="btn warm" style={{ flex: 1 }} disabled={busy || receiveBad}
          onClick={() => act("receive", { lines: receiveLines }, `${poNo(po.id)} received and added to stock.`)}>Confirm what arrived</button>}
        {canShip && <button className="btn warm" style={{ flex: 1 }} disabled={busy} onClick={() => act("ship", undefined, `${poNo(po.id)} marked as sent.`)}>Mark as sent</button>}
        {canApprove && !rejecting && <button className="btn ghost" disabled={busy} onClick={() => setRejecting(true)}>Reject</button>}
        {canApprove && !rejecting && <button className="btn warm" style={{ flex: 1 }} disabled={busy || approveBad}
          onClick={() => act("approve", { lines: approveLines }, `${poNo(po.id)} approved.`)}>Approve</button>}
        {canApprove && rejecting && <button className="btn" style={{ flex: 1 }} disabled={busy || !reason.trim()}
          onClick={() => act("reject", { reason }, `${poNo(po.id)} rejected.`)}>Send rejection</button>}
      </div>
    </Shell>
  );
}

function Shell({ children, onClose }) {
  return (
    <div className="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal wide" role="dialog" aria-modal="true">{children}</div>
    </div>
  );
}
