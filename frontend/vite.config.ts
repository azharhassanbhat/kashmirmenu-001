import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

const backend = process.env.BACKEND_URL ?? 'http://localhost:8080';

// In dev the SPA and API share an origin via this proxy, so no CORS setup is needed.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // sockjs-client expects a Node-style global
  define: { global: 'globalThis' },
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/ws': { target: backend, ws: true },
    },
  },
  test: {
    environment: 'node',
  },
});
