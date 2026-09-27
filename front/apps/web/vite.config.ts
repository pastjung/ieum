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
      // 앱 배포 파일(운영에선 호스트 nginx가 web보다 먼저 제공). 개발 서버에는 없어 SPA 폴백 HTML이
      // 오므로 운영 서버의 공개 파일로 넘긴다. 로컬 파일로 보려면 VITE_APP_RELEASE_TARGET=http://localhost:8765
      // 처럼 정적 서버 주소를 준다(README). /app 페이지 자체는 접두어가 달라 여기 걸리지 않는다
      '/app/latest.json': {
        target: process.env.VITE_APP_RELEASE_TARGET ?? 'https://i-eum.site',
        changeOrigin: true,
      },
      '/app/ieum': {
        target: process.env.VITE_APP_RELEASE_TARGET ?? 'https://i-eum.site',
        changeOrigin: true,
      },
    },
  },
});
