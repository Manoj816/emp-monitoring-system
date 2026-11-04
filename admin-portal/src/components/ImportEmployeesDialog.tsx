import { useRef, useState } from "react";
import { api, DEPARTMENTS, type ImportReport } from "../api";

/**
 * "Import employees" popup: one CSV creates many records at once, instead of
 * the Add-employee form once per person.
 *
 * A CSV holds text, so it cannot carry the ID/photo itself. A row names its
 * picture in the photoFile column and the pictures are chosen here alongside the
 * file; the server pairs them by name. A row may leave photoFile empty and the
 * employee is still created — they show as "ID / photo: missing" until somebody
 * uploads one. Deferring the photo is the point of the bulk path; the
 * single-employee form still demands one, where nothing is gained by waiting.
 */

/** Offered as a download, and the exact shape docs/employees-import-template.csv has. */
const TEMPLATE = [
  "employeeCode,fullName,email,department,photoFile",
  "E-1001,Asha Menon,asha.menon@example.com,Engineering,E-1001.jpg",
].join("\r\n") + "\r\n";

function downloadTemplate() {
  // The BOM is what makes Excel open it as UTF-8 rather than the system codepage,
  // so an accented name survives a round trip through the spreadsheet.
  const blob = new Blob(["﻿" + TEMPLATE], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "employees-import-template.csv";
  link.click();
  URL.revokeObjectURL(url);
}

export default function ImportEmployeesDialog(props: { onClose: () => void; onImported: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [photos, setPhotos] = useState<File[]>([]);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const csvInputRef = useRef<HTMLInputElement>(null);
  const photoInputRef = useRef<HTMLInputElement>(null);

  function chooseCsv(e: React.ChangeEvent<HTMLInputElement>) {
    const chosen = e.target.files?.[0];
    e.target.value = "";
    if (!chosen) return;
    setFile(chosen);
    setReport(null);
    setError(null);
  }

  function choosePhotos(e: React.ChangeEvent<HTMLInputElement>) {
    const chosen = Array.from(e.target.files ?? []);
    e.target.value = "";
    if (chosen.length === 0) return;
    // Added to, not replaced: the pictures may well sit in more than one folder.
    setPhotos((current) => {
      const byName = new Map(current.map((p) => [p.name.toLowerCase(), p]));
      for (const photo of chosen) byName.set(photo.name.toLowerCase(), photo);
      return [...byName.values()];
    });
    setReport(null);
    setError(null);
  }

  async function submit() {
    if (!file) return;
    setBusy(true);
    setError(null);
    setReport(null);
    try {
      const result = await api.importEmployees(file, photos);
      setReport(result);
      if (result.imported > 0) props.onImported();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const imported = report !== null && report.imported > 0;

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>Import employees from a CSV</h3>

        <p className="muted" style={{ marginTop: 0, fontSize: 13 }}>
          The first row names the columns: <code>employeeCode</code>, <code>fullName</code>,{" "}
          <code>email</code>, <code>department</code>, and optionally <code>photoFile</code>.
          Department has to be one of {DEPARTMENTS.join(", ")}.
        </p>
        <p className="muted" style={{ marginTop: -6, fontSize: 13 }}>
          If any row has a problem, nothing is imported and every bad row is listed below — so one
          round of corrections is enough.
        </p>

        <div className="import-pick">
          <div className="import-row">
            <span className="import-label">CSV file *</span>
            <button type="button" className="action" disabled={busy} onClick={() => csvInputRef.current?.click()}>
              Choose CSV…
            </button>
            <span className={file ? "" : "muted"}>{file ? file.name : "No file chosen"}</span>
            <button type="button" className="link import-template" onClick={downloadTemplate}>
              Download template
            </button>
          </div>

          <div className="import-row">
            <span className="import-label">ID / photos</span>
            <button type="button" className="action" disabled={busy} onClick={() => photoInputRef.current?.click()}>
              Choose pictures…
            </button>
            <span className={photos.length ? "" : "muted"}>
              {photos.length === 0 ? "None chosen" : `${photos.length} chosen`}
            </span>
            {photos.length > 0 && (
              <button type="button" className="link" disabled={busy} onClick={() => setPhotos([])}>
                Clear
              </button>
            )}
          </div>

          <p className="import-hint">
            JPEG or PNG only — a face is compared against this picture, so a PDF or a scanned
            document cannot stand in for it. A row that leaves <code>photoFile</code> empty still
            creates the employee; they are listed as “ID / photo: missing” and cannot be verified
            until a picture is uploaded for them.
          </p>
        </div>

        {error && <p className="error-text">{error}</p>}

        {report && imported && (
          <p className="import-done">
            {report.imported} employee{report.imported === 1 ? "" : "s"} imported
            {report.withPhoto > 0
              ? `, ${report.withPhoto} with an ID/photo.`
              : ". None of them has an ID/photo yet."}
          </p>
        )}

        {report && !imported && (
          <div className="import-problems">
            <p className="error-text" style={{ margin: "0 0 8px" }}>
              Nothing was imported — {report.problemRows} of {report.rows} row
              {report.rows === 1 ? "" : "s"} need fixing.
            </p>
            <table>
              <thead>
                <tr><th>Row</th><th>Code</th><th>What is wrong</th></tr>
              </thead>
              <tbody>
                {report.problems.map((problem) => (
                  <tr key={problem.row}>
                    <td>{problem.row}</td>
                    <td>{problem.employeeCode || "—"}</td>
                    <td>{problem.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {report.problemRows > report.problems.length && (
              <p className="muted" style={{ margin: "8px 0 0", fontSize: 12 }}>
                …and {report.problemRows - report.problems.length} more.
              </p>
            )}
          </div>
        )}

        <div className="row">
          <button type="button" className="action" disabled={busy} onClick={props.onClose}>
            {imported ? "Close" : "Cancel"}
          </button>
          {imported ? (
            // Not a second Import: pressing it again would re-send the file that has
            // just landed, and every row would come back as "already exists".
            <button
              type="button"
              className="primary"
              onClick={() => {
                setFile(null);
                setPhotos([]);
                setReport(null);
              }}
            >
              Import another file
            </button>
          ) : (
            <button type="button" className="primary" disabled={!file || busy} onClick={() => void submit()}>
              {busy ? "Importing…" : "Import"}
            </button>
          )}
        </div>

        <input ref={csvInputRef} type="file" accept=".csv,text/csv" hidden onChange={chooseCsv} />
        <input
          ref={photoInputRef}
          type="file"
          accept="image/jpeg,image/png"
          multiple
          hidden
          onChange={choosePhotos}
        />
      </div>
    </div>
  );
}
