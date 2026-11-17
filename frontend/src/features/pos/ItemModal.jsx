import { useState } from "react";
import { peso } from "../../shared/money.js";

export default function ItemModal({ item, onCancel, onAdd }) {
  const [size, setSize] = useState(item.sizes[0] || null);
  const [addons, setAddons] = useState([]);

  const unit = item.price + (size ? size.price : 0) + addons.reduce((s, a) => s + a.price, 0);
  const toggle = (a) =>
    setAddons((cur) => (cur.some((x) => x.id === a.id) ? cur.filter((x) => x.id !== a.id) : [...cur, a]));

  return (
    <>
      <h2>{item.name}</h2>
      {item.sizes.length > 0 && (
        <div>
          <h3>Size</h3>
          <div className="opts">
            {item.sizes.map((s) => (
              <button key={s.id} className="chip" aria-pressed={size?.id === s.id} onClick={() => setSize(s)}>
                {s.name}{s.price ? ` +${peso(s.price)}` : ""}
              </button>
            ))}
          </div>
        </div>
      )}
      {item.addons.length > 0 && (
        <div>
          <h3>Add-ons</h3>
          <div className="opts">
            {item.addons.map((a) => (
              <button key={a.id} className="chip" aria-pressed={addons.some((x) => x.id === a.id)} onClick={() => toggle(a)}>
                {a.name} +{peso(a.price)}
              </button>
            ))}
          </div>
        </div>
      )}
      <div className="opts">
        <button className="btn ghost" onClick={onCancel}>Cancel</button>
        <button className="btn warm" style={{ flex: 1 }} onClick={() => onAdd(size, addons)}>
          Add to order &middot; {peso(unit)}
        </button>
      </div>
    </>
  );
}
