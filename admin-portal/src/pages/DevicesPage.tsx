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
    void run(async () => {
      await api.assignDevice(device.id, employeeId);
    });
  }

  return (
    <>
      <h2 style={{ marginTop: 0 }}>Registered systems</h2>
      <p className="muted">
        A system that registered itself on install shows as <em>Unassigned</em> until you link it to
        an employee — that activates verification on it. Revoking immediately blocks a device from
        verifying and from re-authenticating (lost, retired, or reassigned machine).
      </p>
      {error && <p className="error-text">{error}</p>}

      <table>
        <thead>
          <tr>
            <th>Employee</th><th>Platform</th><th>Hostname</th><th>Status</th>
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
              <td>{d.platform}</td>
              <td>{d.hostname ?? "—"}</td>
              <td><span className={statusBadge(d.status)}>{d.status}</span></td>
              <td className="muted">{d.agentVersion ?? "—"}</td>
              <td className="muted">{d.registeredAt ? new Date(d.registeredAt).toLocaleDateString() : "—"}</td>
              <td className="muted">{d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString() : "never"}</td>
              <td>
                {d.employeeId === null && d.status !== "REVOKED" && (
                  <>
                    <select
                      value={choice[d.id] ?? ""}
                      onChange={(e) => setChoice({ ...choice, [d.id]: e.target.value })}
                      disabled={busy}
                    >
                      <option value="">Select employee…</option>
                      {employees.map((e) => (
                        <option key={e.id} value={e.id}>
                          {e.fullName} ({e.employeeCode})
                        </option>
                      ))}
                    </select>{" "}
                    <button className="action" disabled={busy || !choice[d.id]} onClick={() => assign(d)}>
                      Assign
                    </button>{" "}
                  </>
                )}
                <button className="action" disabled={busy || d.status === "REVOKED"} onClick={() => revoke(d)}>
                  Revoke
                </button>
              </td>
            </tr>
          ))}
          {devices.length === 0 && (
            <tr><td colSpan={8} className="muted">No systems registered yet.</td></tr>
          )}
        </tbody>
      </table>
    </>
  );
}
