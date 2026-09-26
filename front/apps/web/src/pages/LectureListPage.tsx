import { useEffect, useState, type CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { lectureApi } from '../domains/lecture/api/lectureApi'
import type { InstructorLecture, LectureStatus } from '../domains/lecture/model/types'
import { errorMessage } from '../shared/ui/errorMessage'
import { PageHeader } from '../shared/ui/PageHeader'
import { SidebarLayout } from '../shared/ui/SidebarLayout'
import { buttonStyle, inactive } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color

type Filter = 'ALL' | LectureStatus

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'ALL', label: '전체' },
  { key: 'OPEN', label: '진행 중' },
  { key: 'SCHEDULED', label: '예정' },
  { key: 'CLOSED', label: '종료' },
]

const STATUS_ORDER: Record<LectureStatus, number> = { OPEN: 0, SCHEDULED: 1, CLOSED: 2 }

const STATUS_LABEL: Record<LectureStatus, string> = { SCHEDULED: '예정', OPEN: '진행 중', CLOSED: '종료' }

const PILL_BASE: CSSProperties = { fontSize: 11.5, fontWeight: 700, borderRadius: theme.radius.pill, padding: '2px 10px', flexShrink: 0 }

const PILL: Record<LectureStatus, CSSProperties> = {
  OPEN: { ...PILL_BASE, padding: '3px 10px', fontWeight: 800, background: c.primary, color: c.onPrimary },
  SCHEDULED: { ...PILL_BASE, border: `1.5px solid ${c.textSub}`, color: c.textSub },
  CLOSED: { ...PILL_BASE, background: c.border, color: c.textSub },
}

const ROW: CSSProperties = {
  borderRadius: theme.radius.card,
  padding: '18px 22px',
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: 24,
}

const ROW_BODY: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 5, minWidth: 0 }

const ROW_TITLE_LINE: CSSProperties = { display: 'flex', alignItems: 'center', gap: 10 }

const CHIP: CSSProperties = {
  padding: '9px 16px',
  borderRadius: theme.radius.control,
  border: `1.5px solid ${c.border}`,
  background: c.surface,
  color: c.textSub,
  fontSize: 13,
  fontFamily: 'inherit',
  cursor: 'pointer',
}

const CHIP_ACTIVE: CSSProperties = { ...CHIP, border: `1.5px solid ${c.text}`, background: c.text, color: '#FFFFFF', fontWeight: 700 }

function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
}

function day(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' })
}

function Code({ value }: { value: string }) {
  return <span style={{ fontFamily: theme.font.mono, fontWeight: 700 }}>{value}</span>
}

function LectureRow({ lecture }: { lecture: InstructorLecture }) {
  const liveUrl = `/lectures/${lecture.lectureId}/live`

  if (lecture.status === 'OPEN') {
    return (
      <li style={{ ...ROW, background: c.primarySoft, border: `1.5px solid ${c.primaryStrong}` }}>
        <div style={ROW_BODY}>
          <div style={ROW_TITLE_LINE}>
            <span style={PILL.OPEN}>{STATUS_LABEL.OPEN}</span>
            <span style={{ fontSize: 16, fontWeight: 800 }}>{lecture.title}</span>
          </div>
          <div style={{ fontSize: 13, color: c.primaryDark }}>
            {clock(lecture.startedAt ?? lecture.createdAt)} 시작 · {'14문항' /* 목 */} · 코드 <Code value={lecture.participationCode} /> ·{' '}
            {'입장 18/24' /* 목 */}
          </div>
        </div>
        <Link to={liveUrl} style={{ ...buttonStyle('primary'), padding: '10px 18px' }}>
          수업 진행 →
        </Link>
      </li>
    )
  }

  if (lecture.status === 'SCHEDULED') {
    return (
      <li style={{ ...ROW, background: c.surface, border: `1.5px solid ${c.border}` }}>
        <div style={ROW_BODY}>
          <div style={ROW_TITLE_LINE}>
            <span style={PILL.SCHEDULED}>{STATUS_LABEL.SCHEDULED}</span>
            <span style={{ fontSize: 15.5, fontWeight: 700 }}>{lecture.title}</span>
          </div>
          <div style={{ fontSize: 13, color: c.textSub }}>
            {lecture.scheduledAt ? `${day(lecture.scheduledAt)} ${clock(lecture.scheduledAt)}` : '시작 시각 미정'} · 코드{' '}
            <Code value={lecture.participationCode} />
          </div>
        </div>
        <Link to={liveUrl} style={buttonStyle('mintOutline', 'sm')}>
          수업 시작 →
        </Link>
      </li>
    )
  }

  return (
    <li style={{ ...ROW, padding: '16px 22px', background: c.bg, border: `1.5px dashed ${c.border}` }}>
      <div style={{ ...ROW_BODY, gap: 4 }}>
        <div style={ROW_TITLE_LINE}>
          <span style={PILL.CLOSED}>{STATUS_LABEL.CLOSED}</span>
          <span style={{ fontSize: 15, fontWeight: 700, color: c.textSub }}>{lecture.title}</span>
        </div>
        <div style={{ fontSize: 13, color: c.textSub }}>
          {day(lecture.endedAt ?? lecture.startedAt ?? lecture.createdAt)} · {'참여 22명 · 정답률 76%' /* 목 */}
        </div>
      </div>
      {/* 목: 회차 리포트(06)는 MVP 범위 밖 */}
      <button type="button" disabled title="준비 중" style={{ ...buttonStyle('muted', 'sm'), ...inactive }}>
        회차 리포트
      </button>
    </li>
  )
}

