import { theme } from './theme'

const dot = { width: 5, height: 5, borderRadius: '50%', background: theme.color.primary }

/** 와이어프레임 로고: I + 세로 두 점 + EUM + 민트 "강사" 알약 */
export function Logo() {
  return (
    <span
      role="img"
      aria-label="I:EUM 강사"
      style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 21, fontWeight: 900, letterSpacing: '-.02em', color: theme.color.text }}
    >
      I
      <span style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
        <span style={dot} />
        <span style={dot} />
      </span>
      EUM
      <span
        style={{
          marginLeft: 8,
          padding: '3px 9px',
          borderRadius: theme.radius.pill,
          background: theme.color.primarySoft,
          fontSize: 11.5,
          fontWeight: 700,
          color: theme.color.primaryDark,
          letterSpacing: 0,
        }}
      >
        강사
      </span>
    </span>
  )
}
