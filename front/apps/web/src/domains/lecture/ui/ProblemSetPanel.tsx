import { useId, useState, type CSSProperties, type FormEvent } from 'react'
import { errorMessage } from '../../../shared/ui/errorMessage'
import { buttonStyle, dimmed, fieldStyle } from '../../../shared/ui/styles'
import { theme } from '../../../shared/ui/theme'
import { ambiguousCandidates, lectureApi } from '../api/lectureApi'
import { brailleGlyph } from '../model/braille'
import { problemSetItems, type SetItem } from '../model/problemSet'
import type { LectureStatus, LinkedProblem, ProblemCandidate } from '../model/types'

const c = theme.color

const INPUT_GUIDE =
  "초성 ㄱ은 '초성'을 입력하고 한 칸 띄운 뒤 ㄱ을 입력하세요. 받침 ㄱ, 모음 ㅏ, 음절 가도 같은 방식이에요."
const DELIVER_LOCKED = '수업을 시작하면 출제할 수 있어요'
const DELIVER_CLOSED = '종료된 수업이에요'

export interface ProblemSetPanelProps {
  lectureId: string
  status: LectureStatus
  problems: LinkedProblem[]
  currentProblemId: string | null
  /** 이번 세션에 한 번이라도 지금 문제였던 problemId */
  deliveredIds: ReadonlySet<string>
  /** 출제 요청 중인 problemId */
  deliveringId: string | null
  /** 페이지의 다른 요청이 진행 중이면 출제를 막는다 */
  disabled: boolean
  onRegistered: (problem: LinkedProblem) => void
  onDeliver: (problemId: string) => void
}

interface SetRowProps {
  item: SetItem
  status: LectureStatus
  canDeliver: boolean
  delivering: boolean
  disabled: boolean
  onDeliver: (problemId: string) => void
}