export function LectureListPage() {
  const [lectures, setLectures] = useState<InstructorLecture[] | null>(null)
  const [total, setTotal] = useState(0)
  const [filter, setFilter] = useState<Filter>('ALL')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    lectureApi
      .list(0, 50)
      .then((page) => {
        setLectures(page.items)
        setTotal(page.totalElements)
      })
      .catch((e: unknown) => setError(errorMessage(e)))
  }, [])

  const all = lectures ?? []
  const count = (key: Filter) => (key === 'ALL' ? all.length : all.filter((l) => l.status === key).length)
  const visible = all
    .filter((l) => filter === 'ALL' || l.status === filter)
    .sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status])

  return (
    <SidebarLayout
      sidebarTitle="내 강의"
      maxWidth={1400}
      sidebar={
        <div
          aria-current="page"
          style={{ padding: '10px 12px', borderRadius: theme.radius.control, background: c.primarySoft, display: 'flex', justifyContent: 'space-between' }}
        >
          <span style={{ fontSize: 14, fontWeight: 800, color: c.primaryDark }}>전체 수업</span>
          <span style={{ fontFamily: theme.font.mono, fontSize: 13, fontWeight: 700, color: c.primaryDark }}>{total}</span>
        </div>
      }
    >
      <PageHeader
        crumbs={['교육', '수업 목록']}
        title="수업 목록"
        subtitle={`전체 ${total}회차`}
        actions={
          <Link to="/lectures/new" style={{ ...buttonStyle('primary'), padding: '11px 22px' }}>
            새 수업 개설
          </Link>
        }
      />

      <div role="group" aria-label="상태 필터" style={{ display: 'flex', gap: 8 }}>
        {FILTERS.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            aria-pressed={filter === key}
            onClick={() => setFilter(key)}
            style={filter === key ? CHIP_ACTIVE : CHIP}
          >
            {label} {count(key)}
          </button>
        ))}
      </div>

      {error && (
        <p role="alert" style={{ margin: 0, fontSize: 14, fontWeight: 700, color: c.danger }}>
          {error}
        </p>
      )}
      {lectures === null && !error && <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>불러오는 중…</p>}
      {lectures !== null && all.length === 0 && (
        <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>아직 만든 수업이 없어요. 새 수업을 개설해 보세요.</p>
      )}
      {all.length > 0 && visible.length === 0 && (
        <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>이 상태의 수업이 없어요.</p>
      )}

      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
        {visible.map((lecture) => (
          <LectureRow key={lecture.lectureId} lecture={lecture} />
        ))}
      </ul>

      {lectures !== null && (
        <div style={{ marginTop: 'auto', display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 13, color: c.textSub }}>
          <span>
            {total}회차 중 {visible.length}개 표시
          </span>
        </div>
      )}
    </SidebarLayout>
  )
}
