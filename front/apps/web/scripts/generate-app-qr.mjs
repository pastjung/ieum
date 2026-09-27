/**
 * 다운로드 페이지(/app)에 거는 QR 이미지 public/app-qr.svg 를 만든다. 주소가 바뀔 때만 다시 실행한다.
 *
 *   node front/apps/web/scripts/generate-app-qr.mjs
 *
 * QR 인코더를 web 의존성으로 들이지 않고, 워크스페이스에 이미 있는 toqr(Expo CLI가 개발 서버 QR에 쓰는 것.
 * node-linker=hoisted 라 front/node_modules/toqr)을 빌려 쓴다. 빌드 때 실행하지 않으므로 운영 이미지와 무관하다.
 * 결과는 결정적이다 — 같은 주소면 같은 파일이 나온다.
 */
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'

const PAGE_URL = 'https://i-eum.site/app'
const EC_LEVEL_H = 2 // toqr ECLevel: M=0, L=1, H=2, Q=3. 수업 화면에 띄워 멀리서 찍으므로 가장 높은 H
const QUIET_ZONE = 4 // 표준 여백 4칸

const require = createRequire(new URL('../../../package.json', import.meta.url))
const { toQR } = require('toqr')

const modules = toQR(PAGE_URL, EC_LEVEL_H) // 1 = 검은 칸, 행 우선
const size = Math.sqrt(modules.length) | 0
if (size * size !== modules.length) throw new Error(`QR 행렬이 정사각형이 아니다: ${modules.length}`)

let path = ''
for (let y = 0; y < size; y += 1) {
  for (let x = 0; x < size; x += 1) {
    if (modules[y * size + x]) path += `M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`
  }
}
const extent = size + QUIET_ZONE * 2
const svg =
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${extent} ${extent}" shape-rendering="crispEdges">` +
  `<!-- ${PAGE_URL} · scripts/generate-app-qr.mjs 로 생성 -->` +
  `<rect width="${extent}" height="${extent}" fill="#fff"/><path fill="#000" d="${path}"/></svg>\n`

writeFileSync(new URL('../public/app-qr.svg', import.meta.url), svg)
console.log(`${PAGE_URL} → public/app-qr.svg (${size}x${size}, 여백 ${QUIET_ZONE})`)