function SetRow({ item, status, canDeliver, delivering, disabled, onDeliver }: SetRowProps) {
  const { problem, state } = item
  const row: CSSProperties = {
    padding: '10px 13px',
    borderRadius: theme.radius.control,
    display: 'flex',
    alignItems: 'center',
    gap: 10,
  }
  const look: CSSProperties =
    state === 'current'
      ? { background: c.primarySoft, border: `1.5px solid ${c.primaryStrong}` }
      : state === 'delivered'
        ? { background: c.fill, color: c.textMuted }
        : { border: `1.5px solid ${c.border}` }
  const locked = !canDeliver || disabled
  return (
    <li aria-current={state === 'current' ? 'true' : undefined} style={{ ...row, ...look }}>
      <span
        style={{
          flex: 1,
          minWidth: 0,
          fontWeight: state === 'current' ? 900 : 700,
          color: state === 'current' ? c.primaryDark : undefined,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {problem.displayText}
      </span>
      <span aria-hidden="true" style={{ fontSize: 16, lineHeight: 1, flexShrink: 0 }}>
        {brailleGlyph(problem.expectedPattern)}
      </span>
      {state === 'current' && (
        <span style={{ fontSize: 12, fontWeight: 800, color: c.primaryDark, flexShrink: 0 }}>
          지금 문제
        </span>
      )}
      {state === 'delivered' && (
        <span style={{ fontSize: 12, fontWeight: 700, flexShrink: 0 }}>출제됨</span>
      )}
      {state === 'waiting' && (
        <>
          <span style={{ fontSize: 12, fontWeight: 700, color: c.textSub, flexShrink: 0 }}>
            대기
          </span>
          <button
            type="button"
            disabled={locked}
            onClick={() => onDeliver(problem.problemId)}
            title={
              status === 'SCHEDULED'
                ? DELIVER_LOCKED
                : status === 'CLOSED'
                  ? DELIVER_CLOSED
                  : undefined
            }
            aria-label={
              delivering ? `${problem.displayText} 출제 중` : `${problem.displayText} 출제`
            }
            aria-busy={delivering}
            style={{
              ...buttonStyle('outline', 'sm'),
              borderRadius: theme.radius.small,
              padding: '4px 12px',
              fontSize: 12,
              ...(locked ? dimmed : {}),
            }}
          >
            {delivering ? '출제 중…' : '출제'}
          </button>
        </>
      )}
    </li>
  )
}

/** 03 화면 오른쪽 문제 세트: 글자 입력으로 문제를 연결하고, 골라서 출제한다 (문제 출제 설계 §4) */
export function ProblemSetPanel({
  lectureId,
  status,
  problems,
  currentProblemId,
  deliveredIds,
  deliveringId,
  disabled,
  onRegistered,
  onDeliver,
}: ProblemSetPanelProps) {
  const titleId = useId()
  const inputId = useId()
  const guideId = useId()
  const [text, setText] = useState('')
  const [candidates, setCandidates] = useState<ProblemCandidate[] | null>(null)
  const [registering, setRegistering] = useState(false)
  const [registerError, setRegisterError] = useState<string | null>(null)

  const canRegister = status !== 'CLOSED'
  const canDeliver = status === 'OPEN'
  const items = problemSetItems(problems, currentProblemId, deliveredIds)

  async function register(input: string) {
    const targetText = input.trim()
    if (!targetText || registering) return
    setRegistering(true)
    setRegisterError(null)
    try {
      const problem = await lectureApi.registerProblem(lectureId, targetText)
      onRegistered(problem)
      setText('')
      setCandidates(null)
    } catch (e) {
      // 구분어 없는 낱자가 여러 문제에 해당하면 후보를 보여 주고, 누르면 그 input으로 다시 등록한다
      const found = ambiguousCandidates(e)
      setCandidates(found)
      if (!found) setRegisterError(errorMessage(e))
    } finally {
      setRegistering(false)
    }
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void register(text)
  }

  const addLocked = !canRegister || registering || text.trim() === ''

  return (
    <section
      aria-labelledby={titleId}
      style={{ display: 'flex', flexDirection: 'column', gap: 10, flex: 1, minHeight: 0 }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <h2 id={titleId} style={{ margin: 0, fontSize: 14, fontWeight: 800 }}>
          문제 세트 · {problems.length}
        </h2>
        <span style={{ fontSize: 11.5, color: c.textSub }}>골라서 출제 · 순서 무관</span>
      </div>

      <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <label htmlFor={inputId} style={{ fontSize: 12.5, fontWeight: 700 }}>
          문제 추가
        </label>
        <p id={guideId} style={{ margin: 0, fontSize: 11.5, lineHeight: 1.5, color: c.textSub }}>
          {INPUT_GUIDE}
        </p>
        <div style={{ display: 'flex', gap: 8 }}>
          <input
            id={inputId}
            aria-describedby={guideId}
            value={text}
            disabled={!canRegister || registering}
            placeholder="예: 초성 ㄱ, 그리고"
            onChange={(event) => {
              setText(event.target.value)
              setCandidates(null)
            }}
            style={{ ...fieldStyle, flex: 1, minWidth: 0, minHeight: 38, fontSize: 13.5 }}
          />
          <button
            type="submit"
            disabled={addLocked}
            style={{
              ...buttonStyle('primary', 'sm'),
              boxShadow: `0 2px 0 ${c.primaryStrong}`,
              ...(addLocked ? dimmed : {}),
            }}
          >
            {registering ? '추가 중…' : '추가'}
          </button>
        </div>
        {candidates && (
          <div
            role="group"
            aria-label="어떤 문제인지 고르기"
            style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6 }}
          >
            <span style={{ fontSize: 12, color: c.textSub }}>어떤 문제인가요?</span>
            {candidates.map((candidate) => (
              <button
                key={candidate.input}
                type="button"
                disabled={registering || !canRegister}
                onClick={() => void register(candidate.input)}
                style={{
                  ...buttonStyle('mintOutline', 'sm'),
                  padding: '4px 12px',
                  fontSize: 12,
                  ...(registering || !canRegister ? dimmed : {}),
                }}
              >
                {candidate.displayText}
              </button>
            ))}
          </div>
        )}
        {registerError && (
          <p role="alert" style={{ margin: 0, fontSize: 12.5, fontWeight: 700, color: c.danger }}>
            {registerError}
          </p>
        )}
      </form>

      {status === 'SCHEDULED' && (
        <p style={{ margin: 0, fontSize: 12, color: c.textSub }}>{DELIVER_LOCKED}</p>
      )}

      {items.length === 0 ? (
        <p style={{ margin: 0, fontSize: 13, color: c.textSub }}>아직 추가한 문제가 없어요</p>
      ) : (
        <ul
          style={{
            listStyle: 'none',
            margin: 0,
            padding: 0,
            display: 'flex',
            flexDirection: 'column',
            gap: 7,
            fontSize: 13.5,
            overflowY: 'auto',
            minHeight: 0,
          }}
        >
          {items.map((item) => (
            <SetRow
              key={item.problem.problemId}
              item={item}
              status={status}
              canDeliver={canDeliver}
              delivering={deliveringId === item.problem.problemId}
              disabled={disabled || deliveringId !== null}
              onDeliver={onDeliver}
            />
          ))}
        </ul>
      )}
    </section>
  )
}
