import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 개발 중 CORS 없이 백엔드 호출: /api/v1/... → localhost:8080
      // 컨테이너에서 실행할 때는 VITE_PROXY_TARGET으로 대상 변경 (루트 docker-compose.yml 참고)
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
