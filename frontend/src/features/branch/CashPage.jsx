import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { dateTimeOf } from "../../shared/format.js";
import { useAuth } from "../../app/AuthContext.jsx";
import ReadingSheet from "./ReadingSheet.jsx";
import { OpenShift, ShiftPanel } from "./ShiftParts.jsx";
import MyReport from "./MyReport.jsx";
import Modal from "../../shared/Modal.jsx";

const loadShift = () => api("/api/branch/shift");
const loadHistory = () => api("/api/branch/shifts");

export default function CashPage() {
  const { can } = useAuth();
  const navigate = useNavigate();
  const { data, error, loading, reload } = useLoad(loadShift);
  const noHistory = useCallback(() => Promise.resolve(null), []);
  const history = useLoad(can("cashier_reports_view") ? loadHistory : noHistory);
  const [err, setErr] = useState("");
  const [reading, setReading] = useState(null);
  const [photoFor, setPhotoFor] = useState(null);

  async function openReading(path) {
    setErr("");
    try { setReading(await api(path)); } catch (e2) { setErr(e2.message); }
  }

  const s = data?.shift;

  return (
    <section>
      <header className="pghead"><div><h1>Cash Drawer</h1><p>Your shift: opening cash, cash in and out, and the end-of-shift count.</p></div><button type="button" className="btn ghost sm" onClick={() => navigate("/branch/cash/practice")}>Practice</button></header>
      {error && <p className="notice" role="alert">{error}</p>}
      {err && <p className="err" role="alert">{err}</p>}
      {loading && !data && <p role="status">Loading...</p>}

      {data && !s && <OpenShift onDone={reload} />}

      {s && <ShiftPanel s={s} reload={reload} onClosed={history.reload} />}

      {can("pos_view") && !can("cashier_reports_view") && <MyReport />}

      {history.data && (
        <div className="sec tw">
          <h2>Past shifts</h2>
          <table>
            <thead><tr><th>Cashier</th><th>Opened</th><th>Closed</th><th className="n">Expected</th><th className="n">Counted</th><th className="n">Over / short</th><th></th></tr></thead>
            <tbody>
              {history.data.map((h) => (
                <tr key={h.id}>
                  <td><b>{h.cashier}</b></td><td>{dateTimeOf(h.openedAt)}</td><td>{h.closedAt ? dateTimeOf(h.closedAt) : <span className="tag y">Open</span>}</td>
                  <td className="n">{h.expected != null ? peso(h.expected) : ""}</td>
                  <td className="n">{h.counted != null ? peso(h.counted) : ""}</td>
                  <td className={`n${h.variance < 0 ? " short" : ""}`}>{h.variance != null ? (h.variance === 0 ? "Exact" : peso(h.variance)) : ""}</td>
                  <td className="rowbtns">{h.photo && <button className="btn ghost sm" type="button" onClick={() => setPhotoFor(h)}>Photo</button>}<button className="btn ghost sm" type="button" onClick={() => openReading(`/api/branch/shifts/${h.id}/reading`)}>{h.zNo ? `Z #${h.zNo}` : "X"}</button></td>
                </tr>
              ))}
              {history.data.length === 0 && <tr><td colSpan="7" className="emptyrow">No shifts yet.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {photoFor && (
        <Modal title={`${photoFor.cashier} opened the shift`} onClose={() => setPhotoFor(null)}>
          <p className="hint">{dateTimeOf(photoFor.openedAt)}</p>
          <img src={`/api/branch/shifts/${photoFor.id}/photo`} alt={`Photo taken when ${photoFor.cashier} opened the shift`} style={{ width: "100%", borderRadius: 12 }} />
          <div className="opts"><button className="btn ghost" type="button" onClick={() => setPhotoFor(null)}>Close</button></div>
        </Modal>
      )}

      {reading && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setReading(null)}>
          <div className="modal" role="dialog" aria-modal="true"><ReadingSheet r={reading} onClose={() => setReading(null)} /></div>
        </div>
      )}
    </section>
  );
}
