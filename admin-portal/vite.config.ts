import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // Local dev: forward API calls to the Spring Boot backend.
      "/api": "http://empverify-alb-95474567.us-east-1.elb.amazonaws.com",
    },
  },
});
