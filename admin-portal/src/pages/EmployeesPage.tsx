import { useEffect, useRef, useState } from "react";
import { api, type Employee } from "../api";
import AddEmployeeDialog from "../components/AddEmployeeDialog";
import CameraCapture from "../components/CameraCapture";

export default function EmployeesPage() {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [cameraFor, setCameraFor] = useState<Employee | null>(null);
  const [adding, setAdding] = useState(false);
  const fileTargetRef = useRef<Employee | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

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

  function offboard(employee: Employee) {
    if (
      !confirm(
        `Offboard ${employee.fullName}? They are marked OFFBOARDED and every system assigned to them is ` +
          `deregistered in the same step (back to Unassigned, so it can be handed to the next employee).`,
      )
    ) {
      return;
    }
    void run(async () => {
      const result = await api.offboardEmployee(employee.id);
      alert(`${employee.fullName} offboarded. Systems deregistered: ${result.devicesDeregistered}.`);
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
      <div className="page-head">
        <h2>Employees</h2>
        <button className="primary" disabled={busy} onClick={() => setAdding(true)}>+ Add employee</button>
      </div>

      {error && <p className="error-text">{error}</p>}

      <table>
        <thead>
          <tr>
            <th>Code</th><th>Name</th><th>Email</th><th>Department</th><th>Status</th>
            <th>ID / photo</th><th>Live enrollment</th><th>Actions</th>
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
                  ? <span className="badge match">uploaded</span>
                  : <span className="badge check">missing</span>}
              </td>
              <td title="The first live face scan that matches the uploaded ID/photo becomes the second reference.">
                {emp.hasLiveEnrollment
                  ? <span className="badge match">done</span>
                  : <span className="badge neutral">pending first scan</span>}
              </td>
              <td style={{ whiteSpace: "nowrap" }}>
                <button className="action" disabled={busy} onClick={() => setCameraFor(emp)}>Webcam</button>{" "}
                <button className="action" disabled={busy} onClick={() => pickFile(emp)}>Upload photo</button>{" "}
                <button className="action" disabled={busy || emp.status === "OFFBOARDED"} onClick={() => offboard(emp)}
                        title="Marks the employee OFFBOARDED and deregisters their systems">
                  Offboard
                </button>
              </td>
            </tr>
          ))}
          {employees.length === 0 && (
            <tr><td colSpan={8} className="muted">No employees yet — click “Add employee” to create the first one.</td></tr>
          )}
        </tbody>
      </table>

      <input ref={fileInputRef} type="file" accept="image/jpeg,image/png" hidden onChange={onFileChosen} />

      {adding && (
        <AddEmployeeDialog
          onClose={() => setAdding(false)}
          onCreated={() => {
            setAdding(false);
            void refresh();
          }}
        />
      )}

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
