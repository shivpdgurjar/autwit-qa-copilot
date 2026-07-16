import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      // Same-origin in dev, so EventSource works without CORS. SSE needs the proxy
      // not to buffer: Vite's http-proxy streams by default, but the API must also
      // send no Content-Length, which SseEmitter handles.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
