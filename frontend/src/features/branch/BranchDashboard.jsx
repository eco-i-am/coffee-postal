import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../shared/api.js";
import { useLoad } from "../../shared/hooks.js";
import { peso } from "../../shared/money.js";
import { METHOD_NAMES, STATUS_NAMES, TYPE_NAMES, hourLabel, prettyDate, timeOf, todayManila } from "../../shared/format.js";

function Kpi({ label, value, note }) {
  return (
    <div className="kpi">
      <small>{label}</small>
      <b>{value}</b>
      {note && <span>{note}</span>}
    </div>
  );
}

export default function BranchDashboard() {
  const [date, setDate] = useState(todayManila());
  const load = useCallback(() => api(`/api/branch/dashboard?date=${date}`), [date]);
  const { data: d, error, loading } = useLoad(load);
  const paid = d ? d.payments.reduce((s, p) => s + p.amount, 0) : 0;

  return (
    <section>
      <header className="pghead">
        <div>
          <h1>Branch Dashboard</h1>
          <p>{prettyDate(date)}</p>
        </div>
        <label className="datepick">
          Date
          <input type="date" value={date} max={todayManila()} onChange={(e) => e.target.value && setDate(e.target.value)} />
        </label>
      </header>
      {error && <p className="notice" role="alert">{error}</p>}
      {loading && !d && <p role="status">Loading...</p>}

      {d && (
        <>
          <div className="kpis">
            <Kpi label="Sales" value={peso(d.sales)} note="paid orders, VAT included" />
            <Kpi label="Orders" value={d.orders} />
            <Kpi label="Average order" value={peso(d.avgTicket)} />
            <Kpi label="Discounts given" value={peso(d.discounts)} note={`VAT in sales ${peso(d.vat)}`} />
          </div>

          {d.orders === 0 ? (
            <div className="sec"><h2>No sales yet</h2><p>Paid orders for this day will show up here. <Link to="/pos">Open the Point of Sale</Link></p></div>
          ) : (
            <div className="two">
              <div className="sec">
                <h2>Sales by hour</h2>
                <ColumnChart name="Sales by hour of the day" per="hour" what="Hour" noteHead=""
                  points={d.hourly.map((h) => ({ key: h.hour, label: hourLabel(h.hour), title: hourLabel(h.hour), value: h.sales }))} />
              </div>
              <div className="sec">
                <h2>How customers paid</h2>
                {d.payments.map((p) => (
                  <div key={p.method} className="meter">
                    <div className="mrow"><span>{METHOD_NAMES[p.method]} <small>{p.orders} {p.orders === 1 ? "order" : "orders"}</small></span><b>{peso(p.amount)}</b></div>
                    <div className="track"><i style={{ width: `${paid ? (p.amount / paid) * 100 : 0}%` }} /></div>
                  </div>
                ))}
              </div>
              <div className="sec tw">
                <h2>Top sellers</h2>
                <table>
                  <thead><tr><th>Item</th><th className="n">Sold</th><th className="n">Sales</th></tr></thead>
                  <tbody>
                    {d.top.map((t) => (
                      <tr key={t.name}><td><b>{t.name}</b></td><td className="n">{t.qty}</td><td className="n">{peso(t.amount)}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="sec tw">
                <h2>Latest orders</h2>
                <table>
                  <thead><tr><th>No.</th><th>Time</th><th>Type</th><th className="n">Total</th><th>Status</th></tr></thead>
                  <tbody>
                    {d.recent.map((o) => (
                      <tr key={o.id}>
                        <td><b>#{o.orderNo}</b></td><td>{timeOf(o.createdAt)}</td><td>{TYPE_NAMES[o.orderType]}</td>
                        <td className="n">{peso(o.total)}</td>
                        <td><span className={`tag ${o.status === "PAID" ? "g" : "r"}`}>{STATUS_NAMES[o.status]}</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p><Link to="/branch/orders">See all orders</Link></p>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
