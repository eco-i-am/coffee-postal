import { useEffect, useState } from "react";
import { Navigate, NavLink, Outlet, useLocation } from "react-router-dom";
import { api } from "../shared/api.js";
import { inKiosk } from "../shared/kiosk.js";
import ChangePassword from "./ChangePassword.jsx";
import Tutorial from "./Tutorial.jsx";
import { markTourSeen, tourSeen } from "./tutorialSteps.js";
import { useSync } from "./SyncContext.jsx";
import { useAuth } from "./AuthContext.jsx";

// the counter (cashier) only gives feedback at the till; reading it is for the people who run the branch
const counterOnly = (can) => can("pos_view") && !can("branch_reports_view");

// The side menu folds the less-used pages into groups so it stays short. Pages not listed stay at the top level.
const GROUPS = {
  "Menu & stock": ["Products", "Discounts", "Items", "Transfers", "Suppliers", "Item Inventory", "Purchase Orders", "Outsource"],
  "Finance": ["Receivables", "Expenses", "Payables", "Cash Drawer"],
  "People": ["Employees", "Users", "Roles", "Customers", "Training"],
  "Messages": ["Announcements", "Support Chat", "Feedback"],
  "System": ["Settings", "Logs"],
};
const groupOf = (label) => Object.keys(GROUPS).find((g) => GROUPS[g].includes(label));

function navFor(me, can) {
  if (me.scope === "ADMIN") {
    return [
      { to: "/admin", label: "Dashboard", end: true },
      can("branches_view") && { to: "/admin/branches", label: "Branches" },
      can("products_view") && { to: "/admin/products", label: "Products" },
      can("discounts_view") && { to: "/admin/discounts", label: "Discounts" },
      can("branch_po_admin_view") && { to: "/admin/branch-po", label: "Purchase Orders" },
      can("receivables_view") && { to: "/admin/receivables", label: "Receivables" },
      can("items_master_view") && { to: "/admin/items", label: "Items" },
      can("reports_admin_view") && { to: "/admin/reports", label: "Reports" },
      can("admin_employees_view") && { to: "/admin/employees", label: "Employees" },
      can("admin_transfers_view") && { to: "/admin/transfers", label: "Transfers" },
      can("accounting_view") && { to: "/admin/expenses", label: "Expenses" },
      (can("suppliers_view") || can("supplier_po_view")) && { to: "/admin/suppliers", label: "Suppliers" },
      can("users_admin_view") && { to: "/admin/users", label: "Users" },
      can("roles_view") && { to: "/admin/roles", label: "Roles" },
      can("announcements_view") && { to: "/admin/announcements", label: "Announcements" },
      can("support_view") && { to: "/admin/support", label: "Support Chat" },
      can("feedback_admin_view") && { to: "/admin/feedback", label: "Feedback" },
      can("training_admin_view") && { to: "/admin/training", label: "Training" },
      can("settings_admin_view") && { to: "/admin/settings", label: "Settings" },
      can("logs_view") && { to: "/admin/logs", label: "Logs" },
    ].filter(Boolean);
  }
  return [
    can("dashboard_view") && { to: "/branch", label: "Dashboard", end: true },
    can("pos_view") && { to: "/pos", label: "Point of Sale" },
    can("orders_view") && { to: "/branch/orders", label: "Order List" },
    can("branch_inventory_view") && { to: "/branch/inventory", label: "Item Inventory" },
    (can("branch_po_view") || can("branch_po_receive_view")) && { to: "/branch/purchase-orders", label: "Purchase Orders" },
    can("payables_view") && { to: "/branch/payables", label: "Payables" },
    can("cash_inout_view") && can("cashier_reports_view") && { to: "/branch/cash", label: "Cash Drawer" },   // a cashier's drawer is a dialog on the Point of Sale
    can("transfers_view") && { to: "/branch/transfers", label: "Transfers" },
    can("outsource_view") && { to: "/branch/outsource", label: "Outsource" },
    can("branch_expenses_view") && { to: "/branch/expenses", label: "Expenses" },
    can("time_clock_view") && { to: "/branch/clock", label: "Time In / Out" },
    can("branch_employees_view") && { to: "/branch/employees", label: "Employees" },
    can("branch_reports_view") && { to: "/branch/reports", label: "Reports" },
    can("branch_announcements_view") && { to: "/branch/announcements", label: "Announcements" },
    can("branch_support_view") && { to: "/branch/support", label: "Support Chat" },
    can("feedback_view") && !counterOnly(can) && { to: "/branch/feedback", label: "Feedback" },
    can("branch_customers_view") && { to: "/branch/customers", label: "Customers" },
    can("branch_training_view") && { to: "/branch/training", label: "Training" },
    can("branch_settings_view") && { to: "/branch/settings", label: "Settings" },
    can("branch_users_view") && { to: "/branch/users", label: "Users" },
  ].filter(Boolean);
}

