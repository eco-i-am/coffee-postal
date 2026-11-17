import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "./AuthContext.jsx";

function navFor(me, can) {
  if (me.scope === "ADMIN") {
    return [
      { to: "/admin", label: "Dashboard", end: true },
      can("branches_view") && { to: "/admin/branches", label: "Branches" },
      can("products_view") && { to: "/admin/products", label: "Products" },
      can("branch_po_admin_view") && { to: "/admin/branch-po", label: "Branch PO" },
      can("receivables_view") && { to: "/admin/receivables", label: "Receivables" },
      can("items_master_view") && { to: "/admin/items", label: "Items" },
      { label: "Reports", soon: true },
    ].filter(Boolean);
  }
  return [
    { to: "/branch", label: "Dashboard", end: true },
    can("pos_view") && { to: "/pos", label: "Point of Sale" },
    can("orders_view") && { to: "/branch/orders", label: "Order List" },
    can("branch_inventory_view") && { to: "/branch/inventory", label: "Item Inventory" },
    can("branch_po_view") && { to: "/branch/purchase-orders", label: "Purchase Orders" },
    can("payables_view") && { to: "/branch/payables", label: "Payables" },
    can("cash_inout_view") && { to: "/branch/cash", label: "Cash Drawer" },
    { label: "Reports", soon: true },
  ].filter(Boolean);
}

export default function Shell() {
  const { me, can, signOut } = useAuth();
  return (
    <div className="app">
      <aside className="side">
        <div className="brand">
          <svg width="42" height="46" viewBox="0 0 40 44" aria-hidden="true">
            <path d="M20 3C29 3 35 10 35 18C35 28 20 41 20 41C20 41 5 28 5 18C5 10 11 3 20 3Z" fill="#FE8282" />
            <ellipse cx="20" cy="15" rx="8" ry="6" fill="#FFC74A" transform="rotate(-25 20 15)" />
            <path d="M14 17c4-1 8-5 12-6" stroke="#FE8282" strokeWidth="1.6" fill="none" />
          </svg>
          <div>
            <b>Coffee<br />Postal</b>
            <small>POS system</small>
          </div>
        </div>
        <nav className="nav" aria-label="Main">
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
        </nav>
        <div className="demo">
          <div className="who">
            <b>{me.fullName || me.username}</b>
            <br />
            <small>{me.role}{me.branchName ? ` · ${me.branchName}` : ""}</small>
          </div>
          <button className="out" type="button" onClick={signOut}>Sign out</button>
        </div>
      </aside>
      <main>
        <div className="page"><Outlet /></div>
      </main>
    </div>
  );
}
