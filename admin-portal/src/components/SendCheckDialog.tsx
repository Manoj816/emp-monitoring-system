import { useEffect, useMemo, useState } from "react";
import { api, type Employee } from "../api";

/**
 * "Send a check now": opens a verification scan on an employee's system within a
 * minute, outside the day's random plan. The admin picks an employee — or leaves
 * it to chance: a random active employee across the company, or within one
 * department. The server does the picking, so even the admin cannot know who a
 * random check lands on before it is sent.
 */
export default function SendCheckDialog(props: {
  onClose: () => void;
  /** Sent: the parent shows the message and closes the dialog. */
  onSent: (message: string) => void;
}) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [department, setDepartment] = useState("");
  const [employeeId, setEmployeeId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .listEmployees()
      .then((all) => setEmployees(all.filter((e) => e.status === "ACTIVE")))
      .catch((e) => setError((e as Error).message));
  }, []);

  const departments = useMemo(
    () => [...new Set(employees.map((e) => e.department).filter(Boolean))].sort() as string[],
    [employees],
  );

  const selectable = department ? employees.filter((e) => e.department === department) : employees;

  async function send() {
    setBusy(true);
    setError(null);
    try {
      const r = await api.requestCheck(
        employeeId ? { employeeId } : department ? { department } : {},
      );
      props.onSent(
        `Check sent to ${r.employeeName}${r.department ? ` (${r.department})` : ""} — ` +
          `${r.systems} system(s) will open the scan within a minute of being online.`,
      );
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>Send a check now</h3>
        <p className="muted" style={{ marginTop: 0 }}>
          The employee's system opens the scan within a minute of being online, outside the day's
          random plan. Leave the employee on “Random” to let the server pick one — across the whole
          company, or within a department.
        </p>

        <div className="row" style={{ justifyContent: "flex-start", gap: 12 }}>
          <label>
            Department
            <select
              value={department}
              disabled={busy}
              onChange={(e) => {
                setDepartment(e.target.value);
                setEmployeeId(""); // a new pool: back to random within it
              }}
            >
              <option value="">All departments</option>
              {departments.map((d) => (
                <option key={d} value={d}>{d}</option>
              ))}
            </select>
          </label>
          <label>
            Employee
            <select value={employeeId} disabled={busy} onChange={(e) => setEmployeeId(e.target.value)}>
              <option value="">Random {department ? `in ${department}` : "employee"}</option>
              {selectable.map((e) => (
                <option key={e.id} value={e.id}>{e.fullName} ({e.employeeCode})</option>
              ))}
            </select>
          </label>
        </div>

        {error && <p className="error-text">{error}</p>}

        <div className="row">
          <button className="action" disabled={busy} onClick={props.onClose}>Cancel</button>
          <button className="primary" disabled={busy} onClick={() => void send()}>
            {busy ? "Sending…" : "Send check"}
          </button>
        </div>
      </div>
    </div>
  );
}
