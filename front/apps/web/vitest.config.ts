import { defineConfig } from 'vitest/config'

// 브라우저 API가 필요 없는 로직만 테스트한다. 화면은 build·lint와 수동 E2E로 확인한다.
export default defineConfig({
  test: { environment: 'node', include: ['src/**/__tests__/**/*.test.ts'] },
})
