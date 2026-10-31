import { useMemo, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";

/**
 * Assign / reassign popup for the Devices tab: a searchable, department-
 * filterable list of employees, built for fleets where a dropdown would be
 * unusable. Every employee that matches the search is SHOWN — one who cannot
 * be chosen (already assigned to this system, offboarded, suspended) appears
 * disabled with the reason, so a search never looks broken. Assigning resets
 * the employee's first scan: the next check on this system is the enrollment
 * scan, judged against the uploaded ID/photo.
 */
export default function AssignEmployeeDialog(props: {
  device: DeviceSummary;
  /** All employees; the dialog itself decides who is selectable. */
  employees: Employee[];
  onClose: () => void;
  /** Assignment succeeded: the parent refreshes and closes the dialog. */
  onAssigned: () => void;
}) {
  const { device } = props;
  const reassign = device.employeeId !== null;
  const [search, setSearch] = useState("");
  const [department, setDepartment] = useState("");
  const [selected, setSelected] = useState<Employee | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /** Why an employee cannot be chosen; null when they can. */
  function blocked(e: Employee): string | null {
    if (e.id === device.employeeId) return "assigned to this system";
    if (e.status !== "ACTIVE") return e.status.toLowerCase();
    return null;
  }

  // Filter options come from the employees that exist, so the list never offers
  // a department with nobody in it (legacy employees may have none at all).
  const departments = useMemo(() => {
    const names = new Set<string>();
    let hasNone = false;
    for (const e of props.employees) {
      if (e.department) names.add(e.department);
      else hasNone = true;
    }
    return { sorted: [...names].sort((a, b) => a.localeCompare(b)), hasNone };
  }, [props.employees]);

  const q = search.trim().toLowerCase();
  const visible = props.employees
    .filter((e) => {
      if (department === "(none)" && e.department) return false;
      if (department && department !== "(none)" && e.department !== department) return false;
      if (!q) return true;
      return (
        e.fullName.toLowerCase().includes(q) ||
        e.employeeCode.toLowerCase().includes(q) ||
        e.email.toLowerCase().includes(q)
      );
    })
    .sort((a, b) => {
      const ab = blocked(a) === null ? 0 : 1;
      const bb = blocked(b) === null ? 0 : 1;
      return ab !== bb ? ab - bb : a.fullName.localeCompare(b.fullName);
    });

  async function assign() {
    if (!selected) return;
    if (
      reassign &&
      !confirm(
        `Reassign ${device.hostname ?? device.id} from ${device.employeeName ?? "its employee"} to ` +
          `${selected.fullName}? The agent switches on its next poll, and the first check is the enrollment scan.`,
      )
    ) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.assignDevice(device.id, selected.id);
      props.onAssigned();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>
          {reassign ? "Reassign system" : "Assign system"} — {device.hostname ?? device.id.slice(0, 8)}
        </h3>
        <p className="muted" style={{ marginTop: 0 }}>
          {reassign ? `Currently assigned to ${device.employeeName ?? "an employee"}. ` : ""}
          The chosen employee's first scan is reset: the next check on this system is the enrollment scan,
          matched against their uploaded ID/photo.
        </p>

        <div className="pick-controls">
          <input
            autoFocus
            placeholder="Search by name, code or email…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <select value={department} onChange={(e) => setDepartment(e.target.value)} aria-label="Filter by department">
            <option value="">All departments</option>
            {departments.sorted.map((d) => (
              <option key={d} value={d}>{d}</option>
            ))}
            {departments.hasNone && <option value="(none)">No department</option>}
          </select>
        </div>

        <ul className="pick-list" role="listbox" aria-label="Employees">
          {visible.length === 0 && (
            <li className="pick-empty">
              {props.employees.length === 0
                ? "No employees yet — create one on the Employees page first."
                : "No employees match this search or department."}
            </li>
          )}
          {visible.map((e) => {
            const reason = blocked(e);
            return (
              <li key={e.id}>
                <button
                  type="button"
                  className={`pick-item ${selected?.id === e.id ? "selected" : ""}`}
                  role="option"
                  aria-selected={selected?.id === e.id}
                  disabled={busy || reason !== null}
                  title={reason === "assigned to this system"
                    ? "Already assigned here. To make them scan again, use \"Request first scan again\" on the Employees page."
                    : undefined}
                  onClick={() => setSelected(selected?.id === e.id ? null : e)}
                >
                  <span className="avatar">{e.fullName.trim().charAt(0).toUpperCase() || "?"}</span>
                  <span className="pick-main">
                    <span className="pick-name">{e.fullName}</span>
                    <span className="pick-sub">{e.employeeCode} · {e.email}</span>
                  </span>
                  {reason !== null && (
                    <span className={`badge ${reason === "assigned to this system" ? "check" : "error"}`}>{reason}</span>
                  )}
                  <span className="badge neutral">{e.department ?? "No department"}</span>
                </button>
              </li>
            );
          })}
        </ul>

        {error && <p className="error-text">{error}</p>}

        <div className="row">
          <button className="action" disabled={busy} onClick={props.onClose}>Cancel</button>
          <button className="primary" disabled={busy || !selected} onClick={() => void assign()}>
            {busy ? "Assigning…" : selected ? `Assign ${selected.fullName}` : "Assign employee"}
          </button>
        </div>
      </div>
    </div>
  );
}
