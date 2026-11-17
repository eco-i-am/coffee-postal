import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { useAuth } from "./AuthContext.jsx";
import Shell from "./Shell.jsx";
import LoginPage from "../features/auth/LoginPage.jsx";
import Dashboard from "../features/Dashboard.jsx";
import BranchesPage from "../features/branches/BranchesPage.jsx";
import ProductsPage from "../features/products/ProductsPage.jsx";
import PosPage from "../features/pos/PosPage.jsx";
import OrdersPage from "../features/branch/OrdersPage.jsx";
import InventoryPage from "../features/branch/InventoryPage.jsx";
import PurchaseOrdersPage from "../features/branch/PurchaseOrdersPage.jsx";
import ItemsPage from "../features/admin/ItemsPage.jsx";
import BranchPoAdminPage from "../features/admin/BranchPoAdminPage.jsx";

function Protected({ scope }) {
  const { me, ready } = useAuth();
  if (!ready) return <div className="boot" role="status">Loading...</div>;
  if (!me) return <Navigate to="/login" replace />;
  if (scope === "ADMIN" && me.scope !== "ADMIN") return <Navigate to="/" replace />;
  return <Outlet />;
}

function Home() {
  const { me } = useAuth();
  return <Navigate to={me.scope === "ADMIN" ? "/admin" : "/branch"} replace />;
}

export default function App() {
  const { me, ready } = useAuth();
  return (
    <Routes>
      <Route path="/login" element={ready && me ? <Navigate to="/" replace /> : <LoginPage />} />
      <Route element={<Protected />}>
        <Route element={<Shell />}>
          <Route path="/" element={<Home />} />
          <Route path="/branch" element={<Dashboard kind="branch" />} />
          <Route path="/pos" element={<PosPage />} />
          <Route path="/branch/orders" element={<OrdersPage />} />
          <Route path="/branch/inventory" element={<InventoryPage />} />
          <Route path="/branch/purchase-orders" element={<PurchaseOrdersPage />} />
          <Route element={<Protected scope="ADMIN" />}>
            <Route path="/admin" element={<Dashboard kind="admin" />} />
            <Route path="/admin/branches" element={<BranchesPage />} />
            <Route path="/admin/products" element={<ProductsPage />} />
            <Route path="/admin/items" element={<ItemsPage />} />
            <Route path="/admin/branch-po" element={<BranchPoAdminPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
