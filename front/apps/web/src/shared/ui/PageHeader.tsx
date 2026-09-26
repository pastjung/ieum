import { Fragment, type ReactNode } from 'react'
import { theme } from './theme'

interface PageHeaderProps {
  /** 마지막 항목이 현재 화면(민트 굵게) */
  crumbs: string[]
  title: string
  subtitle?: string
  actions?: ReactNode
}

/** 01a·02 공통: 브레드크럼 + h1(28/900) + 부제, 오른쪽 버튼 */
export function PageHeader({ crumbs, title, subtitle, actions }: PageHeaderProps) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', gap: 24 }}>
      <div>
        <nav
          aria-label="현재 위치"
          style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, color: theme.color.textMuted, marginBottom: 6 }}
        >
          {crumbs.map((crumb, i) => (
            <Fragment key={crumb}>
              {i > 0 && <span aria-hidden="true">›</span>}
              <span style={i === crumbs.length - 1 ? { color: theme.color.primaryDark, fontWeight: 700 } : undefined}>{crumb}</span>
            </Fragment>
          ))}
        </nav>
        <h1 style={{ margin: 0, fontSize: 28, fontWeight: 900, letterSpacing: '-.02em' }}>{title}</h1>
        {subtitle && <p style={{ margin: '4px 0 0', fontSize: 14, color: theme.color.textSub }}>{subtitle}</p>}
      </div>
      {actions && <div style={{ display: 'flex', gap: 12, flexShrink: 0 }}>{actions}</div>}
    </div>
  )
}
