import { useState } from "react";
import { getAdminKey, setAdminKey } from "./api";
import EmployeesPage from "./pages/EmployeesPage";
import ChecksPage from "./pages/ChecksPage";

type Tab = "employees" | "checks";

export default function App() {
  const [tab, setTab] = useState<Tab>("employees");
  const [adminKey, setKeyState] = useState(getAdminKey());

  return (
    <>
      <header className="app">
        <h1>EmpVerify Admin</h1>
        <div className="spacer" />
        <label>
          Admin key
          <input
            type="password"
            value={adminKey}
            onChange={(e) => {
              setKeyState(e.target.value);
              setAdminKey(e.target.value);
            }}
            style={{ width: 160 }}
          />
        </label>
      </header>

      <nav className="tabs">
        <button className={tab === "employees" ? "active" : ""} onClick={() => setTab("employees")}>
          Employees
        </button>
        <button className={tab === "checks" ? "active" : ""} onClick={() => setTab("checks")}>
          Verification checks
        </button>
      </nav>

      <main className="page">{tab === "employees" ? <EmployeesPage /> : <ChecksPage />}</main>
    </>
  );
}
