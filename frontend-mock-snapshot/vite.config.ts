import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In live mode (VITE_LIVE=1) the app fetches same-origin paths (`/api/v1/...`)
// and the Vite dev server proxies them to the Spring Boot backend on :8080.
// Talking same-origin means the session cookie flows without any CORS setup on
// the backend (the e2e profile opens no browser origin), and credentials:
// "include" requests are not treated as cross-site. In mock mode nothing hits
// these paths, so the proxy is inert.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      "/test-support": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
