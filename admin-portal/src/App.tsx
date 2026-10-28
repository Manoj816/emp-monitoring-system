import { useEffect, useState } from "react";
import { api } from "./api";
import { authConfigured, currentUserEmail, signOut } from "./auth";
import LoginPage from "./pages/LoginPage";
import EmployeesPage from "./pages/EmployeesPage";
import DevicesPage from "./pages/DevicesPage";
import ChecksPage from "./pages/ChecksPage";

type Tab = "employees" | "devices" | "checks";

export default function App() {
  const [tab, setTab] = useState<Tab>("employees");
  // undefined = still checking the session; null = signed out
  const [user, setUser] = useState<string | null | undefined>(undefined);
  const [resetCount, setResetCount] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    currentUserEmail().then(setUser);
  }, []);

  if (!authConfigured) {
    return (
      <main className="page">
        <p>Admin sign-in is not configured: set VITE_COGNITO_USER_POOL_ID and VITE_COGNITO_CLIENT_ID.</p>
      </main>
    );
  }
  if (user === undefined) {
    return null;
  }
  if (user === null) {
    return <LoginPage onSignedIn={() => currentUserEmail().then(setUser)} />;
  }

  async function resetDemo() {
    const ok = window.confirm(
      "Wipe ALL employees, devices, enrollment tokens, reference images and checks? This cannot be undone.",
    );
    if (!ok) return;
    try {
      const s = await api.resetDemoData();
      setNotice(`Reset done: removed ${s.employees} employees, ${s.devices} devices, ${s.checks} checks.`);
      setTab("employees");
      setResetCount((n) => n + 1); // remount the pages so they refetch
    } catch (e) {
      setNotice(`Reset failed: ${(e as Error).message}`);
    }
  }

  async function handleSignOut() {
    await signOut();
    setUser(null);
  }

  return (
    <>
      <header className="app">
        <h1>EmpVerify Admin</h1>
        <div className="spacer" />
        <span style={{ marginRight: 12 }}>{user}</span>
        <button onClick={resetDemo} style={{ marginRight: 8 }}>
          Reset demo data
        </button>
        <button onClick={handleSignOut}>Sign out</button>
      </header>

      <nav className="tabs">
        <button className={tab === "employees" ? "active" : ""} onClick={() => setTab("employees")}>
          Employees
        </button>
        <button className={tab === "devices" ? "active" : ""} onClick={() => setTab("devices")}>
          Devices
        </button>
        <button className={tab === "checks" ? "active" : ""} onClick={() => setTab("checks")}>
          Verification checks
        </button>
      </nav>

      {notice && (
        <p className="page" style={{ margin: "8px 0 0" }}>
          {notice}
        </p>
      )}

      <main className="page" key={resetCount}>
        {tab === "employees" && <EmployeesPage />}
        {tab === "devices" && <DevicesPage />}
        {tab === "checks" && <ChecksPage />}
      </main>
    </>
  );
}