// Practice mode for purchase orders: switched on per sign-in. A full page load after each switch makes every screen read the right data.
const canPracticeOrders = (can) => can("branch_po_view") || can("branch_po_receive_view") || can("branch_po_admin_view");
async function setPractice(on, to) {
  try { await api(on ? "/api/practice/start" : "/api/practice/stop", { method: "POST", body: {} }); } catch { /* the page load below shows the truth */ }
  window.location.assign(to);
}

function PracticeBanner({ me }) {
  const hq = me.scope === "ADMIN";
  const home = hq ? "/admin/branch-po" : "/branch/purchase-orders";
  const [busy, setBusy] = useState(false);
  async function reset() {
    setBusy(true);
    try { await api("/api/practice/reset", { method: "POST", body: {} }); } catch { /* shown on reload */ }
    window.location.assign(home);
  }
  return (
    <div className="practicebar practicebanner" role="status">
      <b>PRACTICE MODE</b> Purchase orders, invoices and stock here are pretend. Nothing reaches the real lists, reports or stock, and sales, expenses and other changes are switched off.
      <details>
        <summary>What to try</summary>
        {hq ? (
          <ol><li>Open Purchase Orders. Orders made in practice by branch staff show here.</li><li>Approve one, then move it along each stage.</li><li>Open Receivables to see the invoice once the branch counts the delivery.</li></ol>
        ) : (
          <ol><li>Open Purchase Orders and tap New order. Pick items and a delivery day.</li><li>Open the order and tap Practice: let HQ do the next step, again and again, until it is on its way.</li><li>Tap Confirm what arrived. Stock goes up and an invoice is made.</li><li>Open Payables to see what you would owe HQ.</li></ol>
        )}
      </details>
      <span className="practiceacts">
        <button type="button" className="btn ghost sm" disabled={busy} onClick={reset}>Empty practice orders</button>
        <button type="button" className="btn warm sm" disabled={busy} onClick={() => setPractice(false, home)}>Leave practice</button>
      </span>
    </div>
  );
}

