import { useEffect, useState } from "react";
import { api, type DeviceSummary } from "../api";

function statusBadge(status: string): string {
  if (status === "ACTIVE") return "badge match";
  if (status === "PENDING") return "badge check";
  return "badge error"; // REVOKED
}

export default function DevicesPage() {
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function refresh() {
    try {
      setDevices(await api.listDevices());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useEffect(() => {
    void refresh();
  }, []);

  async function revoke(device: DeviceSummary) {
    if (!confirm(`Revoke ${device.hostname ?? device.id}? The agent on that machine will stop verifying immediately.`)) {
      return;
    }
    setBusy(true);
    try {
      await api.revokeDevice(device.id);
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h2 style={{ marginTop: 0 }}>Registered devices</h2>
      <p className="muted">
        Revoking a device immediately blocks it from verifying and from
        re-authenticating. Use it for a lost, retired, or reassigned machine.
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
              <td>{d.employeeName ?? d.employeeId.slice(0, 8)}</td>
              <td>{d.platform}</td>
              <td>{d.hostname ?? "—"}</td>
              <td><span className={statusBadge(d.status)}>{d.status}</span></td>
              <td className="muted">{d.agentVersion ?? "—"}</td>
              <td className="muted">{d.registeredAt ? new Date(d.registeredAt).toLocaleDateString() : "—"}</td>
              <td className="muted">{d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString() : "never"}</td>
              <td>
                <button className="action" disabled={busy || d.status === "REVOKED"} onClick={() => void revoke(d)}>
                  Revoke
                </button>
              </td>
            </tr>
          ))}
          {devices.length === 0 && (
            <tr><td colSpan={8} className="muted">No devices registered yet.</td></tr>
          )}
        </tbody>
      </table>
    </>
  );
}
