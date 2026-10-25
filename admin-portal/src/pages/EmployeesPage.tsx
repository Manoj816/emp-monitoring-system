import { useEffect, useRef, useState } from "react";
import { api, type Employee } from "../api";
import CameraCapture from "../components/CameraCapture";

export default function EmployeesPage() {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [token, setToken] = useState<{ employee: string; value: string } | null>(null);
  const [cameraFor, setCameraFor] = useState<Employee | null>(null);
  const fileTargetRef = useRef<Employee | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [form, setForm] = useState({ employeeCode: "", fullName: "", email: "", department: "" });

  async function refresh() {
    try {
      setEmployees(await api.listEmployees());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useEffect(() => {
    void refresh();
  }, []);

  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await work();
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function createEmployee(e: React.FormEvent) {
    e.preventDefault();
    void run(async () => {
      await api.createEmployee({
        employeeCode: form.employeeCode.trim(),
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        department: form.department.trim() || undefined,
      });
      setForm({ employeeCode: "", fullName: "", email: "", department: "" });
    });
  }

  function issueToken(employee: Employee) {
    void run(async () => {
      const result = await api.issueEnrollmentToken(employee.id);
      setToken({ employee: `${employee.fullName} (${employee.employeeCode})`, value: result.enrollmentToken });
    });
  }

  function pickFile(employee: Employee) {
    fileTargetRef.current = employee;
    fileInputRef.current?.click();
  }

  function onFileChosen(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    const target = fileTargetRef.current;
    e.target.value = "";
    if (file && target) {
      void run(() => api.uploadReferenceImage(target.id, file).then(() => undefined));
    }
  }

  return (
    <>
      <h2 style={{ marginTop: 0 }}>Employees</h2>

      <form className="inline" onSubmit={createEmployee}>
        <label>
          Employee code
          <input required value={form.employeeCode} onChange={(e) => setForm({ ...form, employeeCode: e.target.value })} />
        </label>
        <label>
          Full name
          <input required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
        </label>
        <label>
          Email
          <input required type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </label>
        <label>
          Department
          <input value={form.department} onChange={(e) => setForm({ ...form, department: e.target.value })} />
        </label>
        <button className="primary" type="submit" disabled={busy}>Add employee</button>
      </form>

      {error && <p className="error-text">{error}</p>}

      {token && (
        <div className="notice">
          Enrollment token for <strong>{token.employee}</strong> (single use, 24h) — paste into the agent:
          <br />
          <code>{token.value}</code>
          <div style={{ marginTop: 8 }}>
            <button className="action" onClick={() => void navigator.clipboard.writeText(token.value)}>Copy</button>{" "}
            <button className="action" onClick={() => setToken(null)}>Dismiss</button>
          </div>
        </div>
      )}

      <table>
        <thead>
          <tr>
            <th>Code</th><th>Name</th><th>Email</th><th>Department</th><th>Status</th>
            <th>Reference image</th><th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {employees.map((emp) => (
            <tr key={emp.id}>
              <td>{emp.employeeCode}</td>
              <td>{emp.fullName}</td>
              <td>{emp.email}</td>
              <td>{emp.department ?? "—"}</td>
              <td><span className={`badge ${emp.status === "ACTIVE" ? "match" : "neutral"}`}>{emp.status}</span></td>
              <td>
                {emp.hasReferenceImage
                  ? <span className="badge match">enrolled</span>
                  : <span className="badge check">missing</span>}
              </td>
              <td style={{ whiteSpace: "nowrap" }}>
                <button className="action" disabled={busy} onClick={() => setCameraFor(emp)}>Webcam</button>{" "}
                <button className="action" disabled={busy} onClick={() => pickFile(emp)}>Upload photo</button>{" "}
                <button className="action" disabled={busy || !emp.hasReferenceImage} onClick={() => issueToken(emp)}
                        title={emp.hasReferenceImage ? "" : "Enroll a reference image first"}>
                  Issue token
                </button>
              </td>
            </tr>
          ))}
          {employees.length === 0 && (
            <tr><td colSpan={7} className="muted">No employees yet — add one above.</td></tr>
          )}
        </tbody>
      </table>

      <input ref={fileInputRef} type="file" accept="image/jpeg,image/png" hidden onChange={onFileChosen} />

      {cameraFor && (
        <CameraCapture
          onCancel={() => setCameraFor(null)}
          onCapture={(blob) => {
            const target = cameraFor;
            setCameraFor(null);
            void run(() => api.uploadReferenceImage(target.id, blob).then(() => undefined));
          }}
        />
      )}
    </>
  );
}
