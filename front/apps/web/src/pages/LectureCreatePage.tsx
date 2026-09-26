import { useState, type CSSProperties, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { lectureApi, toScheduledAtUtc } from '../domains/lecture/api/lectureApi'
import { errorMessage } from '../shared/ui/errorMessage'
import { PageHeader } from '../shared/ui/PageHeader'
import { SidebarLayout } from '../shared/ui/SidebarLayout'
import { buttonStyle, cardStyle, dimmed, fieldLabel, fieldStyle, fieldWrap, inactive } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color
const FORM_ID = 'lecture-create-form'
const STEPS = ['기본 정보', '문제 세트', '참여 방식'] as const

/* 목: 아래 값들은 수업 API에 없다 — 와이어프레임 모양만 보여 주고 비활성으로 둔다 */
const LEVELS = ['촉지', '자음', '모음', '약자', '약어', '단어', '문장'] as const
const MOCK_LEVEL = '약자'
const DIFFICULTIES = ['초급', '중급', '고급'] as const
const MOCK_DIFFICULTY = '중급'
const MOCK_TOGGLES = [
  { label: '오답을 복습 목록에 담기', hint: '학생 앱 오늘의 복습에 자동 추가', on: true },
  { label: '정답 즉시 공개', hint: '끄면 수업 종료 후 함께 확인', on: false },
]

const LEVEL_CHIP: CSSProperties = {
  padding: '7px 14px',
  borderRadius: theme.radius.control,
  border: `1.5px solid ${c.border}`,
  background: c.surface,
  color: c.textSub,
  fontSize: 13,
  fontFamily: 'inherit',
  ...inactive,
}
const LEVEL_CHIP_ON: CSSProperties = { ...LEVEL_CHIP, border: `1.5px solid ${c.primary}`, background: c.primary, color: c.onPrimary, fontWeight: 800 }

const SEGMENT: CSSProperties = {
  flex: 1,
  padding: '9px 0',
  borderRadius: theme.radius.control,
  border: `1.5px solid ${c.border}`,
  background: c.surface,
  color: c.textSub,
  fontSize: 13,
  fontFamily: 'inherit',
  textAlign: 'center',
  ...inactive,
}
const SEGMENT_ON: CSSProperties = { ...SEGMENT, border: `1.5px solid ${c.text}`, background: c.text, color: '#FFFFFF', fontWeight: 700 }

function StepItem({ index, label }: { index: number; label: string }) {
  const active = index === 0
  return (
    <div
      aria-current={active ? 'step' : undefined}
      aria-disabled={active ? undefined : true}
      title={active ? undefined : '준비 중'}
      style={{
        padding: 12,
        borderRadius: theme.radius.control,
        background: active ? c.primarySoft : 'transparent',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
      }}
    >
      <span
        style={{
          width: 22,
          height: 22,
          borderRadius: '50%',
          fontFamily: theme.font.mono,
          fontSize: 12,
          fontWeight: 700,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          ...(active ? { background: c.primary, color: c.onPrimary } : { border: `1.5px solid ${c.textMuted}`, color: c.textMuted }),
        }}
      >
        {index + 1}
      </span>
      <span style={active ? { fontSize: 14, fontWeight: 800, color: c.primaryDark } : { fontSize: 14, color: c.textSub }}>{label}</span>
    </div>
  )
}

export function LectureCreatePage() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const scheduledLocal = String(form.get('scheduledAt') ?? '')
    setPending(true)
    setError(null)
    try {
      await lectureApi.create({
        title: String(form.get('title') ?? ''),
        description: String(form.get('description') ?? ''),
        scheduledAt: scheduledLocal ? toScheduledAtUtc(scheduledLocal) : undefined,
      })
      navigate('/lectures', { replace: true })
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <SidebarLayout
      sidebarTitle="개설 단계"
      gap={22}
      sidebar={STEPS.map((step, i) => (
        <StepItem key={step} index={i} label={step} />
      ))}
    >
      <PageHeader
        crumbs={['교육', '수업 목록', '새 수업 개설']}
        title="수업 개설"
        subtitle="개설하면 참여 코드가 바로 발급돼요"
        actions={
          <>
            <Link to="/lectures" style={buttonStyle('outline')}>
              취소
            </Link>
            <button
              type="submit"
              form={FORM_ID}
              disabled={pending}
              style={{ ...buttonStyle('primary'), padding: '11px 22px', ...(pending ? dimmed : {}) }}
            >
              {pending ? '개설 중…' : '개설하고 코드 발급'}
            </button>
          </>
        }
      />

      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'flex-start' }}>
        <form
          id={FORM_ID}
          onSubmit={handleSubmit}
          style={{ ...cardStyle, width: 720, maxWidth: '100%', boxSizing: 'border-box', padding: 32, display: 'flex', flexDirection: 'column', gap: 18 }}
        >
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>1단계 · 기본 정보</h2>

          <label style={fieldWrap}>
            <span style={fieldLabel}>수업 이름</span>
            <input name="title" required maxLength={150} placeholder="약자 4회차 · 약자 규칙 중심" style={fieldStyle} />
          </label>

          <label style={fieldWrap}>
            <span style={fieldLabel}>설명 (선택)</span>
            <textarea name="description" rows={3} style={{ ...fieldStyle, fontWeight: 400, resize: 'vertical' }} />
          </label>

          {/* 목 */}
          <div style={fieldWrap}>
            <span id="lecture-level-label" style={fieldLabel}>
              단계
            </span>
            <div role="group" aria-labelledby="lecture-level-label" style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {LEVELS.map((level) => (
                <button
                  key={level}
                  type="button"
                  disabled
                  title="준비 중"
                  aria-pressed={level === MOCK_LEVEL}
                  style={level === MOCK_LEVEL ? LEVEL_CHIP_ON : LEVEL_CHIP}
                >
                  {level}
                </button>
              ))}
            </div>
          </div>

          {/* 목 */}
          <div style={fieldWrap}>
            <span id="lecture-difficulty-label" style={fieldLabel}>
              난이도
            </span>
            <div role="group" aria-labelledby="lecture-difficulty-label" style={{ display: 'flex', gap: 8 }}>
              {DIFFICULTIES.map((difficulty) => (
                <button
                  key={difficulty}
                  type="button"
                  disabled
                  title="준비 중"
                  aria-pressed={difficulty === MOCK_DIFFICULTY}
                  style={difficulty === MOCK_DIFFICULTY ? SEGMENT_ON : SEGMENT}
                >
                  {difficulty}
                </button>
              ))}
            </div>
          </div>

          <div style={{ display: 'flex', gap: 14 }}>
            <label style={{ ...fieldWrap, flex: 1 }}>
              <span style={fieldLabel}>시작 시각 (선택)</span>
              <input name="scheduledAt" type="datetime-local" style={{ ...fieldStyle, fontFamily: theme.font.mono }} />
            </label>
            {/* 목 */}
            <label style={{ ...fieldWrap, flex: 1 }}>
              <span style={fieldLabel}>문항당 제한</span>
              <input value="30초" readOnly disabled title="준비 중" style={{ ...fieldStyle, fontFamily: theme.font.mono, color: c.text, ...inactive }} />
            </label>
          </div>

          {/* 목 */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {MOCK_TOGGLES.map((toggle) => (
              <div key={toggle.label} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  <span style={{ fontSize: 14, fontWeight: 700 }}>{toggle.label}</span>
                  <span style={{ fontSize: 12, color: c.textSub }}>{toggle.hint}</span>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={toggle.on}
                  aria-label={toggle.label}
                  disabled
                  title="준비 중"
                  style={{
                    width: 44,
                    height: 24,
                    padding: 0,
                    border: 0,
                    borderRadius: theme.radius.pill,
                    background: toggle.on ? c.primary : c.border,
                    position: 'relative',
                    flexShrink: 0,
                    ...inactive,
                  }}
                >
                  <span
                    style={{
                      position: 'absolute',
                      top: 3,
                      width: 18,
                      height: 18,
                      borderRadius: '50%',
                      background: '#FFFFFF',
                      ...(toggle.on ? { right: 3 } : { left: 3, boxShadow: '0 1px 3px rgba(0,0,0,.2)' }),
                    }}
                  />
                </button>
              </div>
            ))}
          </div>

          {error && (
            <p role="alert" style={{ margin: 0, fontSize: 13, fontWeight: 700, color: c.danger }}>
              {error}
            </p>
          )}
        </form>
      </div>
    </SidebarLayout>
  )
}
