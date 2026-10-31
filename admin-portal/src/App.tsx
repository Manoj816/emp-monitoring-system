import { useEffect, useState, type ReactNode } from "react";
import { api } from "./api";
import { authConfigured, currentUserEmail, signOut } from "./auth";
import LoginPage from "./pages/LoginPage";
import EmployeesPage from "./pages/EmployeesPage";
import DevicesPage from "./pages/DevicesPage";
import ChecksPage from "./pages/ChecksPage";

type Section = "employees" | "devices" | "checks";

const SECTIONS: { id: Section; label: string; hint: string; description: string; icon: ReactNode }[] = [
  {
    id: "employees",
    label: "Employees",
    hint: "People and their photos",
    description: "Everyone enrolled for verification, their ID/photo and whether their first live scan has been matched.",
    icon: (
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="8" r="4" />
        <path d="M4 20c0-3.3 3.6-5 8-5s8 1.7 8 5" />
      </svg>
    ),
  },
  {
    id: "devices",
    label: "Devices",
    hint: "Registered systems",
    description:
      "Systems that registered themselves on install. A system shows as Unassigned until you link it to an employee; " +
      "that activates verification on it, and the first check after any assignment is the enrollment scan, judged " +
      "against the uploaded ID/photo (an earlier live scan is retired). A reinstalled " +
      "system comes back as Unassigned (same row, history kept) and must be assigned again. Reassign hands a system to a " +
      "different employee, and offboarding an employee deregisters their systems back to Unassigned. Revoking immediately " +
      "blocks a device from verifying and from re-authenticating (lost or retired machine). Delete removes a row for good " +
      "(stale duplicate, retired machine); its check history stays with the employee.",
    icon: (
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="5" width="18" height="12" rx="2" />
        <path d="M8 21h8M12 17v4" />
      </svg>
    ),
  },
  {
    id: "checks",
    label: "Verification checks",
    hint: "Results and reviews",
    description:
      "Every scan with its live-person confidence, face match, review state and retained evidence. Missed covers " +
      "both a scan that was opened but not done and a work day on which an employee's system opened no check at " +
      "all (machine off); the latter is recorded by the server once the work window has closed. A missed scan says " +
      "why the workstation was at the time — locked, asleep, shutting down, or open with no response.",
    icon: (
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
        <path d="M9 12l2 2 4-4" />
      </svg>
    ),
  },
];

export default function App() {
  const [section, setSection] = useState<Section>("employees");
  // undefined = still checking the session; null = signed out
  const [user, setUser] = useState<string | null | undefined>(undefined);
  const [resetCount, setResetCount] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [navOpen, setNavOpen] = useState(false);

  useEffect(() => {
    currentUserEmail().then(setUser);
  }, []);

  if (!authConfigured) {
    return (
      <main className="card" style={{ margin: 24 }}>
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
      setSection("employees");
      setResetCount((n) => n + 1); // remount the pages so they refetch
    } catch (e) {
      setNotice(`Reset failed: ${(e as Error).message}`);
    }
  }

  async function handleSignOut() {
    await signOut();
    setUser(null);
  }

  const current = SECTIONS.find((s) => s.id === section)!;
  const initial = user.trim().charAt(0).toUpperCase() || "A";

  return (
    <div className={`app ${navOpen ? "nav-open" : ""}`}>
      <header className="appbar">
        <button className="action nav-toggle" onClick={() => setNavOpen(!navOpen)} aria-label="Toggle navigation">
          ☰
        </button>
        <div className="brand">
          <span className="brand-mark">EV</span>
          <span>
            <span className="brand-name">EmpVerify</span>
            <span className="brand-sub">Admin console</span>
          </span>
        </div>
        <div className="spacer" />
        <span className="env-pill" title="Test environment: demo data can be wiped">Test environment</span>
        <button className="action danger" onClick={resetDemo}>Reset demo data</button>
        <span className="appbar-divider" />
        <div className="user-chip" title={user}>
          <span className="avatar">{initial}</span>
          <span className="user-email">{user}</span>
        </div>
        <button className="action" onClick={handleSignOut}>Sign out</button>
      </header>

      <div className="body">
        <aside className="sidenav">
          <div className="sidenav-title">Navigation</div>
          <nav className="sidenav-items" aria-label="Sections">
            {SECTIONS.map((s) => (
              <button
                key={s.id}
                className={`sidenav-item ${section === s.id ? "active" : ""}`}
                onClick={() => {
                  setSection(s.id);
                  setNavOpen(false);
                }}
                aria-current={section === s.id ? "page" : undefined}
              >
                <span className="sidenav-icon">{s.icon}</span>
                <span>
                  <span className="sidenav-label">{s.label}</span>
                  <span className="sidenav-hint">{s.hint}</span>
                </span>
              </button>
            ))}
          </nav>
          <div className="sidenav-footer">
            <span className="muted">EmpVerify · remote workforce identity verification</span>
          </div>
        </aside>

        <div className="content">
          {notice && (
            <p className="notice" style={{ margin: "0 0 16px" }}>
              {notice}
            </p>
          )}

          <main className="card" key={resetCount} aria-label={current.label}>
            {section === "employees" && <EmployeesPage title={current.label} hint={current.description} />}
            {section === "devices" && <DevicesPage title={current.label} hint={current.description} />}
            {section === "checks" && <ChecksPage title={current.label} hint={current.description} />}
          </main>
        </div>
      </div>
    </div>
  );
}
