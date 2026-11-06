import { useEffect, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";
import CardTitle from "../components/CardTitle";
import DataTable from "../components/DataTable";
import { useTableFilters } from "../components/TableFilters";

/**
 * The Configuration tab: the admin controls for how checks run, in one place.
 * Two controls today — the daily check window (when a day's random check can
 * land) and the daily-check roster (who gets a daily check at all). Someone off
 * the roster is still enrolled and still answers a sent check; they are simply
 * not checked automatically every day, and the server never judges them
 * NO_CHECK for a day on which nothing was ever going to run.
 */
export default function ConfigurationPage(props: { title: string; hint: string }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // The check window form. The server may say "12:00" or "12:00:00"; the time
  // inputs want HH:mm.
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [windowLoaded, setWindowLoaded] = useState(false);
  const [savingWindow, setSavingWindow] = useState(false);

  async function refresh() {
    try {
      const [emps, devs] = await Promise.all([api.listEmployees(), api.listDevices()]);
      setEmployees(emps);
      setDevices(devs);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useEffect(() => {
    void refresh();
    api
      .getCheckWindow()
      .then((w) => {
        setStart(w.workWindowStart.slice(0, 5));
        setEnd(w.workWindowEnd.slice(0, 5));
        setWindowLoaded(true);
      })
      .catch((e) => {
        setError((e as Error).message);
        setWindowLoaded(true);
      });
  }, []);

  async function saveWindow() {
    if (!start || !end) {
      setError("Both times are required.");
      return;
    }
    if (end <= start) {
      setError("The window must end after it starts.");
      return;
    }
    setSavingWindow(true);
    setError(null);
    try {
      const saved = await api.updateCheckWindow(start, end);
      setNotice(
        `Check window set to ${saved.workWindowStart.slice(0, 5)}–${saved.workWindowEnd.slice(0, 5)}. ` +
          "Running agents pick it up within a minute.",
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSavingWindow(false);
    }
  }

  async function toggleDaily(emp: Employee) {
    setBusy(true);
    setError(null);
    try {
      const updated = await api.setDailyChecks(emp.id, !emp.dailyChecks);
      setEmployees((prev) => prev.map((e) => (e.id === updated.id ? updated : e)));
      setNotice(
        updated.dailyChecks
          ? `${updated.fullName} is back on the daily-check roster.`
          : `${updated.fullName} no longer gets the daily check. They are still enrolled, and “Send a check” still works.`,
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  /** The systems currently assigned to an employee (revoked ones excluded). */
  function systemsOf(employee: Employee): string {
    const names = devices
      .filter((d) => d.employeeId === employee.id && d.status !== "REVOKED")
      .map((d) => d.hostname ?? d.id.slice(0, 8));
    return names.length === 0 ? "none" : names.join(", ");
  }

  const active = employees.filter((e) => e.status === "ACTIVE");
  const onRoster = active.filter((e) => e.dailyChecks);

  const tableFilters = useTableFilters();

  return (
    <>
      <div className="toolbar">
        <CardTitle title={props.title} hint={props.hint} />
        <span className="toolbar-right">{tableFilters.control}</span>
      </div>

      {notice && <p className="notice">{notice}</p>}
      {error && <p className="error-text">{error}</p>}

      <section className="config-card">
        <h3>Daily check window</h3>
        <p className="help">
          Each day's check opens at a random time inside these hours (the employee's local time), so
          nobody can plan around it. A day whose check has not run yet is re-planned inside the new
          window; a day already checked stays checked.
        </p>
        {!windowLoaded ? (
          <p className="muted">Loading…</p>
        ) : (
          <div className="row" style={{ justifyContent: "flex-start", gap: 12 }}>
            <label>
              From{" "}
              <input type="time" value={start} disabled={savingWindow} onChange={(e) => setStart(e.target.value)} />
            </label>
            <label>
              To{" "}
              <input type="time" value={end} disabled={savingWindow} onChange={(e) => setEnd(e.target.value)} />
            </label>
            <button className="primary" disabled={savingWindow} onClick={() => void saveWindow()}>
              {savingWindow ? "Saving…" : "Save window"}
            </button>
          </div>
        )}
      </section>

      <section className="config-card">
        <h3>Daily-check roster</h3>
        <p className="help">
          Who gets the day's random check. {onRoster.length} of {active.length} active employees are
          on the roster. Turning someone off stops only the automatic daily check — they stay
          enrolled, “Send a check” still reaches them, and days without a check are not counted
          against them as NO CHECK.
        </p>
        <DataTable
          {...tableFilters.tableProps}
          rows={employees}
          rowKey={(emp) => emp.id}
          emptyText="No employees yet — create one on the Employees page first."
          columns={[
            { key: "code", header: "Domain ID", value: (emp) => emp.employeeCode },
            { key: "name", header: "Name", value: (emp) => emp.fullName },
            {
              key: "department", header: "Department", filter: "select",
              value: (emp) => emp.department ?? "—",
            },
            {
              key: "status", header: "Status", filter: "select",
              value: (emp) => emp.status,
              render: (emp) => (
                <span className={`badge ${emp.status === "ACTIVE" ? "match" : "neutral"}`}>{emp.status}</span>
              ),
            },
            { key: "system", header: "System", value: (emp) => systemsOf(emp) },
            {
              key: "daily", header: "Daily check", filter: "select",
              value: (emp) => (emp.dailyChecks ? "on" : "off"),
              headerStyle: { width: 150 },
              render: (emp) => (
                <button
                  className="action"
                  disabled={busy || emp.status !== "ACTIVE"}
                  title={
                    emp.status !== "ACTIVE"
                      ? "Only active employees are checked"
                      : emp.dailyChecks
                        ? "Click to stop the automatic daily check for them"
                        : "Click to put them back on the daily-check roster"
                  }
                  onClick={() => void toggleDaily(emp)}
                >
                  <span className={`badge ${emp.dailyChecks ? "match" : "neutral"}`}>
                    {emp.dailyChecks ? "on" : "off"}
                  </span>
                </button>
              ),
            },
          ]}
        />
      </section>
    </>
  );
}
