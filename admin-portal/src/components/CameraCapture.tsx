import { useEffect, useRef, useState } from "react";

/**
 * Webcam capture for reference-image enrollment (PRD Requirement 2: capture via
 * webcam or upload). Captures one JPEG frame via getUserMedia + canvas.
 */
export default function CameraCapture(props: {
  onCapture: (image: Blob) => void;
  onCancel: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    navigator.mediaDevices
      .getUserMedia({ video: { width: 640, height: 480 } })
      .then((stream) => {
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        streamRef.current = stream;
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
        }
      })
      .catch((e) => setError(`Camera unavailable: ${e.message}`));
    return () => {
      cancelled = true;
      streamRef.current?.getTracks().forEach((t) => t.stop());
    };
  }, []);

  function capture() {
    const video = videoRef.current;
    if (!video) return;
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth || 640;
    canvas.height = video.videoHeight || 480;
    canvas.getContext("2d")!.drawImage(video, 0, 0);
    canvas.toBlob(
      (blob) => {
        if (blob) props.onCapture(blob);
      },
      "image/jpeg",
      0.92,
    );
  }

  return (
    <div className="overlay">
      <div className="modal">
        <h3 style={{ marginTop: 0 }}>Capture reference image</h3>
        {error ? <p className="error-text">{error}</p> : <video ref={videoRef} autoPlay playsInline muted />}
        <div className="row">
          <button className="action" onClick={props.onCancel}>Cancel</button>
          <button className="primary" onClick={capture} disabled={!!error}>Capture</button>
        </div>
      </div>
    </div>
  );
}
