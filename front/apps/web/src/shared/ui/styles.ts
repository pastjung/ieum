import type { CSSProperties } from 'react'
import { theme } from './theme'

const c = theme.color

export type ButtonVariant = 'primary' | 'outline' | 'mintOutline' | 'muted' | 'dangerOutline' | 'darkOutline'

const BUTTON_BASE: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 6,
  borderRadius: theme.radius.control,
  fontFamily: 'inherit',
  textDecoration: 'none',
  whiteSpace: 'nowrap',
  cursor: 'pointer',
  flexShrink: 0,
}

const VARIANTS: Record<ButtonVariant, CSSProperties> = {
  // 새 수업 개설·수업 진행: 민트 채움 + 아래 그림자
  primary: { border: 0, background: c.primary, color: c.onPrimary, fontWeight: 800, boxShadow: `0 3px 0 ${c.primaryStrong}` },
  // 취소·한 번 더 읽어주기
  outline: { border: `1.5px solid ${c.text}`, background: c.surface, color: c.text, fontWeight: 700 },
  // 수업 시작(예정 행)·다음 문제 선택
  mintOutline: { border: `1.5px solid ${c.primaryStrong}`, background: c.surface, color: c.primaryDark, fontWeight: 700 },
  // 회차 리포트(종료 행)·로그아웃
  muted: { border: `1.5px solid ${c.border}`, background: c.surface, color: c.textSub, fontWeight: 700 },
  // 03 수업 종료
  dangerOutline: { border: `1.5px solid ${c.live}`, background: 'transparent', color: c.live, fontWeight: 700 },
  // 03 어두운 바·마이크 카드 위 버튼
  darkOutline: { border: `1.5px solid ${c.darkLine}`, background: 'transparent', color: '#FFFFFF', fontWeight: 700 },
}

const SIZES: Record<'md' | 'sm', CSSProperties> = {
  md: { padding: '11px 18px', fontSize: 14 },
  sm: { padding: '9px 16px', fontSize: 13 },
}

/** Link와 button 모두에 쓰는 버튼 모양 */
export function buttonStyle(variant: ButtonVariant, size: 'md' | 'sm' = 'md'): CSSProperties {
  return { ...BUTTON_BASE, ...SIZES[size], ...VARIANTS[variant] }
}

/** 목(준비 중) 컨트롤: 와이어프레임 모양을 유지하고 누를 수 없다는 것만 커서로 알린다 */
export const inactive: CSSProperties = { cursor: 'default' }

/** 요청 중이라 잠시 누를 수 없는 실제 컨트롤 */
export const dimmed: CSSProperties = { opacity: 0.55, cursor: 'not-allowed' }

export const fieldWrap: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6 }

export const fieldLabel: CSSProperties = { fontSize: 13, fontWeight: 700, color: c.textSub }

export const fieldStyle: CSSProperties = {
  width: '100%',
  minHeight: 42,
  padding: '9px 14px',
  border: `1.5px solid ${c.border}`,
  borderRadius: theme.radius.control,
  fontSize: 14,
  fontWeight: 700,
  fontFamily: 'inherit',
  color: c.text,
  background: c.surface,
  boxSizing: 'border-box',
}

export const cardStyle: CSSProperties = {
  background: c.surface,
  border: `1.5px solid ${c.border}`,
  borderRadius: theme.radius.card,
}
