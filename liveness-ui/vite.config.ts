import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // Matches the backend's default liveness-ui-url.
    port: 5175,
    strictPort: true,
    proxy: {
      // Demo: proxy API calls to the deployed backend (server-side, so no CORS).
      // Switch back to http://localhost:8082 to run against a local backend.
      "/api": "http://empverify-alb-95474567.us-east-1.elb.amazonaws.com",
    },
  },
});
