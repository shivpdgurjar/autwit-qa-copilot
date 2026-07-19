import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Where the dev server proxies /api. Defaults to the host, which is what `npm run dev`
// wants; docker-compose sets it to http://api:8080 because `localhost` inside the UI
// container is the UI container.
const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Bind all interfaces so the port is reachable when published from a container.
    // Vite listens on localhost only by default, which inside a container means the
    // published port accepts the connection and nothing answers it.
    host: true,
    proxy: {
      // Same-origin in dev, so EventSource works without CORS. SSE needs the proxy
      // not to buffer: Vite's http-proxy streams by default, but the API must also
      // send no Content-Length, which SseEmitter handles.
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
});
