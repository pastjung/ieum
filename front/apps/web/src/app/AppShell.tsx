import { Link, Outlet, useNavigate } from 'react-router-dom'
import { authApi } from '../shared/auth/authApi'
import { Logo } from '../shared/ui/Logo'
import { buttonStyle } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color

export function AppShell() {
  const navigate = useNavigate()
  const user = authApi.currentUser()

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        background: c.bg,
        color: c.text,
        fontFamily: theme.font.sans,
      }}
    >
      <header
        style={{
          height: theme.layout.headerHeight,
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 32px',
          background: c.surface,
          borderBottom: `1.5px solid ${c.border}`,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 40 }}>
          <Link to="/lectures" style={{ textDecoration: 'none' }}>
            <Logo />
          </Link>
          {/* 대시보드·수강생·문제 은행은 MVP 범위 밖이라 비활성 표시만 한다 */}
          <nav aria-label="주 메뉴" style={{ display: 'flex', gap: 26, fontSize: 14, fontWeight: 500, color: c.textSub }}>
            <span aria-disabled="true">대시보드</span>
            <Link
              to="/lectures"
              aria-current="page"
              style={{ color: c.primaryDark, fontWeight: 800, boxShadow: `0 3px 0 ${c.primary}`, paddingBottom: 2, textDecoration: 'none' }}
            >
              교육
            </Link>
            <span aria-disabled="true">수강생</span>
            <span aria-disabled="true">문제 은행</span>
          </nav>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          {/* 목: 검색은 MVP 범위 밖 — 와이어프레임 자리만 둔다 */}
          <div
            aria-hidden="true"
            style={{ padding: '8px 16px', borderRadius: theme.radius.control, border: `1.5px solid ${c.border}`, fontSize: 13, color: c.textSub, background: c.surface }}
          >
            학생·수업 검색
          </div>
          <span
            title={user?.username}
            style={{
              width: 34,
              height: 34,
              borderRadius: '50%',
              background: c.primary,
              color: c.onPrimary,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 13,
              fontWeight: 700,
            }}
          >
            {user?.username.charAt(0).toUpperCase()}
          </span>
          <button
            type="button"
            onClick={() => {
              authApi.signOut()
              navigate('/login', { replace: true })
            }}
            style={{ ...buttonStyle('muted', 'sm'), padding: '8px 12px' }}
          >
            로그아웃
          </button>
        </div>
      </header>
      <Outlet />
    </div>
  )
}
