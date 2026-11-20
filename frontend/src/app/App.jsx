import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { inKiosk } from "../shared/kiosk.js";
import { useAuth } from "./AuthContext.jsx";
import Shell from "./Shell.jsx";
import LoginPage from "../features/auth/LoginPage.jsx";
import Dashboard from "../features/Dashboard.jsx";
import BranchesPage from "../features/branches/BranchesPage.jsx";
import ProductsPage from "../features/products/ProductsPage.jsx";
import DiscountsPage from "../features/products/DiscountsPage.jsx";
import PosPage from "../features/pos/PosPage.jsx";
import OrdersPage from "../features/branch/OrdersPage.jsx";
import AdminDashboard from "../features/admin/AdminDashboard.jsx";
import InvoiceList from "../features/invoices/InvoiceList.jsx";
import ReportsPage from "../features/reports/ReportsPage.jsx";
import StaffPage from "../features/users/StaffPage.jsx";
import RolesPage from "../features/users/RolesPage.jsx";
import LogsPage from "../features/logs/LogsPage.jsx";
import CashPage from "../features/branch/CashPage.jsx";
import InventoryPage from "../features/branch/InventoryPage.jsx";
import PurchaseOrdersPage from "../features/branch/PurchaseOrdersPage.jsx";
import ItemsPage from "../features/admin/ItemsPage.jsx";
import BranchPoAdminPage from "../features/admin/BranchPoAdminPage.jsx";
import SuppliersPage from "../features/admin/SuppliersPage.jsx";
import OutsourcePage from "../features/branch/OutsourcePage.jsx";
import ExpensesPage from "../features/expenses/ExpensesPage.jsx";
import AnnouncementsPage from "../features/engagement/AnnouncementsPage.jsx";
import SupportPage from "../features/engagement/SupportPage.jsx";
import FeedbackPage from "../features/engagement/FeedbackPage.jsx";
import CustomersPage from "../features/engagement/CustomersPage.jsx";
import TrainingPage from "../features/engagement/TrainingPage.jsx";
import SettingsPage from "../features/engagement/SettingsPage.jsx";
import TransfersPage from "../features/inventory/TransfersPage.jsx";
import ClockPage from "../features/clock/ClockPage.jsx";
import EmployeesPage from "../features/employees/EmployeesPage.jsx";

function Protected({ scope }) {
  const { me, ready } = useAuth();
  if (!ready) return <div className="boot" role="status">Loading...</div>;
  if (!me) return <Navigate to="/login" replace />;
  if (scope === "ADMIN" && me.scope !== "ADMIN") return <Navigate to="/" replace />;
  return <Outlet />;
}

// a cashier's drawer is a dialog on the Point of Sale; the page is for managers
function CashRoute() {
  const { can } = useAuth();
  return can("cashier_reports_view") ? <CashPage /> : <Navigate to="/pos" replace />;
}

function Home() {
  const { me, can } = useAuth();
  // a tablet account that can only clock people in and out goes straight to the clock
  if (inKiosk(me, can)) return <Navigate to="/branch/clock" replace />;
  if (me.scope === "ADMIN") return <Navigate to="/admin" replace />;
  // a cashier has no dashboard: the till is their home
  if (!can("dashboard_view")) return <Navigate to={can("pos_view") ? "/pos" : can("time_clock_view") ? "/branch/clock" : can("cash_inout_view") ? "/branch/cash" : "/branch"} replace />;
  return <Navigate to="/branch" replace />;
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
          <Route path="/branch/payables" element={<InvoiceList side="branch" />} />
          <Route path="/branch/reports" element={<ReportsPage side="branch" />} />
          <Route path="/branch/users" element={<StaffPage side="branch" />} />
          <Route path="/branch/cash" element={<CashRoute />} />
          <Route path="/branch/clock" element={<ClockPage />} />
          <Route path="/branch/transfers" element={<TransfersPage side="branch" />} />
          <Route path="/branch/outsource" element={<OutsourcePage />} />
          <Route path="/branch/expenses" element={<ExpensesPage side="branch" />} />
          <Route path="/branch/announcements" element={<AnnouncementsPage side="branch" />} />
          <Route path="/branch/support" element={<SupportPage side="branch" />} />
          <Route path="/branch/feedback" element={<FeedbackPage side="branch" />} />
          <Route path="/branch/customers" element={<CustomersPage />} />
          <Route path="/branch/training" element={<TrainingPage side="branch" />} />
          <Route path="/branch/settings" element={<SettingsPage side="branch" />} />
          <Route path="/branch/employees" element={<EmployeesPage side="branch" />} />
          <Route path="/branch/inventory" element={<InventoryPage />} />
          <Route path="/branch/purchase-orders" element={<PurchaseOrdersPage />} />
          <Route element={<Protected scope="ADMIN" />}>
            <Route path="/admin" element={<AdminDashboard />} />
            <Route path="/admin/reports" element={<ReportsPage side="hq" />} />
            <Route path="/admin/users" element={<StaffPage side="hq" />} />
            <Route path="/admin/logs" element={<LogsPage />} />
            <Route path="/admin/roles" element={<RolesPage />} />
            <Route path="/admin/receivables" element={<InvoiceList side="hq" />} />
            <Route path="/admin/branches" element={<BranchesPage />} />
            <Route path="/admin/products" element={<ProductsPage />} />
            <Route path="/admin/discounts" element={<DiscountsPage />} />
            <Route path="/admin/items" element={<ItemsPage />} />
            <Route path="/admin/transfers" element={<TransfersPage side="hq" />} />
            <Route path="/admin/suppliers" element={<SuppliersPage />} />
            <Route path="/admin/expenses" element={<ExpensesPage side="hq" />} />
            <Route path="/admin/announcements" element={<AnnouncementsPage side="hq" />} />
            <Route path="/admin/support" element={<SupportPage side="hq" />} />
            <Route path="/admin/feedback" element={<FeedbackPage side="hq" />} />
            <Route path="/admin/training" element={<TrainingPage side="hq" />} />
            <Route path="/admin/settings" element={<SettingsPage side="hq" />} />
            <Route path="/admin/employees" element={<EmployeesPage side="hq" />} />
            <Route path="/admin/branch-po" element={<BranchPoAdminPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
