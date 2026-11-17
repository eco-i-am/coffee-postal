import { Link } from "react-router-dom";
import { useAuth } from "../app/AuthContext.jsx";
import BranchDashboard from "./branch/BranchDashboard.jsx";

export default function Dashboard({ kind }) {
  const { can } = useAuth();
  const admin = kind === "admin";
  if (!admin && can("dashboard_view")) return <BranchDashboard />;
  return (
    <section>
      <header className="pghead">
        <div>
          <h1>{admin ? "HQ Dashboard" : "Branch Dashboard"}</h1>
          <p>
            {admin
              ? "Sales, overdue invoices, branch performance and alerts will land here."
              : "Sales, cash on hand and daily performance will land here."}
          </p>
        </div>
      </header>
      <div className="sec">
        {admin ? (
          <>
            <h2>Set up the chain</h2>
            <p>
              Start with your <Link to="/admin/branches">branches</Link>, then build the{" "}
              <Link to="/admin/products">menu</Link> the cashiers will sell.
            </p>
          </>
        ) : (
          <>
            <h2>Ready to sell?</h2>
            <p>
              {can("pos_view") ? (
                <>Open the <Link to="/pos">Point of Sale</Link> to start taking orders.</>
              ) : (
                "Your account cannot use the Point of Sale."
              )}
            </p>
          </>
        )}
      </div>
    </section>
  );
}
