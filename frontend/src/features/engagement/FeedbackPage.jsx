import { useCallback, useState } from "react";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { presetRange } from "../../shared/dates.js";
import { dateTimeOf, todayManila } from "../../shared/format.js";
import { RangeBar, Kpi, ExportButton } from "../reports/widgets.jsx";
import PagedRows from "../../shared/PagedRows.jsx";

export const Stars = ({ n }) => <span className="stars" role="img" aria-label={`${n} out of 5`}>{"★".repeat(n)}<span className="starsoff">{"★".repeat(5 - n)}</span></span>;

/** Customer feedback from the till: the average, how the ratings spread, and every comment. Branch sees its own; HQ sees all and can pick a branch. */
export default function FeedbackPage({ side }) {
  const hq = side === "hq";
  const [range, setRange] = useState(() => presetRange("30d", todayManila()));
  const [branchId, setBranchId] = useState("");
  const branches = useLoad(useCallback(() => (hq ? api("/api/admin/branches") : Promise.resolve([])), [hq])).data || [];
  const load = useCallback(() => api(`/api/${hq ? "admin" : "branch"}/feedback?from=${range.from}&to=${range.to}${hq && branchId ? `&branchId=${branchId}` : ""}`),
    [hq, range.from, range.to, branchId]);
  const { data, error, loading } = useLoad(load);
  const s = data?.summary;
  const entries = data?.entries || [];
  const top = Math.max(1, ...(s?.byRating || [0]));
  const csv = [["Date", ...(hq ? ["Branch"] : []), "Rating", "Comment", "Order", "Entered by"],
    ...entries.map((e) => [dateTimeOf(e.at), ...(hq ? [e.branch] : []), e.rating, e.comment || "", e.orderRef || "", e.by])];

  return (
    <section className="fitpage">
      <header className="pghead">
        <div><h1>Customer feedback</h1><p>{hq ? "What customers said at the till, across all branches." : "What customers said at your till."}</p></div>
      </header>
      <RangeBar range={range} setRange={setRange} />
      {hq && (
        <div className="toolbar">
          <select aria-label="Branch" value={branchId} onChange={(e) => setBranchId(e.target.value)}>
            <option value="">All branches</option>
            {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </div>
      )}
      {error && <p className="err" role="alert">{error}</p>}
      {loading && !data ? <p role="status">Loading...</p> : s && (
        <>
          <div className="kpis">
            <Kpi label="Average rating" value={s.count ? `${s.average} / 5` : "No ratings"} note={s.count ? `${s.count} ${s.count === 1 ? "rating" : "ratings"}` : undefined} />
            <div className="sec tw">
              <h2>How they rated</h2>
              {[5, 4, 3, 2, 1].map((r) => (
                <div key={r} className="meter">
                  <div className="mrow"><span>{r} <small>{r === 1 ? "star" : "stars"}</small></span><b>{s.byRating[r - 1]}</b></div>
                  <div className="track"><i style={{ width: `${(s.byRating[r - 1] / top) * 100}%` }} /></div>
                </div>
              ))}
            </div>
          </div>
          <div className="sec fill">
            <div className="pghead"><h2>Comments and ratings</h2><ExportButton name="customer_feedback" rows={csv} range={range} /></div>
            <div className="tw">
              <table className="dense">
                <thead><tr><th>When</th>{hq && <th>Branch</th>}<th>Rating</th><th>Comment</th><th>Order</th></tr></thead>
                <tbody>
                  <PagedRows rows={entries} render={(e) => (
                    <tr key={e.id}>
                      <td>{dateTimeOf(e.at)}</td>
                      {hq && <td>{e.branch}</td>}
                      <td><Stars n={e.rating} /></td>
                      <td>{e.comment || ""}</td>
                      <td>{e.orderRef || ""}</td>
                    </tr>
                  )} />
                  {entries.length === 0 && <tr><td colSpan={hq ? 5 : 4} className="emptyrow">No feedback in this range.</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </section>
  );
}
