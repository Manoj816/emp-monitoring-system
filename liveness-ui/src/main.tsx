import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import CapturePage from "./CapturePage";

// Two pages, chosen by path: /capture (interactive photo) and / (Face Liveness).
const isCapture = window.location.pathname.replace(/\/+$/, "").endsWith("/capture");

createRoot(document.getElementById("root")!).render(
  <StrictMode>{isCapture ? <CapturePage /> : <App />}</StrictMode>,
);
