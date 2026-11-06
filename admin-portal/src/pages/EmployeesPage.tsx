import { useRef, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";
import ActionMenu from "../components/ActionMenu";
import { appAlert, appConfirm } from "../components/Confirm";
import AddEmployeeDialog from "../components/AddEmployeeDialog";
import CardTitle from "../components/CardTitle";
import ConfirmPhotoDialog from "../components/ConfirmPhotoDialog";
import DataTable from "../components/DataTable";
import EmployeeDetailsDialog from "../components/EmployeeDetailsDialog";
import ImportEmployeesDialog from "../components/ImportEmployeesDialog";
import { useStatsBar, type StatItem } from "../components/StatsBar";
import { useTableFilters } from "../components/TableFilters";
import { useFreshOnActive } from "../useFreshOnActive";

/**
 * The plain text each filterable column holds for a row, in one place so the
 * stat tiles count with the very expression the table filters on. A tile then
 * cannot show a number the rows it opens disagree with.
 */
const COLUMN_VALUE: Record<string, (emp: Employee) => string> = {
  status: (emp) => emp.status,
  photo: (emp) => (emp.hasReferenceImage ? "uploaded" : "missing"),
  enrollment: (emp) => (emp.hasLiveEnrollment ? "done" : "pending first scan"),
};

export default function EmployeesPage(props: { title: string; hint: string; active: boolean }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [importing, setImporting] = useState(false);
  const [details, setDetails] = useState<Employee | null>(null);
  // Picked but not uploaded: the confirmation step shows it next to the current one.
  const [pendingPhoto, setPendingPhoto] = useState<{ employee: Employee; photo: Blob } | null>(null);
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

  useFreshOnActive(props.active, () => void refresh());

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

  async function offboard(employee: Employee) {
    const ok = await appConfirm(
      `Offboard ${employee.fullName}? They are marked OFFBOARDED and every system assigned to them is ` +
        `deregistered in the same step (back to Unassigned, so it can be handed to the next employee).`,
      { title: "Offboard employee", confirmLabel: "Offboard", danger: true },
    );
    if (!ok) return;
    void run(async () => {
      const result = await api.offboardEmployee(employee.id);
      await appAlert(
        `${employee.fullName} offboarded. Systems deregistered: ${result.devicesDeregistered}.`,
        "Offboarded",
      );
    });
  }

  const tableFilters = useTableFilters();

  const stats = useStatsBar({
    title: "Employee stats",
    // Every tile opens the table on exactly the rows it counted, the way the
    // check tiles do: the number and the list it leads to are the same query.
    compute: () => {
      const matching = (filters: Record<string, string>) =>
        employees.filter((emp) =>
          Object.entries(filters).every(([column, want]) => COLUMN_VALUE[column](emp) === want));

      const tile = (label: string, filters: Record<string, string>, tone?: StatItem["tone"]): StatItem => {
        const count = matching(filters).length;
        return {
          label,
          value: count,
          tone,
          onClick: () => tableFilters.replace(filters),
          title: Object.keys(filters).length === 0
            ? "Clear the filters and show every employee"
            : `Show these ${count} in the table`,
        };
      };

      return [
        tile("Employees", {}),
        tile("Active", { status: "ACTIVE" }, "ok"),
        tile("Photo missing", { photo: "missing" }, "warn"),
        tile("Live enrollment done", { enrollment: "done" }, "ok"),
      ];
    },
  });

  async function requestFirstScan(employee: Employee) {
    const ok = await appConfirm(
      `Ask ${employee.fullName} for the first scan again? The current live scan is retired and their system(s) ` +
        `run the enrollment scan as the next check. The uploaded ID/photo stays.`,
      { title: "Request first scan", confirmLabel: "Request scan" },
    );
    if (!ok) return;
    void run(async () => {
      const r = await api.requestReEnrollment(employee.id);
      await appAlert(
        `Re-enrollment requested for ${employee.fullName}: ${r.systemsReset} system(s) will ask for the enrollment scan.`,
        "Requested",
      );
    });
  }

  async function sendCheck(employee: Employee) {
    const ok = await appConfirm(
      `Send a verification check to ${employee.fullName} now? Their system opens the scan ` +
        `within a minute of being online, outside the day's random plan.`,
      { title: "Send a check now", confirmLabel: "Send check" },
    );
    if (!ok) return;
    void run(async () => {
      const r = await api.requestCheck({ employeeId: employee.id });
      await appAlert(
        `Check sent to ${r.sent[0].employeeName}: ${r.sent[0].systems} system(s) will open the scan.`,
        "Check sent",
      );
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
    if (!file || !target) return;
    if (!/^image\/(jpeg|png)$/.test(file.type)) {
      setError("The ID/photo must be a JPEG or PNG image.");
      return;
    }
    setError(null);
    setPendingPhoto({ employee: target, photo: file }); // confirmed before it is uploaded
  }

  return (
    <>
      <div className="toolbar">
        <CardTitle title={props.title} hint={props.hint} />
        <span className="toolbar-right">
          {tableFilters.control}
          {stats.controls}
          <button
            className="action toolbar-action"
            disabled={busy}
            title="Create many employees at once from a CSV, with their ID/photos alongside it"
            onClick={() => setImporting(true)}
          >
            Import CSV
          </button>
          <button className="primary" disabled={busy} onClick={() => setAdding(true)}>+ Add employee</button>
        </span>
      </div>
      {stats.panel}

      {error && <p className="error-text">{error}</p>}

      <DataTable
        {...tableFilters.tableProps}
        rows={employees}
        rowKey={(emp) => emp.id}
        onRowClick={(emp) => setDetails(emp)}
        rowLabel={(emp) => `Open ${emp.fullName}`}
        emptyText="No employees yet — click “Add employee” to create the first one."
        columns={[
          { key: "code", header: "Domain ID", value: (emp) => emp.employeeCode },
          { key: "name", header: "Name", value: (emp) => emp.fullName },
          { key: "email", header: "Email", value: (emp) => emp.email },
          {
            // A fixed list on the employee form, so it is a fixed list to filter by.
            key: "department", header: "Department", filter: "select",
            value: (emp) => emp.department ?? "—",
          },
          {
            key: "status", header: "Status", filter: "select",
            value: COLUMN_VALUE.status,
            render: (emp) => <span className={`badge ${emp.status === "ACTIVE" ? "match" : "neutral"}`}>{emp.status}</span>,
          },
          {
            key: "photo", header: "ID / photo", filter: "select",
            value: COLUMN_VALUE.photo,
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
            value: COLUMN_VALUE.enrollment,
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
                  {
                    label: "Details", onClick: () => setDetails(emp),
                    title: "Record, pictures and assigned systems — edit the details with the pencil inside",
                  },
                  { label: "Replace ID / photo", onClick: () => pickFile(emp) },
                  {
                    label: "Request first scan again", onClick: () => void requestFirstScan(emp),
                    disabled: emp.status !== "ACTIVE", title: "Keep the photo; the employee enrolls again at the next check",
                  },
                  {
                    label: "Send check now", onClick: () => void sendCheck(emp),
                    disabled: emp.status !== "ACTIVE" || systemsOf(emp).length === 0,
                    title: "Their system opens a verification scan within a minute of being online",
                  },
                  {
                    label: "Offboard", onClick: () => void offboard(emp), danger: true,
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

      {importing && (
        <ImportEmployeesDialog
          onClose={() => setImporting(false)}
          // The popup stays open on the report; the table behind it is already fresh.
          onImported={() => void refresh()}
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

      {pendingPhoto && (
        <ConfirmPhotoDialog
          employee={pendingPhoto.employee}
          photo={pendingPhoto.photo}
          busy={busy}
          onCancel={() => setPendingPhoto(null)}
          onConfirm={() => {
            const { employee, photo } = pendingPhoto;
            void run(async () => {
              await api.uploadReferenceImage(employee.id, photo);
              setPendingPhoto(null);
            });
          }}
        />
      )}
    </>
  );
}
