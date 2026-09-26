import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { authApi } from '../shared/auth/authApi'
import { errorMessage } from '../shared/ui/errorMessage'
import { Logo } from '../shared/ui/Logo'
import { buttonStyle, cardStyle, dimmed, fieldLabel, fieldStyle, fieldWrap } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const expired = new URLSearchParams(location.search).get('expired') === '1'

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setPending(true)
    setError(null)
    try {
      await authApi.signIn(String(form.get('username') ?? ''), String(form.get('password') ?? ''))
      navigate('/lectures', { replace: true })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        background: theme.color.canvas,
        fontFamily: theme.font.sans,
        color: theme.color.text,
      }}
    >
      <form
        onSubmit={handleSubmit}
        style={{
          ...cardStyle,
          width: 400,
          maxWidth: 'calc(100vw - 32px)',
          boxSizing: 'border-box',
          padding: 32,
          display: 'flex',
          flexDirection: 'column',
          gap: 18,
        }}
      >
        <Logo />
        <h1 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>강사 로그인</h1>
        {expired && (
          <p role="status" style={{ margin: 0, fontSize: 13, color: theme.color.textSub }}>
            로그인이 만료됐어요. 다시 로그인해 주세요.
          </p>
        )}
        <label style={fieldWrap}>
          <span style={fieldLabel}>아이디</span>
          <input name="username" autoComplete="username" required style={fieldStyle} />
        </label>
        <label style={fieldWrap}>
          <span style={fieldLabel}>비밀번호</span>
          <input name="password" type="password" autoComplete="current-password" required style={fieldStyle} />
        </label>
        {error && (
          <p role="alert" style={{ margin: 0, fontSize: 13, fontWeight: 700, color: theme.color.danger }}>
            {error}
          </p>
        )}
        <button type="submit" disabled={pending} style={{ ...buttonStyle('primary'), width: '100%', ...(pending ? dimmed : {}) }}>
          {pending ? '로그인 중…' : '로그인'}
        </button>
      </form>
    </main>
  )
}
