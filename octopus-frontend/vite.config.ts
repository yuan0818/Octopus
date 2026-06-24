import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      // Review-agent API calls — must use /review/api/ prefix so SPA routes like /review work
      '/review/api': {
        target: 'http://localhost:8083',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/review\/api/, '/api'),
      },
      // Generic /api → code-intelligence (MUST be last /api* rule)
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Reconstruct
      '/reconstruct': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/reconstruct/, '/api'),
      },
    },
  },
});
