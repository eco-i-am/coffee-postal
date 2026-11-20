import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { PO_NEXT, PO_STATUS, PO_STEP_LABEL, PO_REJECTABLE, PO_RECEIVABLE, dateTimeOf, poNo, poTone } from "../../shared/format.js";
import { useAuth } from "../../app/AuthContext.jsx";

/**
 * One purchase order. side="branch" can cancel, receive (in parts) and complete; side="hq" can approve (fewer if needed),
 * reject, and move it along the stages. Quantities typed here are only suggestions: the server checks every rule.
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
  const due = (l) => (l.qtyApproved ?? 0) - (l.qtyReceived ?? 0);
  const typed = (l, fallback) => (qty[l.id] !== undefined ? qty[l.id] : String(fallback));
  const asNumbers = (fallbackOf) => lines.map((l) => ({ lineId: l.id, qty: Number(typed(l, fallbackOf(l))) }));
  const bad = (n, max) => !Number.isInteger(n) || n < 0 || n > max;

  const hqEdit = side === "hq" && can("branch_po_admin_edit");
  const brEdit = side === "branch" && can("branch_po_edit");
  const canApprove = hqEdit && status === "REQUESTED";
  const nextSteps = hqEdit ? PO_NEXT[status] || [] : [];
  const canReject = hqEdit && PO_REJECTABLE.includes(status);
  const canCancel = brEdit && status === "REQUESTED";
  const canReceive = side === "branch" && can("branch_po_receive_edit") && PO_RECEIVABLE.includes(status) && lines.some((l) => due(l) > 0);
  const canComplete = brEdit && PO_RECEIVABLE.includes(status);

  const approveLines = asNumbers((l) => l.qtyOrdered);
  const approveBad = approveLines.some((x, i) => bad(x.qty, lines[i].qtyOrdered)) || approveLines.every((x) => x.qty === 0);
  const receiveLines = asNumbers(due);
  const receiveBad = receiveLines.some((x, i) => bad(x.qty, due(lines[i]))) || receiveLines.every((x) => x.qty === 0);

  const approved = !["REQUESTED", "CANCELLED", "REJECTED"].includes(status);
  const showReceived = PO_RECEIVABLE.includes(status) || status === "COMPLETED";
  const costQty = (l) => (canApprove ? Number(typed(l, l.qtyOrdered)) || 0
    : status === "DELIVERED" || status === "COMPLETED" ? (l.qtyReceived ?? 0)
    : approved ? (l.qtyApproved ?? l.qtyOrdered) : l.qtyOrdered);

  return (
    <Shell onClose={onClose}>
      <h2>{poNo(po.id)} <span className={`tag ${poTone(status)}`}>{PO_STATUS[status]}</span></h2>
      <p className="hint">
        {po.branch} &middot; asked by {d.requestedBy} on {dateTimeOf(po.requestedAt)}{po.deliverOn ? `, delivery wanted ${po.deliverOn}` : ""}
        {d.shippedAt && <> &middot; sent {dateTimeOf(d.shippedAt)}</>}
        {d.receivedAt && <> &middot; last received {dateTimeOf(d.receivedAt)}</>}
      </p>
      {d.note && <p><b>Note:</b> {d.note}</p>}
      {d.rejectReason && <p className="notice" role="status">HQ said no: {d.rejectReason}</p>}

      <div className="tw">
        <table>
          <thead>
            <tr>
              <th>Item</th><th className="n">Ordered</th>
              {approved && <th className="n">Approved</th>}
              {showReceived && <th className="n">Received so far</th>}
              {canReceive && <th className="n">Arrived now</th>}
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
                {approved && <td className="n">{l.qtyApproved}</td>}
                {showReceived && <td className="n"><span className={status === "COMPLETED" && (l.qtyReceived ?? 0) < (l.qtyApproved ?? 0) ? "short" : ""}>{l.qtyReceived ?? 0}</span></td>}
                {canReceive && (
                  <td className="n">
                    {due(l) > 0
                      ? <input className="qtyin" aria-label={`Arrived ${l.name}`} inputMode="numeric" value={typed(l, due(l))} onChange={(e) => setQty({ ...qty, [l.id]: e.target.value })} />
                      : "-"}
                  </td>
                )}
                <td className="n">{peso(l.unitCost * costQty(l))}</td>
              </tr>
            ))}
          </tbody>
          <tfoot><tr><td colSpan="9" className="n"><b>Total {peso(po.value)}</b></td></tr></tfoot>
        </table>
      </div>
      {status === "COMPLETED" && lines.some((l) => (l.qtyReceived ?? 0) < (l.qtyApproved ?? 0)) && (
        <p className="hint">Some items arrived short. The branch only pays for what was received.</p>
      )}
      {status === "DELIVERED" && <p className="hint">Part of this order is still to come. Count each delivery as it arrives, or complete the order if nothing more is coming.</p>}

      {rejecting && (
        <div className="addr"><label htmlFor="rj">Why is HQ saying no?</label>
          <input id="rj" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus /></div>
      )}
      {err && <p className="err" role="alert">{err}</p>}

      <div className="opts">
        <button className="btn ghost" onClick={onClose}>Close</button>
        {canCancel && <button className="btn ghost" disabled={busy} onClick={() => act("cancel", undefined, `${poNo(po.id)} cancelled.`)}>Cancel order</button>}
        {canComplete && <button className="btn ghost" disabled={busy} onClick={() => act("close", undefined, `${poNo(po.id)} completed.`)}>Complete order</button>}
        {canReceive && <button className="btn warm" style={{ flex: 1 }} disabled={busy || receiveBad}
          onClick={() => act("receive", { lines: receiveLines }, `${poNo(po.id)}: delivery counted and added to stock.`)}>Confirm what arrived</button>}
        {nextSteps.map((to) => (
          <button key={to} className="btn warm" style={{ flex: 1 }} disabled={busy} onClick={() => act("advance", { to }, `${poNo(po.id)}: ${PO_STATUS[to].toLowerCase()}.`)}>{PO_STEP_LABEL[to]}</button>
        ))}
        {canReject && !rejecting && !canApprove && <button className="btn ghost" disabled={busy} onClick={() => setRejecting(true)}>Reject</button>}
        {canApprove && !rejecting && <button className="btn ghost" disabled={busy} onClick={() => setRejecting(true)}>Reject</button>}
        {canApprove && !rejecting && <button className="btn warm" style={{ flex: 1 }} disabled={busy || approveBad}
          onClick={() => act("approve", { lines: approveLines }, `${poNo(po.id)} approved.`)}>Approve</button>}
        {rejecting && <button className="btn" style={{ flex: 1 }} disabled={busy || !reason.trim()}
          onClick={() => act("reject", { reason }, `${poNo(po.id)} rejected.`)}>Send rejection</button>}
      </div>

      {d.history?.length > 0 && (
        <div className="sec">
          <h3>History</h3>
          {d.history.map((h, i) => (
            <div className="r" key={i}>
              <b>{PO_STATUS[h.to]}</b> <small>{dateTimeOf(h.at)}{h.by ? ` · ${h.by}` : ""}{h.note ? ` · ${h.note}` : ""}</small>
            </div>
          ))}
        </div>
      )}
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
