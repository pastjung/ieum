import { Link } from 'react-router-dom'

export function HomePage() {
  return (
    <main style={{ padding: 32, fontFamily: 'sans-serif' }}>
      <h1>잇다 — 강사용 웹</h1>
      <p>점자 학습 지원 서비스 개발 환경이 준비되었습니다.</p>
      <ul>
        <li>
          <Link to="/signup">회원가입 (API 연동 예시)</Link>
        </li>
      </ul>
    </main>
  )
}
