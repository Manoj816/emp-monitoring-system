import { useEffect, useMemo, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";

/**
 * "Send a check now": opens a verification scan on employees' systems within a
 * minute, outside the day's random plan. Built like the assign popup, for fleets
 * where a dropdown would be unusable: a searchable, department-filterable list
 * with checkboxes, "Select all" for the whole filtered set (one department, or
 * every employee), or — with nobody picked — one random employee, chosen
 * server-side so even the admin cannot know who a random check lands on before
 * it is sent. Employees who cannot receive a check (suspended, no active
 * system) are shown with the reason rather than hidden, so the list never looks
 * like it lost someone.
 */
export default function SendCheckDialog(props: {
  onClose: () => void;
  /** Sent: the parent shows the message and closes the dialog. */
  onSent: (message: string) => void;
}) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [search, setSearch] = useState("");
  const [department, setDepartment] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([api.listEmployees(), api.listDevices()])
      .then(([emps, devs]) => {
        setEmployees(emps);
        setDevices(devs);
        setLoaded(true);
      })
      .catch((e) => {
        setError((e as Error).message);
        setLoaded(true);
      });
  }, []);

  /** Why an employee cannot receive a check right now; null when they can. */
  function blocked(e: Employee): string | null {
    if (e.status !== "ACTIVE") return e.status.toLowerCase();
    if (!devices.some((d) => d.employeeId === e.id && d.status === "ACTIVE")) return "no system";
    return null;
  }

  const departments = useMemo(
    () => [...new Set(employees.map((e) => e.department).filter(Boolean))].sort() as string[],
    [employees],
  );

  const q = search.trim().toLowerCase();
  const visible = employees
    .filter((e) => !department || e.department === department)
    .filter(
      (e) =>
        !q ||
        e.fullName.toLowerCase().includes(q) ||
        e.employeeCode.toLowerCase().includes(q) ||
        e.email.toLowerCase().includes(q),
    )
    .sort((a, b) => {
      const ab = blocked(a) === null ? 0 : 1;
      const bb = blocked(b) === null ? 0 : 1;
      return ab !== bb ? ab - bb : a.fullName.localeCompare(b.fullName);
    });

  /** The shown employees a check can actually be sent to — what "Select all" selects. */
  const selectable = visible.filter((e) => blocked(e) === null);
  const allShownSelected = selectable.length > 0 && selectable.every((e) => selected.has(e.id));

  function toggle(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAll() {
    setSelected((prev) => {
      const next = new Set(prev);
      if (allShownSelected) selectable.forEach((e) => next.delete(e.id));
      else selectable.forEach((e) => next.add(e.id));
      return next;
    });
  }

  async function send() {
    setBusy(true);
    setError(null);
    try {
      const r = await api.requestCheck(
        selected.size > 0 ? { employeeIds: [...selected] } : department ? { department } : {},
      );
      const names =
        r.sent.length <= 3
          ? r.sent.map((t) => t.employeeName).join(", ")
          : `${r.sent.length} employees`;
      let message =
        r.sent.length === 1
          ? `Check sent to ${r.sent[0].employeeName}${r.sent[0].department ? ` (${r.sent[0].department})` : ""} — ` +
            "their system opens the scan within a minute of being online."
          : `Checks sent to ${names} — each system opens the scan within a minute of being online.`;
      if (r.skipped.length > 0) {
        message +=
          r.skipped.length <= 3
            ? ` Skipped: ${r.skipped.map((s) => `${s.employeeName} (${s.reason})`).join(", ")}.`
            : ` Skipped ${r.skipped.length} who have no active system.`;
      }
      props.onSent(message);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  const sendLabel = busy
    ? "Sending…"
    : selected.size > 0
      ? `Send check to ${selected.size} employee${selected.size === 1 ? "" : "s"}`
      : department
        ? `Send to a random employee in ${department}`
        : "Send to a random employee";

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <button type="button" className="modal-close" aria-label="Close" title="Close" disabled={busy} onClick={props.onClose}>×</button>
        <h3 style={{ marginTop: 0 }}>Send a check now</h3>
        <p className="muted" style={{ marginTop: 0 }}>
          Each selected employee's system opens the scan within a minute of being online, outside the
          day's random plan. Use “Select all” for a whole department (or everyone) — or select nobody
          and the server picks one employee at random.
        </p>

        <div className="pick-controls">
          <input
            autoFocus
            placeholder="Search by name, code or email…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <select
            value={department}
            onChange={(e) => setDepartment(e.target.value)}
            aria-label="Filter by department"
          >
            <option value="">All departments</option>
            {departments.map((d) => (
              <option key={d} value={d}>{d}</option>
            ))}
          </select>
        </div>

        <div className="pick-toolbar">
          <label>
            <input
              type="checkbox"
              checked={allShownSelected}
              disabled={busy || selectable.length === 0}
              onChange={toggleAll}
            />{" "}
            Select all shown ({selectable.length})
          </label>
          <span className="spacer" />
          <span className="muted">{selected.size} selected</span>
          {selected.size > 0 && (
            <button className="action" disabled={busy} onClick={() => setSelected(new Set())}>
              Clear
            </button>
          )}
        </div>

        <ul className="pick-list" role="listbox" aria-label="Employees" aria-multiselectable="true">
          {!loaded && <li className="pick-empty">Loading…</li>}
          {loaded && visible.length === 0 && (
            <li className="pick-empty">
              {employees.length === 0
                ? "No employees yet — create one on the Employees page first."
                : "No employees match this search or department."}
            </li>
          )}
          {visible.map((e) => {
            const reason = blocked(e);
            const isSelected = selected.has(e.id);
            return (
              <li key={e.id}>
                <button
                  type="button"
                  className={`pick-item ${isSelected ? "selected" : ""}`}
                  role="option"
                  aria-selected={isSelected}
                  disabled={busy || reason !== null}
                  title={reason === "no system" ? "No active system is assigned to them" : undefined}
                  onClick={() => toggle(e.id)}
                >
                  <input type="checkbox" checked={isSelected} readOnly tabIndex={-1} />
                  <span className="avatar">{e.fullName.trim().charAt(0).toUpperCase() || "?"}</span>
                  <span className="pick-main">
                    <span className="pick-name">{e.fullName}</span>
                    <span className="pick-sub">{e.employeeCode} · {e.email}</span>
                  </span>
                  {reason !== null && <span className="badge error">{reason}</span>}
                  <span className="badge neutral">{e.department ?? "No department"}</span>
                </button>
              </li>
            );
          })}
        </ul>

        {error && <p className="error-text">{error}</p>}

        <div className="row">
          <button className="action" disabled={busy} onClick={props.onClose}>Cancel</button>
          <button className="primary" disabled={busy || !loaded} onClick={() => void send()}>
            {sendLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
