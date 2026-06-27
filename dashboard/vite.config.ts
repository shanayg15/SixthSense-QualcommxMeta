import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Visualization-only dashboard. Host on 127.0.0.1 by default; the MCP
// dashboard_status tool checks http://127.0.0.1:5173.
export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5173,
  },
});
