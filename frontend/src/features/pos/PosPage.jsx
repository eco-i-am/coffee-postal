import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../shared/api.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { loadMenu as loadSavedMenu, saveMenu } from "../../shared/offline.js";
import SyncPanel from "./SyncPanel.jsx";
import { useLoad } from "../../shared/hooks.js";
import { peso, totals } from "../../shared/money.js";
import TileArt from "./tileArt.jsx";
import { useImages } from "../../shared/images.js";
import { addToCart, cartGross, changeQty, newOrderId } from "./cart.js";
import ItemModal from "./ItemModal.jsx";
import PayModal from "./PayModal.jsx";
import Receipt from "./Receipt.jsx";
import Modal from "../../shared/Modal.jsx";
import ClockPage from "../clock/ClockPage.jsx";
import { OpenShift, ShiftPanel } from "../branch/ShiftParts.jsx";
import MyReport from "../branch/MyReport.jsx";

export const TYPES = [["DINE_IN", "Dine-in"], ["TAKEOUT", "Takeout"], ["DELIVERY", "Delivery"]];

export default function PosPage() {
  const { me, can } = useAuth();
  const [menuSavedAt, setMenuSavedAt] = useState(null);
  // the menu is saved on every successful load, and used when there is no internet
  const loadMenu = useCallback(async () => {
    try {
      const m = await api("/api/pos/menu", { timeoutMs: 8000 });
      saveMenu(me.username, m);
      setMenuSavedAt(null);
      return m;
    } catch (e) {
      const saved = e.status === 0 ? loadSavedMenu(me.username) : null;
      if (saved) { setMenuSavedAt(saved.savedAt); return saved.menu; }
      throw e;
    }
  }, [me.username]);
  const { data: menu, error, loading } = useLoad(loadMenu);
  const loadShift = useCallback(() => api("/api/branch/shift").catch(() => null), []);
  const { data: shiftInfo, reload: reloadShift } = useLoad(loadShift);
  const [drawer, setDrawer] = useState(false);   // the cash drawer dialog

  const imgs = useImages();
  // Favourites: the items this device taps most, so the usual orders are one tap away (kept on the tablet, works offline)
  const tapsKey = `cp.pos.taps.v1:${me.username}`;
  const [taps, setTaps] = useState(() => { try { return JSON.parse(localStorage.getItem(tapsKey) || "{}"); } catch { return {}; } });
  const [cat, setCat] = useState(() => (Object.keys(taps).length ? "fav" : "All"));
  const [discOpen, setDiscOpen] = useState(false);   // the discount panel
  const [nameOpen, setNameOpen] = useState(false);   // the optional name or table line
  const [confirmClear, setConfirmClear] = useState(false);
  const [q, setQ] = useState("");   // search the menu by name
  const [cart, setCart] = useState([]);
  const [otype, setOtype] = useState("DINE_IN");
  const [disc, setDisc] = useState("NONE");
  const [idNo, setIdNo] = useState("");
  const [who, setWho] = useState("");   // optional name or label for the order ("Ana", "Table 4")
  const [orderId, setOrderId] = useState(null);
  const [clocking, setClocking] = useState(false);   // the time clock over the POS, for staff sharing this device
  const [modal, setModal] = useState(null); // { kind: "item", item } | { kind: "pay" } | { kind: "receipt", data }

  const rule = (menu?.discounts || []).find((d) => d.code === disc) || null;
  const vatRegistered = menu?.vatRegistered ?? true;
  const t = totals(cartGross(cart), rule, vatRegistered);
  const needsId = !!rule?.requiresId;
  const needsPin = rule?.approvalOver != null && t.discount > rule.approvalOver;
  const needle = q.trim().toLowerCase();
  const favItems = (menu?.items || []).filter((m) => taps[m.id] > 0).sort((a, b) => taps[b.id] - taps[a.id]).slice(0, 12);
  const view = cat === "fav" && favItems.length === 0 ? "All" : cat;   // no favourites yet: show everything
  // while searching, look across every category
  const items = needle
    ? (menu?.items || []).filter((m) => m.name.toLowerCase().includes(needle))
    : view === "fav" ? favItems
    : (menu?.items || []).filter((m) => view === "All" || (view.startsWith("tag:") ? m.tags?.includes(view.slice(4)) : m.category === view));
  const cartCount = cart.reduce((n, l) => n + l.qty, 0);

  function edit(fn) {
    setCart(fn);
    setOrderId(null); // the order changed, so it is a new sale
  }

  // count what was actually sold, once the order is paid, so the Favourites tiles never move while an order is being taken
  function countSold(lines) {
    setTaps((old) => {
      const next = { ...old };
      for (const l of lines) next[l.productId] = (next[l.productId] || 0) + l.qty;
      try { localStorage.setItem(tapsKey, JSON.stringify(next)); } catch { /* favourites just will not be kept */ }
      return next;
    });
  }

  function clearOrder() {
    setCart([]); setDisc("NONE"); setIdNo(""); setWho(""); setOrderId(null); setConfirmClear(false); setNameOpen(false);
  }

  function tap(item) {
    if (!item.sizes.length && !item.addons.length) edit((c) => addToCart(c, item, null, []));
    else setModal({ kind: "item", item });
  }

  function openPay() {
    if (!orderId) setOrderId(newOrderId());
    setModal({ kind: "pay" });
  }

  function paid(res, method, tendered) {
    setModal({ kind: "receipt", data: { res, method, tendered, cart, otype, discName: rule?.name, idNo: needsId ? idNo.trim() : "", vatRegistered, who: who.trim() } });
    countSold(cart);
    clearOrder();
  }

  // no shift, no sales: the till stays locked until the cashier counts the drawer and starts a shift
  // (shiftInfo is empty when it could not be loaded, such as offline, and then the till is not locked)
  if (shiftInfo && !shiftInfo.shift && can("cash_inout_add")) {
    return (
      <section className="fitpage">
        <header className="pghead"><div><h1>Start your shift</h1><p>The Point of Sale opens once your shift has started.</p></div></header>
        <OpenShift onDone={reloadShift} />
      </section>
    );
  }

  return (
    <section className="fitpage">
      <header className="pghead poshead">
        <div>
          <h1>Point of Sale</h1>
          <p>Tap an item to start an order.{vatRegistered ? " Prices include 12% VAT." : ""}</p>
        </div>
        <input className="searchbox possearch" type="search" aria-label="Search the menu" placeholder="Search the menu" value={q} onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && items.length === 1) { tap(items[0]); setQ(""); } else if (e.key === "Escape") setQ(""); }} />
        {can("cash_inout_view") && <button type="button" className="btn ghost sm clockbtn" onClick={() => setDrawer(true)}>Cash drawer</button>}
        {can("time_clock_add") && <button type="button" className="btn ghost sm clockbtn" onClick={() => setClocking(true)}>Time In / Out</button>}
      </header>
      {error && <p className="notice" role="alert">{error}</p>}
      <SyncPanel menuSavedAt={menuSavedAt} />
      {shiftInfo && !shiftInfo.shift && (
        <p className="notice" role="status">No shift is open, so cash sales will not be counted in your drawer. <Link to="/branch/cash">Open your shift</Link></p>
      )}

      <div className="pos">
        <nav className="catrail" aria-label="Categories">
          {favItems.length > 0 && <button type="button" className="catbtn fav" aria-pressed={!needle && view === "fav"} onClick={() => { setCat("fav"); setQ(""); }}><span aria-hidden="true">&#9733;</span>Favourites</button>}
          {["All", ...(menu?.categories || [])].map((c) => (
            <button key={c} type="button" className="catbtn" aria-pressed={!needle && c === view} onClick={() => { setCat(c); setQ(""); }}>{c}</button>
          ))}
          {(menu?.tags || []).map((tg) => (
            <button key={`tag:${tg}`} type="button" className="catbtn" aria-pressed={!needle && view === `tag:${tg}`} onClick={() => { setCat(`tag:${tg}`); setQ(""); }}># {tg}</button>
          ))}
        </nav>
        <div className="menu">
          {menu && items.length === 0 && <p className="hint" role="status">{needle ? `Nothing matches "${q.trim()}".` : "Nothing in this category."}</p>}
          {items.map((m) => (
            <button key={m.id} className="tile" style={{ "--c": m.color }} onClick={() => tap(m)}>
              <span className="art">{imgs.url("product", m.id) ? <img className="tilephoto" src={imgs.url("product", m.id)} alt="" loading="lazy" decoding="async" /> : <TileArt kind={m.art} />}</span>
              <b>{m.name}</b>
              <span className="pr"><small>&#8369;</small>{Math.floor(m.price / 100)}</span>
            </button>
          ))}
          {!loading && items.length === 0 && !error && (
            <div className="empty">No items on the menu yet. Ask HQ to add products.</div>
          )}
        </div>

        <div className="cart">
          <div className="hd">
            <h2>New order{cartCount > 0 && <span className="cartcount">{cartCount}</span>}</h2>
            {confirmClear ? (
              <span className="clearask" role="group" aria-label="Clear this order?">
                <button type="button" className="btn ghost sm" onClick={() => setConfirmClear(false)}>Keep</button>
                <button type="button" className="btn brand sm" onClick={clearOrder}>Yes, clear</button>
              </span>
            ) : (
              <button type="button" className="btn ghost sm" disabled={cart.length === 0 && !who && disc === "NONE"} onClick={() => (cart.length > 0 ? setConfirmClear(true) : clearOrder())}>Clear</button>
            )}
          </div>
          <div className="seg bigseg" role="group" aria-label="Order type">
            {TYPES.map(([k, label]) => (
              <button key={k} type="button" className="chip" aria-pressed={otype === k} onClick={() => setOtype(k)}>{label}</button>
            ))}
          </div>
          {nameOpen || who
            ? <input className="custname" aria-label="Customer name or order label (optional)" value={who} maxLength={60} autoFocus={!who} onChange={(e) => setWho(e.target.value)} placeholder="Customer name or table" />
            : <button type="button" className="linkbtn" onClick={() => setNameOpen(true)}>+ Add name or table</button>}
          <div className="cartlines">
            {cart.length === 0 && <div className="empty">Nothing on this order yet.<br />Tap a drink or snack to add it.</div>}
            {cart.map((l) => (
              <div className="line" key={l.key}>
                <div><b>{l.name}</b></div>
                <div><b>{peso(l.unit * l.qty)}</b></div>
                <small>{l.opt}</small>
                <div className="qty">
                  <button aria-label={`Less ${l.name}`} onClick={() => edit((c) => changeQty(c, l.key, -1))}>&minus;</button>
                  {l.qty}
                  <button aria-label={`More ${l.name}`} onClick={() => edit((c) => changeQty(c, l.key, 1))}>+</button>
                </div>
              </div>
            ))}
          </div>
          <div className="cartfoot">
          {(menu?.discounts || []).length > 0 && (
            <button type="button" className={`discbtn${rule ? " on" : ""}`} onClick={() => setDiscOpen(true)}>
              <span>{rule ? `Discount: ${rule.name}` : "Discount"}</span>
              <span aria-hidden="true">{rule ? (needsId && !idNo.trim() ? "ID needed" : "Change") : "Add"}</span>
            </button>
          )}
          {t.discount > 0 && <div className="tot"><span>Discount</span><span>&minus;{peso(t.discount)}</span></div>}
          {vatRegistered && <div className="tot vatrow"><span>incl. VAT 12%</span><span>{peso(t.vat)}</span></div>}
          <div className="tot big"><span>Total</span><span>{peso(t.pay)}</span></div>
          <button className="btn brand chargebtn" disabled={cart.length === 0 || (needsId && !idNo.trim())} onClick={openPay}>Charge {peso(t.pay)}</button>
          </div>
        </div>
      </div>

      {discOpen && (
        <Modal title="Discount" onClose={() => setDiscOpen(false)} onSubmit={(e) => { e.preventDefault(); if (!needsId || idNo.trim()) setDiscOpen(false); }}>
          <div className="discpick" role="group" aria-label="Discount">
            {[["NONE", "No discount"], ...(menu?.discounts || []).map((d) => [d.code, d.name])].map(([code, name]) => (
              <button key={code} type="button" className="chip" aria-pressed={disc === code} onClick={() => { setDisc(code); setOrderId(null); if (code === "NONE") setIdNo(""); }}>{name}</button>
            ))}
          </div>
          {needsId && (
            <label className="discrow">
              ID no.
              <input aria-label="Senior or PWD ID number" value={idNo} maxLength={40} autoFocus onChange={(e) => { setIdNo(e.target.value); setOrderId(null); }} placeholder="ID number" />
            </label>
          )}
          {rule && t.discount > 0 && <p className="hint">Takes off {peso(t.discount)}.{needsPin ? " Over the limit: a manager PIN is asked at payment." : ""}</p>}
          <div className="opts">
            <button type="submit" className="btn warm" style={{ flex: 1 }} disabled={needsId && !idNo.trim()}>Done</button>
          </div>
        </Modal>
      )}

      {drawer && (
        <Modal title="Cash drawer" wide onClose={() => setDrawer(false)}>
          {shiftInfo?.shift ? <ShiftPanel s={shiftInfo.shift} reload={reloadShift} onClosed={() => setDrawer(false)} /> : <p className="hint">No shift is open.</p>}
          {can("pos_view") && !can("cashier_reports_view") && <MyReport />}
          <div className="opts"><button type="button" className="btn ghost" onClick={() => setDrawer(false)}>Back to the POS</button></div>
        </Modal>
      )}

      {clocking && (
        <Modal title="Time In / Out" wide onClose={() => setClocking(false)}>
          <ClockPage embedded onDone={() => setClocking(false)} />
          <div className="opts"><button type="button" className="btn ghost" onClick={() => setClocking(false)}>Back to the POS</button></div>
        </Modal>
      )}

      {modal && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setModal(null)}>
          <div className="modal" role="dialog" aria-modal="true">
            {modal.kind === "item" && (
              <ItemModal item={modal.item} onCancel={() => setModal(null)}
                onAdd={(size, addons) => { edit((c) => addToCart(c, modal.item, size, addons)); setModal(null); }} />
            )}
            {modal.kind === "pay" && (
              <PayModal cart={cart} otype={otype} who={who.trim()} disc={disc} rule={rule} vatRegistered={vatRegistered} idNo={needsId ? idNo.trim() : ""} needsPin={needsPin} orderId={orderId} total={t.pay}
                onBack={() => setModal(null)} onPaid={paid} />
            )}
            {modal.kind === "receipt" && <Receipt {...modal.data} onDone={() => setModal(null)} />}
          </div>
        </div>
      )}
    </section>
  );
}
