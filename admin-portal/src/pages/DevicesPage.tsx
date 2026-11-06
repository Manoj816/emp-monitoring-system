import { useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";
import ActionMenu from "../components/ActionMenu";
import AssignEmployeeDialog from "../components/AssignEmployeeDialog";
import CardTitle from "../components/CardTitle";
import DataTable from "../components/DataTable";
import DeviceDetailsDialog from "../components/DeviceDetailsDialog";
import { useFreshOnActive } from "../useFreshOnActive";
import { useStatsBar, type StatItem } from "../components/StatsBar";
import { useTableFilters } from "../components/TableFilters";

function statusBadge(status: string): string {
  if (status === "ACTIVE") return "badge match";
  if (status === "PENDING") return "badge check";
  return "badge error"; // REVOKED
}

/**
 * The plain text each filterable column holds for a row, in one place so the
 * stat tiles count with the very expression the table filters on.
 *
 * Status carries the assignment too, which is why the tiles can go through it:
 * the server only ever sets ACTIVE together with an employee and only ever
 * clears the employee back to PENDING, so ACTIVE means assigned and PENDING
 * means waiting for an administrator.
 */
const COLUMN_VALUE: Record<string, (d: DeviceSummary) => string> = {
  status: (d) => d.status,
};

export default function DevicesPage(props: { title: string; hint: string; active: boolean }) {
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [assigning, setAssigning] = useState<DeviceSummary | null>(null);
  const [details, setDetails] = useState<DeviceSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function refresh() {
    try {
      const [d, e] = await Promise.all([api.listDevices(), api.listEmployees()]);
      setDevices(d);
      // Keep an open details popup on the fresh row; a deleted system closes it.
      setDetails((current) => (current ? (d.find((x) => x.id === current.id) ?? null) : null));
      // All employees: the assign popup shows non-selectable ones (offboarded,
      // already assigned here) disabled with the reason, so a search never
      // looks broken just because somebody is filtered out.
      setEmployees(e);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useFreshOnActive(props.active, () => void refresh());

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

  const tableFilters = useTableFilters();

  const stats = useStatsBar({
    title: "System stats",
    // Every tile opens the table on exactly the rows it counted, the way the
    // check tiles do: the number and the list it leads to are the same query.
    compute: () => {
      const matching = (filters: Record<string, string>) =>
        devices.filter((d) =>
          Object.entries(filters).every(([column, want]) => COLUMN_VALUE[column](d) === want));

      const tile = (label: string, filters: Record<string, string>, tone?: StatItem["tone"]): StatItem => {
        const count = matching(filters).length;
        return {
          label,
          value: count,
          tone,
          onClick: () => tableFilters.replace(filters),
          title: Object.keys(filters).length === 0
            ? "Clear the filters and show every system"
            : `Show these ${count} in the table`,
        };
      };

      return [
        tile("Systems", {}),
        tile("Assigned", { status: "ACTIVE" }, "ok"),
        tile("Unassigned", { status: "PENDING" }, "warn"),
        tile("Revoked", { status: "REVOKED" }, "bad"),
      ];
    },
  });

  function revoke(device: DeviceSummary) {
    if (!confirm(`Revoke ${device.hostname ?? device.id}? The agent on that machine will stop verifying immediately.`)) {
      return;
    }
    void run(() => api.revokeDevice(device.id));
  }

  function remove(device: DeviceSummary) {
    if (
      !confirm(
        `Delete ${device.hostname ?? device.id} from the list? Its check history stays with the employee. ` +
          `If the agent on that machine is still running, it registers again as Unassigned.`,
      )
    ) {
      return;
    }
    void run(() => api.deleteDevice(device.id));
  }

  return (
    <>
      <div className="toolbar">
        <CardTitle title={props.title} hint={props.hint} />
        <span className="toolbar-right">{tableFilters.control}{stats.controls}</span>
      </div>
      {stats.panel}
      {error && <p className="error-text">{error}</p>}

      <DataTable
        {...tableFilters.tableProps}
        rows={devices}
        rowKey={(d) => d.id}
        onRowClick={(d) => setDetails(d)}
        rowLabel={(d) => `Open ${d.hostname ?? d.id}`}
        emptyText="No systems registered yet."
        columns={[
          { key: "hostname", header: "Hostname", value: (d) => d.hostname ?? "—" },
          {
            key: "employee", header: "Employee",
            value: (d) => (d.employeeId === null ? "Unassigned" : (d.employeeName ?? d.employeeId.slice(0, 8))),
            render: (d) =>
              d.employeeId === null
                ? <span className="badge check">Unassigned</span>
                : (d.employeeName ?? d.employeeId.slice(0, 8)),
          },
          {
            key: "status", header: "Status", filter: "select",
            value: COLUMN_VALUE.status,
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
                {d.status !== "REVOKED" && d.employeeId === null && (
                  <>
                    <button className="action" disabled={busy} onClick={() => setAssigning(d)}>
                      Assign employee
                    </button>{" "}
                  </>
                )}
                <ActionMenu
                  disabled={busy}
                  label={`Actions for ${d.hostname ?? d.id}`}
                  items={[
                    {
                      label: "Details", onClick: () => setDetails(d),
                      title: "Registration record, assigned employee and the checks that ran on this system",
                    },
                    {
                      label: "Reassign to another employee", onClick: () => setAssigning(d),
                      disabled: d.employeeId === null || d.status === "REVOKED",
                      title: "Hand this system to a different employee",
                    },
                    {
                      label: "Revoke", onClick: () => revoke(d), danger: true, disabled: d.status === "REVOKED",
                      title: "Lost or retired machine: blocks it immediately",
                    },
                    {
                      label: "Delete", onClick: () => remove(d), danger: true,
                      title: "Remove this row (stale duplicate or retired machine); its check history stays with the employee",
                    },
                  ]}
                />
              </>
            ),
          },
        ]}
      />

      {details && (
        <DeviceDetailsDialog
          device={details}
          employee={employees.find((e) => e.id === details.employeeId) ?? null}
          busy={busy}
          onClose={() => setDetails(null)}
          // The popup stays open behind the picker, so it shows the new assignment once it is made.
          onAssign={() => setAssigning(details)}
          onRevoke={() => revoke(details)}
          onDelete={() => remove(details)}
        />
      )}

      {assigning && (
        <AssignEmployeeDialog
          device={assigning}
          employees={employees}
          onClose={() => setAssigning(null)}
          onAssigned={() => {
            setAssigning(null);
            void refresh();
          }}
        />
      )}
    </>
  );
}
