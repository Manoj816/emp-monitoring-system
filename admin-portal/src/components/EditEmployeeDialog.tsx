import { useState } from "react";
import { api, DEPARTMENTS, type Employee } from "../api";

/**
 * "Edit details" popup: corrects an employee's name, code, email or department
 * (typos, name changes, department moves). None of these affect verification,
 * so nothing re-enrolls; the ID/photo has its own action.
 */
export default function EditEmployeeDialog(props: {
  employee: Employee;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { employee } = props;
  const [form, setForm] = useState({
    employeeCode: employee.employeeCode,
    fullName: employee.fullName,
    email: employee.email,
    // A legacy record may carry a department outside today's fixed list (or none):
    // it still shows as the current choice so saving without touching it keeps it.
    department: employee.department ?? "",
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const departments: string[] = [...DEPARTMENTS];
  if (employee.department && !departments.includes(employee.department)) {
    departments.unshift(employee.department);
  }

  const complete =
    form.employeeCode.trim() !== "" && form.fullName.trim() !== "" &&
    form.email.trim() !== "" && form.department !== "";
  const changed =
    form.employeeCode.trim() !== employee.employeeCode ||
    form.fullName.trim() !== employee.fullName ||
    form.email.trim() !== employee.email ||
    form.department !== (employee.department ?? "");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!complete) return;
    setBusy(true);
    setError(null);
    try {
      await api.updateEmployee(employee.id, {
        employeeCode: form.employeeCode.trim(),
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        department: form.department,
      });
      props.onSaved();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>Edit employee — {employee.fullName}</h3>
        <p className="muted" style={{ marginTop: 0 }}>
          Corrections only: these details do not affect verification. Use "Replace ID / photo" to change the picture.
        </p>
        <form onSubmit={submit}>
          <div className="form-grid">
            <label>
              Employee code *
              <input
                required
                autoFocus
                value={form.employeeCode}
                onChange={(e) => setForm({ ...form, employeeCode: e.target.value })}
              />
            </label>
            <label>
              Full name *
              <input required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
            </label>
            <label>
              Email *
              <input
                required
                type="email"
                value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}
              />
            </label>
            <label>
              Department *
              <select
                required
                value={form.department}
                onChange={(e) => setForm({ ...form, department: e.target.value })}
              >
                <option value="">Select department…</option>
                {departments.map((d) => (
                  <option key={d} value={d}>{d}</option>
                ))}
              </select>
            </label>
          </div>

          {error && <p className="error-text">{error}</p>}

          <div className="row">
            <button type="button" className="action" disabled={busy} onClick={props.onClose}>Cancel</button>
            <button type="submit" className="primary" disabled={busy || !complete || !changed}>
              {busy ? "Saving…" : "Save changes"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
