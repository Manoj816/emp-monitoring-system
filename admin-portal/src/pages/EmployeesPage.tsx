import { useEffect, useRef, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";
import ActionMenu from "../components/ActionMenu";
import AddEmployeeDialog from "../components/AddEmployeeDialog";
import CameraCapture from "../components/CameraCapture";
import CardTitle from "../components/CardTitle";
import DataTable from "../components/DataTable";
import EmployeeDetailsDialog from "../components/EmployeeDetailsDialog";
import { useStatsBar } from "../components/StatsBar";

export default function EmployeesPage(props: { title: string; hint: string }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [cameraFor, setCameraFor] = useState<Employee | null>(null);
  const [adding, setAdding] = useState(false);
  const [details, setDetails] = useState<Employee | null>(null);
  const fileTargetRef = useRef<Employee | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  async function refresh() {
    try {
      const [emps, devs] = await Promise.all([api.listEmployees(), api.listDevices()]);
      setEmployees(emps);
      setDevices(devs);
      // Keep an open details popup on the fresh record (photo/enrollment flags).
      setDetails((current) => (current ? (emps.find((e) => e.id === current.id) ?? current) : current));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  /** The systems currently assigned to an employee (revoked ones excluded). */
  function systemsOf(employee: Employee): DeviceSummary[] {
    return devices.filter((d) => d.employeeId === employee.id && d.status !== "REVOKED");
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

  const stats = useStatsBar({
    title: "Employee stats",
    compute: () => [
      { label: "Employees", value: employees.length },
      { label: "Active", value: employees.filter((e) => e.status === "ACTIVE").length, tone: "ok" },
      { label: "Photo missing", value: employees.filter((e) => !e.hasReferenceImage).length, tone: "warn" },
      { label: "Live enrollment done", value: employees.filter((e) => e.hasLiveEnrollment).length, tone: "ok" },
    ],
  });

  function requestFirstScan(employee: Employee) {
    if (
      !confirm(
        `Ask ${employee.fullName} for the first scan again? The current live scan is retired and their system(s) ` +
          `run the enrollment scan as the next check (the day's random time, or Verify now). The uploaded ID/photo stays.`,
      )
    ) {
      return;
    }
    void run(async () => {
      const r = await api.requestReEnrollment(employee.id);
      alert(`Re-enrollment requested for ${employee.fullName}: ${r.systemsReset} system(s) will ask for the enrollment scan.`);
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
        <CardTitle title={props.title} hint={props.hint} />
        <span className="toolbar-right">
          {stats.controls}
          <button className="primary" disabled={busy} onClick={() => setAdding(true)}>+ Add employee</button>
        </span>
      </div>
      {stats.panel}

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
            key: "system", header: "System",
            value: (emp) => {
              const names = systemsOf(emp).map((d) => d.hostname ?? d.id.slice(0, 8));
              return names.length === 0 ? "none" : names.join(", ");
            },
            render: (emp) => {
              const list = systemsOf(emp);
              if (list.length === 0) return <span className="muted">none</span>;
              return (
                <>
                  {list.map((d) => (
                    <span key={d.id} className="badge neutral" style={{ marginRight: 4 }}
                          title={`${d.status} · agent ${d.agentVersion ?? "?"} · last seen ${d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString() : "never"}`}>
                      {d.hostname ?? d.id.slice(0, 8)}
                    </span>
                  ))}
                </>
              );
            },
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
            key: "actions", header: "", filter: "none", value: () => "",
            cellStyle: { whiteSpace: "nowrap", textAlign: "right", width: 48 },
            render: (emp) => (
              <ActionMenu
                disabled={busy}
                label={`Actions for ${emp.fullName}`}
                items={[
                  { label: "Details", onClick: () => setDetails(emp), title: "Record, pictures and assigned systems" },
                  { label: "Replace ID / photo", onClick: () => pickFile(emp) },
                  { label: "Replace with webcam", onClick: () => setCameraFor(emp) },
                  {
                    label: "Request first scan again", onClick: () => requestFirstScan(emp),
                    disabled: emp.status !== "ACTIVE", title: "Keep the photo; the employee enrolls again at the next check",
                  },
                  {
                    label: "Offboard", onClick: () => offboard(emp), danger: true,
                    disabled: emp.status === "OFFBOARDED", title: "Marks the employee OFFBOARDED and deregisters their systems",
                  },
                ]}
              />
            ),
          },
        ]}
      />

      <input ref={fileInputRef} type="file" accept="image/jpeg,image/png" hidden onChange={onFileChosen} />

      {details && (
        <EmployeeDetailsDialog
          employee={details}
          onClose={() => setDetails(null)}
          onChanged={() => void refresh()}
        />
      )}

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
