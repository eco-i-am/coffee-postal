import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In dev the UI runs on :5173 and forwards /api to the Java backend on :8080,
// so the browser sees one origin (cookies and CSRF just work).
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { "/api": "http://localhost:8080" } },
  test: { environment: "node" },
});
