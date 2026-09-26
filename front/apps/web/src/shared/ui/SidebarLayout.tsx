import type { ReactNode } from 'react'
import { theme } from './theme'

interface SidebarLayoutProps {
  /** 사이드바 섹션 이름 (예: 내 강의, 개설 단계) */
  sidebarTitle: string
  sidebar: ReactNode
  children: ReactNode
  /** 본문 세로 간격. 01a 20, 02 22 */
  gap?: number
  maxWidth?: number
}

/** 01a·02 공통: 흰 좌측 사이드바(240px) + 본문(padding 32). AppShell의 Outlet 안에서 남은 높이를 채운다 */
export function SidebarLayout({ sidebarTitle, sidebar, children, gap = 20, maxWidth }: SidebarLayoutProps) {
  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
      <nav
        aria-label={sidebarTitle}
        style={{
          width: theme.layout.sidebarWidth,
          flexShrink: 0,
          padding: '24px 16px',
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
          background: theme.color.surface,
          borderRight: `1.5px solid ${theme.color.border}`,
        }}
      >
        <div style={{ padding: '0 12px 8px', fontSize: 11.5, fontWeight: 700, color: theme.color.textMuted, letterSpacing: '.06em' }}>
          {sidebarTitle}
        </div>
        {sidebar}
      </nav>
      <main
        style={{
          flex: 1,
          minWidth: 0,
          maxWidth,
          padding: theme.layout.pagePadding,
          display: 'flex',
          flexDirection: 'column',
          gap,
        }}
      >
        {children}
      </main>
    </div>
  )
}
