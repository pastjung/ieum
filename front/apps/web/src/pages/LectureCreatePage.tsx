import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { lectureApi, toScheduledAtUtc } from '../domains/lecture/api/lectureApi'
import { errorMessage } from '../shared/ui/errorMessage'
import { PageHeader } from '../shared/ui/PageHeader'
import { SidebarLayout } from '../shared/ui/SidebarLayout'
import { buttonStyle, cardStyle, dimmed, fieldLabel, fieldStyle, fieldWrap } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color
const FORM_ID = 'lecture-create-form'

/** 개설은 한 화면으로 끝난다 — 문제는 수업 화면의 문제 세트에서 추가한다 */
const GUIDE = [
  '수업 이름을 정하고 개설하면 참여 코드가 바로 발급돼요.',
  "문제는 수업 화면 오른쪽 '문제 세트'에서 글자를 입력해 추가해요. 수업 시작 전에 미리 넣어 둘 수 있어요.",
  '학생은 앱에서 참여 코드를 입력해 들어와요.',
] as const

function Guide() {
  return (
    <ol style={{ margin: 0, padding: '4px 4px 0 18px', display: 'flex', flexDirection: 'column', gap: 10 }}>
      {GUIDE.map((line) => (
        <li key={line} style={{ fontSize: 13, lineHeight: 1.55, color: c.textSub }}>
          {line}
        </li>
      ))}
    </ol>
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
      sidebarTitle="개설 안내"
      gap={22}
      sidebar={<Guide />}
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
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>기본 정보</h2>

          <label style={fieldWrap}>
            <span style={fieldLabel}>수업 이름</span>
            <input name="title" required maxLength={150} placeholder="약자 4회차 · 약자 규칙 중심" style={fieldStyle} />
          </label>

          <label style={fieldWrap}>
            <span style={fieldLabel}>설명 (선택)</span>
            <textarea name="description" rows={3} style={{ ...fieldStyle, fontWeight: 400, resize: 'vertical' }} />
          </label>

          <label style={fieldWrap}>
            <span style={fieldLabel}>시작 시각 (선택)</span>
            <input name="scheduledAt" type="datetime-local" style={{ ...fieldStyle, fontFamily: theme.font.mono, maxWidth: 320 }} />
          </label>

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
