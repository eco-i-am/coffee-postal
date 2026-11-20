import { useEffect, useState } from "react";
import { Navigate, NavLink, Outlet, useLocation } from "react-router-dom";
import { inKiosk } from "../shared/kiosk.js";
import ChangePassword from "./ChangePassword.jsx";
import Tutorial from "./Tutorial.jsx";
import { markTourSeen, tourSeen } from "./tutorialSteps.js";
import { useSync } from "./SyncContext.jsx";
import { useAuth } from "./AuthContext.jsx";

function navFor(me, can) {
  if (me.scope === "ADMIN") {
    return [
      { to: "/admin", label: "Dashboard", end: true },
      can("branches_view") && { to: "/admin/branches", label: "Branches" },
      can("products_view") && { to: "/admin/products", label: "Products" },
      can("discounts_view") && { to: "/admin/discounts", label: "Discounts" },
      can("branch_po_admin_view") && { to: "/admin/branch-po", label: "Branch PO" },
      can("receivables_view") && { to: "/admin/receivables", label: "Receivables" },
      can("items_master_view") && { to: "/admin/items", label: "Items" },
      can("reports_admin_view") && { to: "/admin/reports", label: "Reports" },
      can("admin_employees_view") && { to: "/admin/employees", label: "Employees" },
      can("admin_transfers_view") && { to: "/admin/transfers", label: "Transfers" },
      can("accounting_view") && { to: "/admin/expenses", label: "Expenses" },
      (can("suppliers_view") || can("supplier_po_view")) && { to: "/admin/suppliers", label: "Suppliers" },
      can("users_admin_view") && { to: "/admin/users", label: "Users" },
      can("roles_view") && { to: "/admin/roles", label: "Roles" },
      can("logs_view") && { to: "/admin/logs", label: "Logs" },
    ].filter(Boolean);
  }
  return [
    can("dashboard_view") && { to: "/branch", label: "Dashboard", end: true },
    can("pos_view") && { to: "/pos", label: "Point of Sale" },
    can("orders_view") && { to: "/branch/orders", label: "Order List" },
    can("branch_inventory_view") && { to: "/branch/inventory", label: "Item Inventory" },
    can("branch_po_view") && { to: "/branch/purchase-orders", label: "Purchase Orders" },
    can("payables_view") && { to: "/branch/payables", label: "Payables" },
    can("cash_inout_view") && { to: "/branch/cash", label: "Cash Drawer" },
    can("transfers_view") && { to: "/branch/transfers", label: "Transfers" },
    can("outsource_view") && { to: "/branch/outsource", label: "Outsource" },
    can("branch_expenses_view") && { to: "/branch/expenses", label: "Expenses" },
    can("time_clock_view") && { to: "/branch/clock", label: "Time In / Out" },
    can("branch_employees_view") && { to: "/branch/employees", label: "Employees" },
    can("branch_reports_view") && { to: "/branch/reports", label: "Reports" },
    can("branch_users_view") && { to: "/branch/users", label: "Users" },
  ].filter(Boolean);
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
    <svg width="42" height="46" viewBox="0 0 40 44" aria-hidden="true">
      <path d="M20 3C29 3 35 10 35 18C35 28 20 41 20 41C20 41 5 28 5 18C5 10 11 3 20 3Z" fill="#FE8282" />
      <ellipse cx="20" cy="15" rx="8" ry="6" fill="#FFC74A" transform="rotate(-25 20 15)" />
      <path d="M14 17c4-1 8-5 12-6" stroke="#FE8282" strokeWidth="1.6" fill="none" />
    </svg>
    <div>
      <b>Coffee<br />Postal</b>
      <small>Operating System</small>
    </div>
  </div>
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
              <NavLink key={n.to} to={n.to} end={n.end}>{n.label}</NavLink>
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
          {links}
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
            <button className="out" type="button" onClick={() => setTouring(true)}>Quick tour</button>
            <button className="out" type="button" onClick={() => setChanging(true)}>Change password</button>
            <button className="out" type="button" onClick={signOut}>Sign out</button>
          </div>
        </div>
      </aside>}
      <main>
        <div className="page"><Outlet /></div>
      </main>
      {touring && <Tutorial me={me} can={can} onClose={closeTour} onChangePassword={() => setChanging(true)} />}
      {changing && <ChangePassword onClose={() => setChanging(false)} />}
    </div>
  );
}