export default function Shell() {
  const { me, can, signOut } = useAuth();
  const [changing, setChanging] = useState(false);
  const [acct, setAcct] = useState(false);   // the account menu in the top bar
  // a counter account (cashier) gets the menu as a slim bar across the top, so the till has the whole screen; other roles can switch
  const counter = me.scope !== "ADMIN" && can("pos_view") && !can("branch_reports_view");
  const menuKey = `cp.menu.v1:${me.username}`;
  const [menuAt, setMenuAt] = useState(() => { try { return localStorage.getItem(menuKey); } catch { return null; } });
  const top = counter || menuAt === "top";   // cashiers always get the top bar; everyone else can choose
  const moveMenu = () => { const next = top ? "side" : "top"; setMenuAt(next); try { localStorage.setItem(menuKey, next); } catch { /* ignore */ } };
  const where = useLocation();
  const kiosk = inKiosk(me, can);
  const [touring, setTouring] = useState(() => !kiosk && !tourSeen(me));
  const sync = useSync();
  // unread counts shown beside menu items: announcements not opened, support messages not read
  const [badges, setBadges] = useState({});
  const groupKey = `cp.menugroups.v1:${me.username}`;
  const [openGroups, setOpenGroups] = useState(() => { try { return JSON.parse(localStorage.getItem(groupKey) || "{}"); } catch { return {}; } });
  const toggleGroup = (g) => setOpenGroups((o) => { const n = { ...o, [g]: !o[g] }; try { localStorage.setItem(groupKey, JSON.stringify(n)); } catch { /* ignore */ } return n; });
  const hqUser = me.scope === "ADMIN";
  const wantsAnn = !hqUser && can("branch_announcements_view");
  const wantsSupport = hqUser ? can("support_view") : can("branch_support_view");
  useEffect(() => {
    if (!wantsAnn && !wantsSupport) return undefined;
    let alive = true;
    const set = (k, n) => { if (alive) setBadges((b) => (b[k] === n ? b : { ...b, [k]: n })); };
    const count = () => {
      if (wantsAnn) api("/api/branch/announcements").then((l) => set("/branch/announcements", l.filter((a) => !a.read).length)).catch(() => {});
      if (wantsSupport) api(`${hqUser ? "/api/admin" : "/api/branch"}/support/unread`).then((r) => set(hqUser ? "/admin/support" : "/branch/support", r.count)).catch(() => {});
    };
    count();
    const timer = setInterval(count, 60000);
    window.addEventListener("cp-announcements", count);
    return () => { alive = false; clearInterval(timer); window.removeEventListener("cp-announcements", count); };
  }, [wantsAnn, wantsSupport, hqUser, where.pathname]);
  const [, bump] = useState(0);
  useEffect(() => {   // clock-only mode switched on or off from the clock page
    const f = () => bump((n) => n + 1);
    window.addEventListener("cp-kiosk", f);
    return () => window.removeEventListener("cp-kiosk", f);
  }, []);
  function closeTour() { markTourSeen(me); setTouring(false); }
  // clock-only mode: nothing but the time clock, full width, no menu
  if (kiosk && where.pathname !== "/branch/clock") return <Navigate to="/branch/clock" replace />;
  if (kiosk) return <main className="kiosk"><Outlet /></main>;

  const brand = (
  <div className="brand">
    <img className="brandlogo" src="/logo.png" alt="Coffee Postal" width="168" height="51" />
    <small>Operating System</small>
  </div>
  );
  const items = navFor(me, can);
  const link = (n) => n.soon ? (
    <span key={n.label} className="soon"><span>{n.label}</span><small>soon</small></span>
  ) : (
    <NavLink key={n.to} to={n.to} end={n.end}>{n.label}{badges[n.to] > 0 && <span className="navbadge" aria-label={`${badges[n.to]} unread`}>{badges[n.to]}</span>}</NavLink>
  );
  // the side menu: loose pages first, then each group as a fold. The group holding the open page stays open.
  const sideLinks = (
    <>
      {items.filter((n) => !groupOf(n.label)).map(link)}
      {Object.keys(GROUPS).map((g) => {
        const mine = items.filter((n) => groupOf(n.label) === g);
        if (mine.length === 0) return null;
        const here = mine.some((n) => where.pathname === n.to || where.pathname.startsWith(n.to + "/"));
        const open = here || openGroups[g];
        const unread = mine.reduce((s, n) => s + (badges[n.to] || 0), 0);
        return (
          <div key={g} className="navgroup">
            <button type="button" className="navgrouphead" aria-expanded={open} onClick={() => toggleGroup(g)}>
              <span>{g}</span>
              {!open && unread > 0 && <span className="navbadge" aria-label={`${unread} unread`}>{unread}</span>}
              <span aria-hidden="true">{open ? "\u25B4" : "\u25BE"}</span>
            </button>
            {open && mine.map(link)}
          </div>
        );
      })}
    </>
  );
  const links = (
    <>
      {navFor(me, can).map((n) =>
            n.soon ? (
              <span key={n.label} className="soon">
                <span>{n.label}</span>
                <small>soon</small>
              </span>
            ) : (
              <NavLink key={n.to} to={n.to} end={n.end}>{n.label}{badges[n.to] > 0 && <span className="navbadge" aria-label={`${badges[n.to]} unread`}>{badges[n.to]}</span>}</NavLink>
            )
          )}
    </>
  );
  const closeAcct = () => setAcct(false);
  const topbar = (
    <header className="topbar">
      {brand}
      <nav className="tnav" aria-label="Main">{links}</nav>
      {(!sync.online || sync.pending > 0) && (
        <p className="syncchip" role="status">{!sync.online ? "Offline" : "Online"}{sync.pending > 0 ? ` · ${sync.pending} to upload` : ""}</p>
      )}
      <div className="acct">
        <button type="button" className="acctbtn" aria-haspopup="menu" aria-expanded={acct} onClick={() => setAcct(!acct)}>
          <span className="av" aria-hidden="true">{(me.fullName || me.username).slice(0, 1).toUpperCase()}</span>
          <span className="acctname"><b>{me.fullName || me.username}</b><small>{me.role}</small></span>
          <span aria-hidden="true">&#9662;</span>
        </button>
        {acct && (
          <>
            <div className="acctscrim" onClick={closeAcct} />
            <div className="acctmenu" role="menu">
              <p className="acctwho">{me.branchName || "Coffee Postal"}</p>
              {!counter && <button type="button" role="menuitem" onClick={() => { closeAcct(); moveMenu(); }}>Menu on the side</button>}
              {canPracticeOrders(can) && !me.practice && <button type="button" role="menuitem" onClick={() => setPractice(true, me.scope === "ADMIN" ? "/admin/branch-po" : "/branch/purchase-orders")}>Practice orders</button>}
              <button type="button" role="menuitem" onClick={() => { closeAcct(); setTouring(true); }}>Quick tour</button>
              <button type="button" role="menuitem" onClick={() => { closeAcct(); setChanging(true); }}>Change password</button>
              <button type="button" role="menuitem" className="danger" onClick={signOut}>Sign out</button>
            </div>
          </>
        )}
      </div>
    </header>
  );

  return (
    <div className={`app${top ? " menutop" : ""}`}>
      {top && topbar}
      {!top && <aside className="side">
        {brand}
        <nav className="nav" aria-label="Main">
          {sideLinks}
        </nav>
        <div className="demo">
          {(!sync.online || sync.pending > 0) && (
            <p className="syncchip" role="status">{!sync.online ? "Offline" : "Online"}{sync.pending > 0 ? ` · ${sync.pending} to upload` : ""}</p>
          )}
          <div className="who">
            <b>{me.fullName || me.username}</b>
            <br />
            <small>{me.role}{me.branchName ? ` · ${me.branchName}` : ""}</small>
          </div>
          <div className="sidelinks">
            <button className="out" type="button" onClick={moveMenu}>{top ? "Menu on the side" : "Menu on top"}</button>
            {canPracticeOrders(can) && !me.practice && <button className="out" type="button" onClick={() => setPractice(true, me.scope === "ADMIN" ? "/admin/branch-po" : "/branch/purchase-orders")}>Practice orders</button>}
            <button className="out" type="button" onClick={() => setTouring(true)}>Quick tour</button>
            <button className="out" type="button" onClick={() => setChanging(true)}>Change password</button>
            <button className="out" type="button" onClick={signOut}>Sign out</button>
          </div>
        </div>
      </aside>}
      <main>
        {me.practice && <PracticeBanner me={me} />}
        <div className="page"><Outlet /></div>
      </main>
      {touring && <Tutorial me={me} can={can} onClose={closeTour} onChangePassword={() => setChanging(true)} />}
      {changing && <ChangePassword onClose={() => setChanging(false)} />}
    </div>
  );
}
