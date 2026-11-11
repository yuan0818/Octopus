import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 后端地址可通过环境变量覆盖（默认与各服务 application.yaml 的端口一致）。
// 例：8080 被占用时 QUERY_API_TARGET=http://localhost:8085 npm run dev
const queryTarget = process.env.QUERY_API_TARGET || 'http://localhost:8080';
const reviewTarget = process.env.REVIEW_API_TARGET || 'http://localhost:8083';
const reconstructTarget = process.env.RECONSTRUCT_API_TARGET || 'http://localhost:8082';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/business-testing/api': {
        target: process.env.SCENARIO_API_TARGET || 'http://localhost:8086',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/business-testing\/api/, '/api'),
      },
      // Review-agent API calls — must use /review/api/ prefix so SPA routes like /review work
      '/review/api': {
        target: reviewTarget,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/review\/api/, '/api'),
      },
      // Scenario-runner
      '/scenarios/api': {
        target: process.env.SCENARIO_API_TARGET || 'http://localhost:8086',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/scenarios\/api/, '/api'),
      },
      // Generic /api → code-intelligence (MUST be last /api* rule)
      '/api': {
        target: queryTarget,
        changeOrigin: true,
      },
      // Reconstruct
      '/reconstruct': {
        target: reconstructTarget,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/reconstruct/, '/api'),
      },
    },
  },
});
