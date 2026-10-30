import { useEffect, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";
import ActionMenu from "../components/ActionMenu";
import CardTitle from "../components/CardTitle";
import DataTable from "../components/DataTable";
import { useStatsBar } from "../components/StatsBar";

function statusBadge(status: string): string {
  if (status === "ACTIVE") return "badge match";
  if (status === "PENDING") return "badge check";
  return "badge error"; // REVOKED
}

export default function DevicesPage(props: { title: string; hint: string }) {
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [choice, setChoice] = useState<Record<string, string>>({}); // deviceId -> employeeId
  const [reassigning, setReassigning] = useState<string | null>(null); // deviceId with the picker open
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function refresh() {
    try {
      const [d, e] = await Promise.all([api.listDevices(), api.listEmployees()]);
      setDevices(d);
      setEmployees(e.filter((x) => x.status === "ACTIVE"));
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
    try {
      await work();
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const stats = useStatsBar({
    title: "System stats",
    compute: () => [
      { label: "Systems", value: devices.length },
      { label: "Assigned", value: devices.filter((d) => d.employeeId !== null && d.status === "ACTIVE").length, tone: "ok" },
      { label: "Unassigned", value: devices.filter((d) => d.employeeId === null && d.status !== "REVOKED").length, tone: "warn" },
      { label: "Revoked", value: devices.filter((d) => d.status === "REVOKED").length, tone: "bad" },
    ],
  });

  function revoke(device: DeviceSummary) {
    if (!confirm(`Revoke ${device.hostname ?? device.id}? The agent on that machine will stop verifying immediately.`)) {
      return;
    }
    void run(() => api.revokeDevice(device.id));
  }

  function assign(device: DeviceSummary) {
    const employeeId = choice[device.id];
    if (!employeeId) return;
    const target = employees.find((e) => e.id === employeeId);
    if (
      device.employeeId !== null &&
      !confirm(
        `Reassign ${device.hostname ?? device.id} from ${device.employeeName ?? "its employee"} to ` +
          `${target?.fullName ?? "the selected employee"}? The agent switches on its next poll; if the new ` +
          `employee has no live enrollment yet, their first check is the enrollment scan.`,
      )
    ) {
      return;
    }
    void run(async () => {
      await api.assignDevice(device.id, employeeId);
      setReassigning(null);
      setChoice({ ...choice, [device.id]: "" });
    });
  }

  function picker(device: DeviceSummary) {
    const candidates = employees.filter((e) => e.id !== device.employeeId);
    return (
      <>
        <select
          value={choice[device.id] ?? ""}
          onChange={(e) => setChoice({ ...choice, [device.id]: e.target.value })}
          disabled={busy}
        >
          <option value="">Select employee…</option>
          {candidates.map((e) => (
            <option key={e.id} value={e.id}>
              {e.fullName} ({e.employeeCode})
            </option>
          ))}
        </select>{" "}
        <button className="action" disabled={busy || !choice[device.id]} onClick={() => assign(device)}>
          {device.employeeId === null ? "Assign" : "Confirm reassign"}
        </button>{" "}
      </>
    );
  }

  return (
    <>
      <div className="toolbar">
        <CardTitle title={props.title} hint={props.hint} />
        <span className="toolbar-right">{stats.controls}</span>
      </div>
      {stats.panel}
      {error && <p className="error-text">{error}</p>}

      <DataTable
        rows={devices}
        rowKey={(d) => d.id}
        emptyText="No systems registered yet."
        columns={[
          {
            key: "employee", header: "Employee",
            value: (d) => (d.employeeId === null ? "Unassigned" : (d.employeeName ?? d.employeeId.slice(0, 8))),
            render: (d) =>
              d.employeeId === null
                ? <span className="badge check">Unassigned</span>
                : (d.employeeName ?? d.employeeId.slice(0, 8)),
          },
          { key: "hostname", header: "Hostname", value: (d) => d.hostname ?? "—" },
          {
            key: "status", header: "Status", filter: "select",
            value: (d) => d.status,
            render: (d) => <span className={statusBadge(d.status)}>{d.status}</span>,
          },
          {
            key: "enrolled", header: "Enrollment", filter: "select",
            value: (d) => (d.employeeId === null ? "—" : d.enrolledAt ? "done" : "pending first scan"),
            render: (d) =>
              d.employeeId === null
                ? <span className="muted">—</span>
                : d.enrolledAt
                  ? <span className="badge match" title={`Enrollment scan matched ${new Date(d.enrolledAt).toLocaleString()}`}>done</span>
                  : <span className="badge neutral" title="The first check on this system is the enrollment scan">pending first scan</span>,
          },
          { key: "agent", header: "Agent", value: (d) => d.agentVersion ?? "—", cellStyle: { color: "var(--muted)" } },
          {
            key: "registered", header: "Registered",
            value: (d) => (d.registeredAt ? new Date(d.registeredAt).toLocaleDateString() : "—"),
            cellStyle: { color: "var(--muted)" },
          },
          {
            key: "lastSeen", header: "Last seen",
            value: (d) => (d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString() : "never"),
            cellStyle: { color: "var(--muted)" },
          },
          {
            key: "actions", header: "", filter: "none", value: () => "",
            cellStyle: { whiteSpace: "nowrap", textAlign: "right" },
            render: (d) => (
              <>
                {d.status !== "REVOKED" && d.employeeId === null && picker(d)}
                {d.status !== "REVOKED" && d.employeeId !== null && reassigning === d.id && (
                  <>
                    {picker(d)}
                    <button className="action" disabled={busy} onClick={() => setReassigning(null)}>
                      Cancel
                    </button>{" "}
                  </>
                )}
                <ActionMenu
                  disabled={busy || d.status === "REVOKED"}
                  label={`Actions for ${d.hostname ?? d.id}`}
                  items={[
                    {
                      label: "Reassign to another employee", onClick: () => setReassigning(d.id),
                      disabled: d.employeeId === null, title: "Hand this system to a different employee",
                    },
                    { label: "Revoke", onClick: () => revoke(d), danger: true, title: "Lost or retired machine: blocks it immediately" },
                  ]}
                />
              </>
            ),
          },
        ]}
      />
    </>
  );
}
