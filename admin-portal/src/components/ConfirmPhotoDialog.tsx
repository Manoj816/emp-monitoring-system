import { useEffect, useState } from "react";
import { api, type Employee } from "../api";

/**
 * Confirmation step before an ID/photo is replaced. Replacing is not a small
 * edit — it retires the reference every past check was judged against and makes
 * the employee enroll again — so the picked image is shown next to the one on
 * file and nothing is uploaded until it is confirmed.
 */
export default function ConfirmPhotoDialog(props: {
  employee: Employee;
  /** The image the administrator just picked (not uploaded yet). */
  photo: Blob;
  busy?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const { employee, photo } = props;
  const [current, setCurrent] = useState<string | null>(null);
  const [preview, setPreview] = useState<string | null>(null);

  useEffect(() => {
    const url = URL.createObjectURL(photo);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [photo]);

  useEffect(() => {
    if (!employee.hasReferenceImage) {
      return;
    }
    let cancelled = false;
    let created: string | undefined;
    api
      .loadReferenceImage(employee.id)
      .then((url) => {
        created = url;
        if (cancelled) URL.revokeObjectURL(url);
        else setCurrent(url);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
    };
  }, [employee]);

  return (
    <div
      className="overlay"
      onClick={(e) => {
        // Stop the click reaching a dialog underneath (the employee details popup).
        e.stopPropagation();
        if (!props.busy) props.onCancel();
      }}
    >
      <div className="modal wide" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>Replace ID / photo — {employee.fullName}</h3>
        <p className="muted" style={{ marginTop: 0 }}>
          Check the picture before replacing it. Every verification is compared against this image.
        </p>

        <div className="photo-row">
          <figure>
            {current ? (
              <img className="photo-preview" src={current} alt="ID or photo currently on file" />
            ) : (
              <div className="photo-placeholder">
                {employee.hasReferenceImage ? "Loading…" : "No ID/photo on file yet."}
              </div>
            )}
            <figcaption>Currently on file</figcaption>
          </figure>
          <figure>
            {preview && <img className="photo-preview" src={preview} alt="Newly picked ID or photo" />}
            <figcaption>New picture</figcaption>
          </figure>
        </div>

        <p className="notice" style={{ marginBottom: 0 }}>
          Replacing it retires the current picture and {employee.fullName}'s enrolled live scan. Every system of
          theirs runs the enrollment scan again at its next check, matched against this new image.
        </p>

        <div className="row">
          <button className="action" disabled={props.busy} onClick={props.onCancel}>Cancel</button>
          <button className="primary" disabled={props.busy} onClick={props.onConfirm}>
            {props.busy ? "Replacing…" : "Replace photo"}
          </button>
        </div>
      </div>
    </div>
  );
}
