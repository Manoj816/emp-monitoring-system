import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // Matches the backend's default liveness-ui-url.
    port: 5174,
    proxy: {
      "/api": "http://localhost:8082",
    },
  },
});
