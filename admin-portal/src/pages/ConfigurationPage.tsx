import { useEffect, useMemo, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";
import CardTitle from "../components/CardTitle";
import { useFreshOnActive } from "../useFreshOnActive";

const TABS = ["window", "roster"] as const;
type Tab = (typeof TABS)[number];

const TAB_LABELS: Record<Tab, string> = {
  window: "Daily check window",
  roster: "Daily-check roster",
};

const PAGE_SIZES = [10, 25, 50, 100];

/**
 * The Configuration tab: the admin controls for how checks run, split into the
 * same chip tabs the checks page uses — the daily check window (when a day's
 * random check can land) and the daily-check roster (who gets a daily check at
 * all). The roster is the same searchable pick list as "Send a check", with
 * client-side pagination so ten thousand rows never render at once ("Select
 * all" selects everything that matches the filter, across pages, which is what
 * a department-wide change means). Someone off the roster is still enrolled
 * and still answers a sent check; they are simply not checked automatically
 * every day. Running agents pick up every change within a minute.
 *
 * Scale note, deliberate for now: the employee list itself still loads in one
 * request, like everywhere else in the portal. At a truly large employee base
 * the list endpoints should grow server-side paging/search before this page
 * does anything cleverer.
 */
export default function ConfigurationPage(props: { title: string; hint: string; active: boolean }) {
  const [tab, setTab] = useState<Tab>("window");
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Roster list controls.
  const [search, setSearch] = useState("");
  const [department, setDepartment] = useState("");
  // "ACTIVE" by default: only active employees can be checked, so the others are
  // reference rows (shown with the switch disabled) rather than the default view.
  const [statusFilter, setStatusFilter] = useState("ACTIVE");
  const [dailyFilter, setDailyFilter] = useState("");
  const [enrollFilter, setEnrollFilter] = useState("");
  const [systemFilter, setSystemFilter] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);

  // The check window form. The server may say "12:00" or "12:00:00"; the time
  // inputs want HH:mm.
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [savedWindow, setSavedWindow] = useState<{ start: string; end: string } | null>(null);
  const [windowLoaded, setWindowLoaded] = useState(false);
  const [savingWindow, setSavingWindow] = useState(false);

  async function refresh() {
    try {
      const [emps, devs] = await Promise.all([api.listEmployees(), api.listDevices()]);
      setEmployees(emps);
      setDevices(devs);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoaded(true);
    }
  }

  useFreshOnActive(props.active, () => void refresh());

  // The window is read once; this page is where it changes, and saving updates it.
  useEffect(() => {
    api
      .getCheckWindow()
      .then((w) => {
        const s = w.workWindowStart.slice(0, 5);
        const e = w.workWindowEnd.slice(0, 5);
        setStart(s);
        setEnd(e);
        setSavedWindow({ start: s, end: e });
        setWindowLoaded(true);
      })
      .catch((e) => {
        setError((e as Error).message);
        setWindowLoaded(true);
      });
  }, []);

  async function saveWindow() {
    if (!start || !end) {
      setError("Both times are required.");
      return;
    }
    if (end <= start) {
      setError("The window must end after it starts.");
      return;
    }
    setSavingWindow(true);
    setError(null);
    try {
      const saved = await api.updateCheckWindow(start, end);
      const s = saved.workWindowStart.slice(0, 5);
      const e = saved.workWindowEnd.slice(0, 5);
      setSavedWindow({ start: s, end: e });
      setNotice(`Check window set to ${s}–${e}. Running agents pick it up within a minute.`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSavingWindow(false);
    }
  }

  // ---- roster ---------------------------------------------------------------

  /** Only ACTIVE employees are on (or off) the roster; others cannot be checked at all. */
  const active = useMemo(() => employees.filter((e) => e.status === "ACTIVE"), [employees]);
  const onRoster = active.filter((e) => e.dailyChecks);

  const departments = useMemo(
    () => [...new Set(active.map((e) => e.department).filter(Boolean))].sort() as string[],
    [active],
  );

  /** Whether an active system is assigned to the employee (what a check needs). */
  function hasSystem(e: Employee): boolean {
    return devices.some((d) => d.employeeId === e.id && d.status === "ACTIVE");
  }

  const filtersDirty =
    search !== "" || department !== "" || statusFilter !== "ACTIVE" ||
    dailyFilter !== "" || enrollFilter !== "" || systemFilter !== "";

  function clearFilters() {
    setSearch("");
    setDepartment("");
    setStatusFilter("ACTIVE");
    setDailyFilter("");
    setEnrollFilter("");
    setSystemFilter("");
  }

  const q = search.trim().toLowerCase();
  const matching = employees
    .filter((e) => !statusFilter || e.status === statusFilter)
    .filter((e) => !department || e.department === department)
    .filter((e) => !dailyFilter || (dailyFilter === "on") === e.dailyChecks)
    .filter((e) => !enrollFilter || (enrollFilter === "done") === e.hasLiveEnrollment)
    .filter((e) => !systemFilter || (systemFilter === "assigned") === hasSystem(e))
    .filter(
      (e) =>
        !q ||
        e.fullName.toLowerCase().includes(q) ||
        e.employeeCode.toLowerCase().includes(q) ||
        e.email.toLowerCase().includes(q),
    )
    .sort((a, b) => a.fullName.localeCompare(b.fullName));

  /** The matches whose switch means anything — what "Select all" selects. */
  const selectable = matching.filter((e) => e.status === "ACTIVE");

  // A new filter starts from page 1 — page 7 of a different list means nothing.
  useEffect(() => setPage(1), [search, department, statusFilter, dailyFilter, enrollFilter, systemFilter, pageSize]);

  const pageCount = Math.max(1, Math.ceil(matching.length / pageSize));
  const safePage = Math.min(page, pageCount);
  const pageStart = (safePage - 1) * pageSize;
  const pageRows = matching.slice(pageStart, pageStart + pageSize);

  /** "Select all" means every ACTIVE match — across pages — because that is what
      a department-wide change is; a suspended row's switch means nothing. */
  const allMatchingSelected = selectable.length > 0 && selectable.every((e) => selected.has(e.id));

  function toggleSelected(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAllMatching() {
    setSelected((prev) => {
      const next = new Set(prev);
      if (allMatchingSelected) selectable.forEach((e) => next.delete(e.id));
      else selectable.forEach((e) => next.add(e.id));
      return next;
    });
  }

  /** Flip one employee from the switch on their row. */
  async function toggleOne(emp: Employee) {
    setBusy(true);
    setError(null);
    try {
      const updated = await api.setDailyChecks(emp.id, !emp.dailyChecks);
      setEmployees((prev) => prev.map((e) => (e.id === updated.id ? updated : e)));
      setNotice(
        updated.dailyChecks
          ? `${updated.fullName} is back on the daily-check roster.`
          : `${updated.fullName} no longer gets the daily check. They stay enrolled, and “Send a check” still reaches them.`,
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  /** The selection in bulk — a department filter and “select all” make this department-wide. */
  async function setForSelected(enabled: boolean) {
    setBusy(true);
    setError(null);
    try {
      const r = await api.setDailyChecksBulk({ enabled, employeeIds: [...selected] });
      setNotice(
        `Daily check turned ${enabled ? "on" : "off"} for ${r.updated} of ${r.matched} selected employee${r.matched === 1 ? "" : "s"}` +
          (r.updated < r.matched ? " (the rest were already set that way)." : "."),
      );
      setSelected(new Set());
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="toolbar">
        <CardTitle title={props.title} hint={props.hint} />
      </div>

      <div className="subbar">
        <div className="chips" role="tablist" aria-label="Configuration sections">
          {TABS.map((t) => (
            <button
              key={t}
              role="tab"
              aria-selected={tab === t}
              className={`chip ${tab === t ? "active" : ""}`}
              onClick={() => setTab(t)}
            >
              {TAB_LABELS[t]}
            </button>
          ))}
        </div>
      </div>

      {notice && <p className="notice">{notice}</p>}
      {error && <p className="error-text">{error}</p>}

      {tab === "window" && (
        <section className="config-card">
          <div className="config-head">
            <h3>Daily check window</h3>
            {savedWindow && (
              <span className="badge neutral">
                current: {savedWindow.start}–{savedWindow.end}
              </span>
            )}
          </div>
          <p className="help">
            Each day's check opens at a random time inside these hours (the employee's local time), so
            nobody can plan around it. A day whose check has not run yet is re-planned inside the new
            window; a day already checked stays checked.
          </p>
          {!windowLoaded ? (
            <p className="muted">Loading…</p>
          ) : (
            <div className="config-window">
              <label>
                From
                <input type="time" value={start} disabled={savingWindow} onChange={(e) => setStart(e.target.value)} />
              </label>
              <label>
                To
                <input type="time" value={end} disabled={savingWindow} onChange={(e) => setEnd(e.target.value)} />
              </label>
              <button
                className="primary"
                disabled={savingWindow || (savedWindow?.start === start && savedWindow?.end === end)}
                onClick={() => void saveWindow()}
              >
                {savingWindow ? "Saving…" : "Save window"}
              </button>
              {savedWindow && (savedWindow.start !== start || savedWindow.end !== end) && (
                <button
                  className="action"
                  disabled={savingWindow}
                  title={`Back to the saved window, ${savedWindow.start}–${savedWindow.end}`}
                  onClick={() => {
                    setStart(savedWindow.start);
                    setEnd(savedWindow.end);
                    setError(null);
                  }}
                >
                  Discard changes
                </button>
              )}
            </div>
          )}
        </section>
      )}

      {tab === "roster" && (
        <section className="config-card">
          <div className="config-head">
            <h3>Daily-check roster</h3>
            <span className="badge neutral">
              {onRoster.length} of {active.length} active employees on the roster
            </span>
          </div>
          <p className="help">
            Who gets the day's random check. Turning someone off stops only the automatic daily check —
            they stay enrolled, “Send a check” still reaches them, and days without a check are not
            counted against them. For a whole department: filter to it, “Select all matching”, then turn
            the daily check off (or on) for the selection.
          </p>

          <div className="pick-controls">
            <input
              placeholder="Search by name, code or email…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <select
              value={department}
              onChange={(e) => setDepartment(e.target.value)}
              aria-label="Filter by department"
            >
              <option value="">All departments</option>
              {departments.map((d) => (
                <option key={d} value={d}>{d}</option>
              ))}
            </select>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              aria-label="Filter by employee status"
            >
              <option value="ACTIVE">Active</option>
              <option value="OFFBOARDED">Offboarded</option>
              <option value="">All statuses</option>
            </select>
            <select
              value={dailyFilter}
              onChange={(e) => setDailyFilter(e.target.value)}
              aria-label="Filter by daily check"
            >
              <option value="">Daily: all</option>
              <option value="on">Daily: on</option>
              <option value="off">Daily: off</option>
            </select>
            <select
              value={enrollFilter}
              onChange={(e) => setEnrollFilter(e.target.value)}
              aria-label="Filter by enrollment"
            >
              <option value="">Enrollment: all</option>
              <option value="done">Enrolled</option>
              <option value="pending">Pending first scan</option>
            </select>
            <select
              value={systemFilter}
              onChange={(e) => setSystemFilter(e.target.value)}
              aria-label="Filter by assigned system"
            >
              <option value="">System: all</option>
              <option value="assigned">System assigned</option>
              <option value="none">No system</option>
            </select>
            {filtersDirty && (
              <button className="action" onClick={clearFilters} title="Back to the default view">
                Clear filters
              </button>
            )}
          </div>

          <div className="pick-toolbar">
            <label>
              <input
                type="checkbox"
                checked={allMatchingSelected}
                disabled={busy || selectable.length === 0}
                onChange={toggleAllMatching}
              />{" "}
              Select all {selectable.length} matching
            </label>
            <span className="spacer" />
            <span className="muted">{selected.size} selected</span>
            {selected.size > 0 && (
              <>
                <button className="action" disabled={busy} onClick={() => void setForSelected(true)}>
                  Daily check on
                </button>
                <button className="action" disabled={busy} onClick={() => void setForSelected(false)}>
                  Daily check off
                </button>
                <button className="action" disabled={busy} onClick={() => setSelected(new Set())}>
                  Clear
                </button>
              </>
            )}
          </div>

          <ul className="pick-list" role="listbox" aria-label="Employees" aria-multiselectable="true">
            {!loaded && <li className="pick-empty">Loading…</li>}
            {loaded && pageRows.length === 0 && (
              <li className="pick-empty">
                {employees.length === 0
                  ? "No employees yet — create one on the Employees page first."
                  : "No employees match these filters."}
              </li>
            )}
            {pageRows.map((e) => {
              const isSelected = selected.has(e.id);
              const inert = e.status !== "ACTIVE";
              return (
                <li key={e.id}>
                  <div className={`pick-item pick-row ${isSelected ? "selected" : ""}`}>
                    <button
                      type="button"
                      className="pick-row-main"
                      role="option"
                      aria-selected={isSelected}
                      disabled={busy || inert}
                      title={inert ? "Only active employees are checked" : undefined}
                      onClick={() => toggleSelected(e.id)}
                    >
                      <input type="checkbox" checked={isSelected} readOnly tabIndex={-1} disabled={inert} />
                      <span className="avatar">{e.fullName.trim().charAt(0).toUpperCase() || "?"}</span>
                      <span className="pick-main">
                        <span className="pick-name">{e.fullName}</span>
                        <span className="pick-sub">{e.employeeCode} · {e.email}</span>
                      </span>
                      {inert && <span className="badge error">{e.status.toLowerCase()}</span>}
                      {!e.hasLiveEnrollment && e.status === "ACTIVE" && (
                        <span className="badge neutral" title="Their first live scan has not matched the ID/photo yet">
                          pending first scan
                        </span>
                      )}
                      {e.status === "ACTIVE" && !hasSystem(e) && (
                        <span className="badge check" title="No active system is assigned, so no check can reach them">
                          no system
                        </span>
                      )}
                      <span className="badge neutral">{e.department ?? "No department"}</span>
                    </button>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={e.dailyChecks}
                      aria-label={`Daily check for ${e.fullName}`}
                      className={`switch ${e.dailyChecks ? "on" : ""}`}
                      disabled={busy || inert}
                      title={
                        inert ? "Only active employees are checked" :
                        e.dailyChecks
                          ? "Daily check is on — click to stop the automatic daily check for them"
                          : "Daily check is off — click to put them back on the daily-check roster"
                      }
                      onClick={() => void toggleOne(e)}
                    >
                      <span className="switch-knob" />
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>

          <div className="table-footer">
            <span className="muted">
              {matching.length === 0
                ? "0 employees"
                : `Showing ${pageStart + 1}–${Math.min(pageStart + pageSize, matching.length)} of ${matching.length}`}
              {matching.length !== active.length && ` (filtered from ${active.length})`}
            </span>
            <span className="pager">
              <label className="muted">
                Per page{" "}
                <select value={pageSize} onChange={(e) => setPageSize(Number(e.target.value))}>
                  {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </label>
              <button className="action" disabled={safePage <= 1} onClick={() => setPage(safePage - 1)}>‹ Prev</button>
              <span className="muted">Page {safePage} of {pageCount}</span>
              <button className="action" disabled={safePage >= pageCount} onClick={() => setPage(safePage + 1)}>Next ›</button>
            </span>
          </div>
        </section>
      )}
    </>
  );
}
