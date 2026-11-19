import { useState } from "react";
import { BILLS, COINS, countTotal, denomLabel, pesoText, pieceCount } from "./denoms.js";
import { peso } from "./money.js";

/**
 * An optional bill and coin counter. Type how many of each; the total is worked out and sent to the amount box above
 * through onTotal (as "1234.50"). The amount box can still be typed in by hand.
 */
export default function DenomCounter({ onTotal, label = "Count bills and coins" }) {
  const [open, setOpen] = useState(false);
  const [counts, setCounts] = useState({});

  function change(key, raw) {
    const clean = raw.replace(/\D/g, "").slice(0, 6);
    const next = { ...counts, [key]: clean };
    setCounts(next);
    onTotal(pieceCount(next) === 0 ? "" : pesoText(countTotal(next)));
  }

  function clear() {
    setCounts({});
    onTotal("");
  }

  const total = countTotal(counts);

  if (!open) return <button type="button" className="btn ghost sm" style={{ alignSelf: "start" }} onClick={() => setOpen(true)}>{label}</button>;

  const column = (title, list) => (
    <div>
      <h3 className="dn-h">{title}</h3>
      {list.map((d) => {
        const q = Number(counts[d.key]) || 0;
        return (
          <div className="dn" key={d.key}>
            <label htmlFor={`dn-${d.key}`}>{denomLabel(d.cents)}</label>
            <input id={`dn-${d.key}`} inputMode="numeric" value={counts[d.key] ?? ""} onChange={(e) => change(d.key, e.target.value)} placeholder="0" aria-label={`${title} ${denomLabel(d.cents)}, how many`} />
            <span className="dn-sub">{q ? peso(q * d.cents) : ""}</span>
          </div>
        );
      })}
    </div>
  );

  return (
    <div className="denoms" role="group" aria-label="Bill and coin count">
      <div className="denoms-grid">
        {column("Bills", BILLS)}
        {column("Coins", COINS)}
      </div>
      <div className="denoms-total" role="status" aria-live="polite">
        <span>{pieceCount(counts)} pieces</span>
        <b>{peso(total)}</b>
      </div>
      <div className="opts">
        <button type="button" className="btn ghost sm" onClick={clear}>Clear counts</button>
        <button type="button" className="btn ghost sm" onClick={() => setOpen(false)}>Hide</button>
      </div>
    </div>
  );
}
