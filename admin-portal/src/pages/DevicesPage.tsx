import { useEffect, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";

function statusBadge(status: string): string {
  if (status === "ACTIVE") return "badge match";
  if (status === "PENDING") return "badge check";
  return "badge error"; // REVOKED
}

export default function DevicesPage() {
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
      <h2 style={{ marginTop: 0 }}>Registered systems</h2>
      <p className="muted">
        A system that registered itself on install shows as <em>Unassigned</em> until you link it to
        an employee — that activates verification on it. <em>Reassign</em> hands a system to a different
        employee; offboarding an employee deregisters their systems back to Unassigned. Revoking
        immediately blocks a device from verifying and from re-authenticating (lost or retired machine).
      </p>
      {error && <p className="error-text">{error}</p>}

      <table>
        <thead>
          <tr>
            <th>Employee</th><th>Hostname</th><th>Status</th>
            <th>Agent</th><th>Registered</th><th>Last seen</th><th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {devices.map((d) => (
            <tr key={d.id}>
              <td>
                {d.employeeId === null ? (
                  <span className="badge check">Unassigned</span>
                ) : (
                  d.employeeName ?? d.employeeId.slice(0, 8)
                )}
              </td>
              <td>{d.hostname ?? "—"}</td>
              <td><span className={statusBadge(d.status)}>{d.status}</span></td>
              <td className="muted">{d.agentVersion ?? "—"}</td>
              <td className="muted">{d.registeredAt ? new Date(d.registeredAt).toLocaleDateString() : "—"}</td>
              <td className="muted">{d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString() : "never"}</td>
              <td style={{ whiteSpace: "nowrap" }}>
                {d.status !== "REVOKED" && d.employeeId === null && picker(d)}
                {d.status !== "REVOKED" && d.employeeId !== null && (
                  reassigning === d.id ? (
                    <>
                      {picker(d)}
                      <button className="action" disabled={busy} onClick={() => setReassigning(null)}>
                        Cancel
                      </button>{" "}
                    </>
                  ) : (
                    <>
                      <button
                        className="action"
                        disabled={busy}
                        onClick={() => setReassigning(d.id)}
                        title="Hand this system to a different employee"
                      >
                        Reassign
                      </button>{" "}
                    </>
                  )
                )}
                <button className="action" disabled={busy || d.status === "REVOKED"} onClick={() => revoke(d)}>
                  Revoke
                </button>
              </td>
            </tr>
          ))}
          {devices.length === 0 && (
            <tr><td colSpan={7} className="muted">No systems registered yet.</td></tr>
          )}
        </tbody>
      </table>
    </>
  );
}
