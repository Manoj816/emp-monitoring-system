import { useEffect, useState } from "react";
import { api, type AdminCheckRow, type DeviceSummary } from "../api";
import CardTitle from "../components/CardTitle";
import CheckDetailsDialog from "../components/CheckDetailsDialog";
import DataTable from "../components/DataTable";
import { useStatsBar } from "../components/StatsBar";
import { useTableFilters } from "../components/TableFilters";
import { checkBadgeClass as badgeClass, checkStatusLabel as statusLabel, isMissed, needsReview } from "../utils/checks";
import { inDateRange, serializeRange } from "../utils/dates";

const FILTERS = ["ALL", "CHECK", "MATCH", "MISSED", "ERRORS"] as const;
type Filter = (typeof FILTERS)[number];

const FILTER_LABELS: Record<Filter, string> = {
  ALL: "All", CHECK: "Needs review", MATCH: "Match", MISSED: "Missed", ERRORS: "Errors",
};

export default function ChecksPage(props: { title: string; hint: string }) {
  const [rows, setRows] = useState<AdminCheckRow[]>([]);
  const [filter, setFilter] = useState<Filter>("ALL");
  const [error, setError] = useState<string | null>(null);
  const [details, setDetails] = useState<AdminCheckRow | null>(null);
  const [evidence, setEvidence] = useState<{ checkId: string; url: string } | null>(null);
  // Hostnames for the deviceId a check carries. Fetched once: a system is
  // renamed far too rarely to re-read it on every 20s refresh.
  const [devices, setDevices] = useState<DeviceSummary[]>([]);

  async function refresh() {
    try {
      const fresh = await api.listChecks(500);
      setRows(fresh);
      // Keep an open details popup on the fresh row (a review recorded elsewhere).
      setDetails((current) => (current ? (fresh.find((r) => r.checkId === current.checkId) ?? current) : current));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useEffect(() => {
    void refresh();
    void api.listDevices().then(setDevices).catch(() => setDevices([]));
    const interval = setInterval(() => void refresh(), 20_000);
    return () => clearInterval(interval);
  }, []);

  const visible = rows.filter((r) => {
    if (filter === "ALL") return true;
    if (filter === "MISSED") return isMissed(r.status);
    if (filter === "ERRORS") return r.status === "VERIFICATION_ERROR" || r.status === "NO_FACE";
    if (filter === "CHECK") return r.status === "CHECK" || r.status === "MULTIPLE_FACES";
    return r.status === filter;
  });

  const tableFilters = useTableFilters();

  // Taken from the rows rather than the fixed department list: a check belonging
  // to an employee on an older department still has to be findable here.
  const departments = [...new Set(rows.map((r) => r.employeeDepartment).filter(Boolean))]
    .sort() as string[];

  const stats = useStatsBar({
    dated: true,
    title: "Check stats",
    groupBy: { label: "Department", options: departments, allLabel: "All departments" },
    // Each tile opens its tab AND narrows the table to the period the number was
    // read for, so the rows on screen are exactly the ones the tile counted.
    compute: (range, department) => {
      const dated = range ? rows.filter((r) => inDateRange(r.requestedAt, range)) : rows;
      const set = department ? dated.filter((r) => r.employeeDepartment === department) : dated;
      const goTo = (f: Filter) => ({
        onClick: () => {
          setFilter(f);
          // The table ends up showing exactly the rows the tile counted, so the
          // department has to travel with the period, not just the dates.
          const narrow: Record<string, string> = {};
          if (range) narrow.when = serializeRange(range);
          if (department) narrow.department = department;
          if (Object.keys(narrow).length > 0) tableFilters.apply(narrow);
        },
        title: department
          ? `Open the ${FILTER_LABELS[f]} tab for ${department} in this period`
          : `Open the ${FILTER_LABELS[f]} tab for this period`,
      });
      return [
        { label: "Checks", value: set.length, ...goTo("ALL") },
        { label: "Needs review", value: set.filter(needsReview).length, tone: "warn" as const, ...goTo("CHECK") },
        { label: "Matches", value: set.filter((r) => r.status === "MATCH").length, tone: "ok" as const, ...goTo("MATCH") },
        { label: "Missed", value: set.filter((r) => isMissed(r.status)).length, tone: "bad" as const, ...goTo("MISSED") },
        { label: "Errors", value: set.filter((r) => r.status === "VERIFICATION_ERROR" || r.status === "NO_FACE").length, tone: "bad" as const, ...goTo("ERRORS") },
      ];
    },
  });

  async function showEvidence(row: AdminCheckRow) {
    try {
      setEvidence({ checkId: row.checkId, url: await api.loadEvidence(row.checkId) });
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <div className="toolbar">
        <CardTitle title={props.title} hint={props.hint} />
        <span className="toolbar-right">
          {tableFilters.control}
          {stats.controls}
          <span className="muted">Auto-refreshes every 20s</span>
        </span>
      </div>
      <div className="subbar">
        <div className="chips" role="tablist" aria-label="Quick filter">
          {FILTERS.map((f) => (
            <button
              key={f}
              role="tab"
              aria-selected={filter === f}
              className={`chip ${filter === f ? "active" : ""}`}
              onClick={() => setFilter(f)}
            >
              {FILTER_LABELS[f]}
            </button>
          ))}
        </div>
      </div>
      {stats.panel}

      {error && <p className="error-text">{error}</p>}

      <DataTable
        {...tableFilters.tableProps}
        rows={visible}
        rowKey={(row) => row.checkId}
        onRowClick={(row) => setDetails(row)}
        rowLabel={(row) => `Open the check for ${row.employeeName ?? row.employeeId.slice(0, 8)}`}
        emptyText="No checks match this filter."
        columns={[
          {
            key: "employee", header: "Employee",
            value: (row) => `${row.employeeName ?? row.employeeId.slice(0, 8)} ${row.employeeCode ?? ""}`.trim(),
            render: (row) => (
              <>{row.employeeName ?? row.employeeId.slice(0, 8)} <span className="muted">{row.employeeCode ?? ""}</span></>
            ),
            headerStyle: { width: 150 },
          },
          {
            key: "department", header: "Department", filter: "select",
            value: (row) => row.employeeDepartment ?? "—",
            cellStyle: { color: "var(--muted)" }, headerStyle: { width: 130 },
          },
          {
            key: "type", header: "Type", filter: "select", value: (row) => row.checkType,
            cellStyle: { color: "var(--muted)" }, headerStyle: { width: 120 },
          },
          {
            key: "status", header: "Status", filter: "select",
            value: (row) => statusLabel(row.status),
            render: (row) => (
              <>
                <span className={badgeClass(row.status)}>{statusLabel(row.status)}</span>
                {row.mismatchFlag && <span className="badge error" style={{ marginLeft: 4 }}>mismatch?</span>}
                {row.errorDetail && (
                  <div className="muted truncate" style={{ fontSize: 12 }} title={row.errorDetail}>
                    {row.errorDetail}
                  </div>
                )}
              </>
            ),
            cellStyle: { maxWidth: 300 }, headerStyle: { width: 300 },
          },
          {
            key: "similarity", header: "Similarity", filter: "number",
            value: (row) => row.similarityScore?.toFixed(1) ?? "—",
            number: (row) => row.similarityScore,
            headerStyle: { width: 128 },
          },
          {
            key: "liveness", header: "Liveness", filter: "number",
            value: (row) => row.livenessConfidence?.toFixed(1) ?? "—",
            number: (row) => row.livenessConfidence,
            headerStyle: { width: 128 },
          },
          {
            key: "review", header: "Review", filter: "select", headerStyle: { width: 120 },
            value: (row) => row.reviewResolution ?? (needsReview(row) ? "needs review" : "—"),
            render: (row) =>
              row.reviewResolution ? (
                <span className="badge neutral">{row.reviewResolution}</span>
              ) : needsReview(row) ? (
                <button className="action" onClick={() => setDetails(row)}>Review</button>
              ) : (
                <span className="muted">—</span>
              ),
          },
          {
            key: "evidence", header: "Evidence", filter: "select", headerStyle: { width: 110 },
            value: (row) => (row.hasEvidence ? "retained" : "not retained"),
            render: (row) =>
              row.hasEvidence
                ? <button className="action" onClick={() => void showEvidence(row)}>View</button>
                : <span className="muted">not retained</span>,
          },
          {
            key: "when", header: "When", filter: "date", date: (row) => row.requestedAt,
            value: (row) => new Date(row.requestedAt).toLocaleString(),
            render: (row) => <span title={row.checkId}>{new Date(row.requestedAt).toLocaleString()}</span>,
            headerStyle: { width: 250 },
          },
        ]}
      />

      {details && (
        <CheckDetailsDialog
          check={details}
          deviceHostname={devices.find((d) => d.id === details.deviceId)?.hostname ?? null}
          onClose={() => setDetails(null)}
          onReviewed={() => {
            setDetails(null);
            void refresh();
          }}
        />
      )}

      {evidence && (
        <div className="overlay" onClick={() => setEvidence(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ marginTop: 0 }}>Capture evidence</h3>
            <img src={evidence.url} alt="verification capture evidence" />
            <div className="row">
              <button className="action" onClick={() => setEvidence(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
