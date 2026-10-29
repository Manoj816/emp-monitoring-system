import { useEffect, useRef, useState } from "react";
import { api, type Employee } from "../api";
import AddEmployeeDialog from "../components/AddEmployeeDialog";
import CameraCapture from "../components/CameraCapture";
import DataTable from "../components/DataTable";
import EmployeeDetailsDialog from "../components/EmployeeDetailsDialog";
import Stats from "../components/Stats";

export default function EmployeesPage() {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [cameraFor, setCameraFor] = useState<Employee | null>(null);
  const [adding, setAdding] = useState(false);
  const [details, setDetails] = useState<Employee | null>(null);
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
      <div className="toolbar">
        <Stats
          items={[
            { label: "Employees", value: employees.length },
            { label: "Active", value: employees.filter((e) => e.status === "ACTIVE").length, tone: "ok" },
            { label: "Photo missing", value: employees.filter((e) => !e.hasReferenceImage).length, tone: "warn" },
            { label: "Live enrollment done", value: employees.filter((e) => e.hasLiveEnrollment).length, tone: "ok" },
          ]}
        />
        <button className="primary" disabled={busy} onClick={() => setAdding(true)}>+ Add employee</button>
      </div>

      {error && <p className="error-text">{error}</p>}

      <DataTable
        rows={employees}
        rowKey={(emp) => emp.id}
        emptyText="No employees yet — click “Add employee” to create the first one."
        columns={[
          { key: "code", header: "Code", value: (emp) => emp.employeeCode },
          { key: "name", header: "Name", value: (emp) => emp.fullName },
          { key: "email", header: "Email", value: (emp) => emp.email },
          { key: "department", header: "Department", value: (emp) => emp.department ?? "—" },
          {
            key: "status", header: "Status", filter: "select",
            value: (emp) => emp.status,
            render: (emp) => <span className={`badge ${emp.status === "ACTIVE" ? "match" : "neutral"}`}>{emp.status}</span>,
          },
          {
            key: "photo", header: "ID / photo", filter: "select",
            value: (emp) => (emp.hasReferenceImage ? "uploaded" : "missing"),
            render: (emp) =>
              emp.hasReferenceImage
                ? <span className="badge match">uploaded</span>
                : <span className="badge check">missing</span>,
          },
          {
            key: "enrollment", header: "Live enrollment", filter: "select",
            value: (emp) => (emp.hasLiveEnrollment ? "done" : "pending first scan"),
            render: (emp) => (
              <span title="The first live face scan that matches the uploaded ID/photo becomes the second reference.">
                {emp.hasLiveEnrollment
                  ? <span className="badge match">done</span>
                  : <span className="badge neutral">pending first scan</span>}
              </span>
            ),
          },
          {
            key: "actions", header: "Actions", filter: "none", value: () => "",
            cellStyle: { whiteSpace: "nowrap" },
            render: (emp) => (
              <>
                <button className="action" disabled={busy} onClick={() => setDetails(emp)}
                        title="Record, uploaded ID/photo, enrolled live scan and assigned systems">
                  Details
                </button>{" "}
                <button className="action" disabled={busy} onClick={() => setCameraFor(emp)}>Webcam</button>{" "}
                <button className="action" disabled={busy} onClick={() => pickFile(emp)}>Upload photo</button>{" "}
                <button className="action" disabled={busy || emp.status === "OFFBOARDED"} onClick={() => offboard(emp)}
                        title="Marks the employee OFFBOARDED and deregisters their systems">
                  Offboard
                </button>
              </>
            ),
          },
        ]}
      />

      <input ref={fileInputRef} type="file" accept="image/jpeg,image/png" hidden onChange={onFileChosen} />

      {details && <EmployeeDetailsDialog employee={details} onClose={() => setDetails(null)} />}

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
