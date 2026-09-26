import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@ieum/api-client'
import { lectureApi } from '../domains/lecture/api/lectureApi'
import { connectInstructorRoom, type InstructorRoom, type RoomState } from '../domains/lecture/live/instructorRoom'
import type { RosterStudent } from '../domains/lecture/live/roster'
import {
  mockBankResult,
  mockCurrentProblem,
  mockProblemSet,
  mockProblemSetMore,
  mockProblemSetTotal,
  mockProgress,
  mockStudents,
  type MockSetItem,
  type MockStudent,
  type MockStudentState,
} from '../domains/lecture/mock/liveMock'
import type { InstructorLecture, LectureStatus } from '../domains/lecture/model/types'
import { errorMessage } from '../shared/ui/errorMessage'
import { buttonStyle, cardStyle, dimmed, inactive } from '../shared/ui/styles'
import { theme } from '../shared/ui/theme'

const c = theme.color
const mono = theme.font.mono

const STATUS_BADGE: Record<LectureStatus, { label: string; color: string }> = {
  SCHEDULED: { label: '시작 전', color: c.textMuted },
  OPEN: { label: '수업 중', color: c.live },
  CLOSED: { label: '종료', color: c.textMuted },
}

interface MicIndicator {
  label: string
  color: string
  live: boolean
}

/** 내 마이크 카드의 상태 문구. 실제 수업 상태·음성 방 상태·마이크 상태로 정한다 */
function micIndicator(status: LectureStatus, room: RoomState, mic: boolean): MicIndicator {
  if (status === 'SCHEDULED') return { label: '수업 시작 전', color: c.darkTextSub, live: false }
  if (status === 'CLOSED') return { label: '수업 종료', color: c.darkTextSub, live: false }
  if (room === 'connecting') return { label: '연결 중', color: c.darkTextSub, live: false }
  if (room === 'reconnecting') return { label: '재연결 중', color: c.warningLine, live: false }
  if (room === 'disconnected') return { label: '끊김', color: c.live, live: false }
  return mic ? { label: '송출 중', color: c.primary, live: true } : { label: '마이크 꺼짐', color: c.darkTextSub, live: false }
}

/** 경과 시간 mm:ss (1시간 이상이면 h:mm:ss) */
function formatElapsed(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const mmss = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return hours > 0 ? `${hours}:${mmss}` : mmss
}

/** 목: 장식용 음량 막대 높이(%) */
const LEVEL_BARS = [40, 75, 55, 90, 35, 65, 50]

/** 2열 그리드는 왼쪽→오른쪽, 위→아래로 채워지므로 점 번호를 1·4 / 2·5 / 3·6 순서로 놓는다 */
const DOT_ORDER = [1, 4, 2, 5, 3, 6]

const STUDENT_LOOK: Record<MockStudentState, { card: CSSProperties; name: string; accent: string }> = {
  correct: { card: { background: c.primarySoft }, name: c.text, accent: c.primaryDark },
  wrong: { card: { background: c.warningSoft }, name: c.text, accent: c.warningText },
  idle: { card: { background: c.bg, border: `1.5px solid ${c.borderSoft}` }, name: c.textSub, accent: c.textSub },
  absent: { card: { border: `1.5px solid ${c.borderStrong}` }, name: c.textMuted, accent: c.textMuted },
}

const LEGEND: { label: string; swatch: CSSProperties }[] = [
  { label: '정답', swatch: { background: c.primarySoft, border: `1px solid ${c.primaryStrong}` } },
  { label: '오답', swatch: { background: c.warningSoft, border: `1px solid ${c.warningLine}` } },
  { label: '미착수', swatch: { background: c.bg, border: `1px solid ${c.borderSoft}` } },
]

function BrailleCell({ dots }: { dots: readonly number[] }) {
  return (
    <div
      aria-hidden="true"
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(2, 12px)',
        gridAutoRows: '12px',
        gap: 5,
        padding: '8px 9px',
        background: c.fill,
        borderRadius: theme.radius.control,
      }}
    >
      {DOT_ORDER.map((dot) =>
        dots.includes(dot) ? (
          <span key={dot} style={{ width: 12, height: 12, borderRadius: '50%', background: c.text }} />
        ) : (
          <span key={dot} style={{ width: 12, height: 12, borderRadius: '50%', border: `2px solid ${c.borderStrong}`, boxSizing: 'border-box' }} />
        ),
      )}
    </div>
  )
}

/** 답안 상태는 아직 목이다(문제 출제·답안 API는 범위 밖). 미입장 예시는 실제 명단과 모순되므로 뺀다 */
const MOCK_ANSWERS = mockStudents.filter((s) => s.state !== 'absent')

/** 같은 학생에게는 정렬 순서가 바뀌어도 같은 예시 답안을 보여 준다 */
function mockAnswerFor(userId: string): MockStudent {
  return MOCK_ANSWERS[Number(userId) % MOCK_ANSWERS.length]
}

interface StudentCardProps {
  student: RosterStudent
  /** 수업 중이고 음성 방에 연결돼 있을 때만 발언을 바꿀 수 있다 */
  canControl: boolean
  /** 이 학생의 발언 요청이 진행 중 */
  pending: boolean
  disabled: boolean
  onToggleSpeaking: () => void
}

function StudentCard({ student, canControl, pending, disabled, onToggleSpeaking }: StudentCardProps) {
  const answer = mockAnswerFor(student.userId)
  const look = STUDENT_LOOK[answer.state]
  const action = student.speakingAllowed ? '발언 끄기' : '발언 허용'
  return (
    <li
      style={{
        borderRadius: theme.radius.control,
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        background: c.surface,
        border: `1.5px solid ${student.isSpeaking ? c.primary : c.border}`,
        boxShadow: student.isSpeaking ? `0 0 0 3px ${c.primarySoft}` : 'none',
      }}
    >
      {/* 실제: 이름·손들기·발언 허용·마이크·말하는 중 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 14, fontWeight: 800, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {student.name}
        </span>
        {student.handUp && (
          <span role="img" aria-label="손 들었어요" style={{ fontSize: 18, lineHeight: 1, flexShrink: 0 }}>
            ✋
          </span>
        )}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, fontSize: 11.5, fontWeight: 700 }}>
        {student.speakingAllowed && (
          <span style={{ padding: '2px 8px', borderRadius: theme.radius.pill, background: c.primarySoft, color: c.primaryDark }}>
            발언 허용
          </span>
        )}
        <span style={{ color: student.micOn ? c.primaryDark : c.textMuted }}>{student.micOn ? '🎙 마이크 켜짐' : '마이크 꺼짐'}</span>
        {student.isSpeaking && <span style={{ color: c.primaryDark }}>말하는 중</span>}
      </div>
      {/* 목: 답안 상태 예시 — 점선 칸으로 실제 정보와 구분한다 */}
      <div
        style={{
          ...look.card,
          border: `1px dashed ${c.borderStrong}`,
          borderRadius: theme.radius.small,
          padding: '6px 8px',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <span style={{ fontSize: 10, fontWeight: 700, color: c.textMuted }}>예시</span>
        {answer.glyph && <span style={{ fontSize: 18, lineHeight: 1, color: look.accent }}>{answer.glyph}</span>}
        <span style={{ fontSize: 11.5, fontWeight: 700, color: look.accent, fontFamily: answer.state === 'correct' ? mono : undefined }}>
          {answer.detail}
        </span>
        <span
          role="img"
          aria-label={answer.deviceConnected ? '장치 연결됨(예시)' : '장치 끊김(예시)'}
          style={{
            marginLeft: 'auto',
            width: 9,
            height: 9,
            borderRadius: '50%',
            flexShrink: 0,
            background: answer.deviceConnected ? c.primaryDark : c.live,
          }}
        />
      </div>
      {canControl && (
        <button
          type="button"
          disabled={disabled}
          onClick={onToggleSpeaking}
          aria-label={pending ? `${student.name} 처리 중` : `${student.name} ${action}`}
          aria-busy={pending}
          style={{
            ...buttonStyle(student.speakingAllowed ? 'dangerOutline' : 'mintOutline', 'sm'),
            padding: '6px 10px',
            fontSize: 12,
            ...(disabled ? dimmed : {}),
          }}
        >
          {pending ? '처리 중…' : action}
        </button>
      )}
    </li>
  )
}

function SetItem({ item }: { item: MockSetItem }) {
  const row: CSSProperties = {
    padding: '10px 13px',
    borderRadius: theme.radius.control,
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  }
  if (item.state === 'done') {
    return (
      <li style={{ ...row, background: c.fill, color: c.textMuted }}>
        <span style={{ fontWeight: 700 }}>{item.text}</span>
        <span style={{ fontSize: 12, fontWeight: 700 }}>출제됨 · {item.rate}</span>
      </li>
    )
  }
  if (item.state === 'current') {
    return (
      <li aria-current="true" style={{ ...row, background: c.primarySoft, border: `1.5px solid ${c.primaryStrong}` }}>
        <span style={{ fontWeight: 900, color: c.primaryDark }}>{item.text}</span>
        <span style={{ fontSize: 12, fontWeight: 800, color: c.primaryDark }}>출제 중</span>
      </li>
    )
  }
  return (
    <li style={{ ...row, border: `1.5px solid ${c.border}` }}>
      <span style={{ fontWeight: 700 }}>{item.text}</span>
      <button
        type="button"
        disabled
        title="준비 중"
        style={{
          border: `1.5px solid ${c.text}`,
          borderRadius: theme.radius.small,
          padding: '4px 12px',
          fontSize: 12,
          fontWeight: 700,
          fontFamily: 'inherit',
          background: 'transparent',
          color: c.text,
          ...inactive,
        }}
      >
        출제
      </button>
    </li>
  )
}

export function LiveLecturePage() {
  const { lectureId = '' } = useParams()
  const navigate = useNavigate()
  const [lecture, setLecture] = useState<InstructorLecture | null>(null)
  const [roomState, setRoomState] = useState<RoomState>('disconnected')
  const [mic, setMic] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [joining, setJoining] = useState(false)
  const [endFailed, setEndFailed] = useState(false)
  const [students, setStudents] = useState<RosterStudent[]>([])
  const [audioBlocked, setAudioBlocked] = useState(false)
  const [pendingUserId, setPendingUserId] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const roomRef = useRef<InstructorRoom | null>(null)
  // joinVoice 호출을 직렬화하는 세대 번호. 진입 자동 접속과 "음성 다시 연결" 클릭이
  // 겹치거나 언마운트 중에도 이어질 수 있어, 매 await 뒤 이 값으로 내가 아직 최신 호출인지 확인한다
  const joinSeqRef = useRef(0)

  const releaseRoom = useCallback(async () => {
    const room = roomRef.current
    roomRef.current = null
    if (room) await room.disconnect()
  }, [])

  const joinVoice = useCallback(async () => {
    const seq = ++joinSeqRef.current
    setJoining(true)
    try {
      await releaseRoom()
      if (seq !== joinSeqRef.current) return // 더 나중 호출이나 언마운트에 밀려났다
      // 이전 방의 학생 목록·재생 상태는 그 방의 콜백이 이미 세대 번호로 막혀 있어 여기서 비운다
      setStudents([])
      setAudioBlocked(false)
      const token = await lectureApi.issueMediaToken(lectureId)
      if (seq !== joinSeqRef.current) return
      // 이 방이 더 이상 최신 접속이 아니면(다른 접속으로 대체됐거나 언마운트됐으면) 어떤 콜백도 화면을 덮어쓰지 않는다
      const room = await connectInstructorRoom(
        token,
        (state) => {
          if (seq === joinSeqRef.current) setRoomState(state)
        },
        {
          onRoster: (next) => {
            if (seq === joinSeqRef.current) setStudents(next)
          },
          onAudioPlayback: (playing) => {
            if (seq === joinSeqRef.current) setAudioBlocked(!playing)
          },
          // 마이크 권한 프롬프트가 열려 있는 동안에도 확인한다 — 그 사이 언마운트되거나
          // 다음 접속(음성 다시 연결)에 밀려나면 학생 오디오를 붙이지 않고 방을 바로 끊는다
          isStale: () => seq !== joinSeqRef.current,
        },
      )
      if (seq !== joinSeqRef.current) {
        // 이 사이에 더 나중 호출이 이미 새 방을 roomRef에 넣었거나 언마운트됐다 —
        // 내가 만든 방은 아무도 참조하지 않으므로 여기서 바로 정리한다 (누수 방지)
        await room.disconnect()
        return
      }
      roomRef.current = room
      setMic(true)
      setStudents(room.roster())
      setAudioBlocked(!room.canPlaybackAudio())
    } finally {
      if (seq === joinSeqRef.current) setJoining(false)
    }
  }, [lectureId, releaseRoom])

  useEffect(() => {
    let cancelled = false
    if (!/^\d+$/.test(lectureId)) {
      // 숫자가 아닌 lectureId는 서버에 물어볼 필요 없이 바로 "찾을 수 없음"으로 처리한다
      setError(errorMessage(new ApiError(404, 'LECTURE_NOT_FOUND', 'invalid lectureId')))
      return
    }
    lectureApi
      .get(lectureId)
      .then(async (loaded) => {
        if (cancelled) return
        setLecture(loaded)
        if (loaded.status === 'OPEN') await joinVoice()
      })
      .catch((e) => !cancelled && setError(errorMessage(e)))
    return () => {
      cancelled = true
      joinSeqRef.current += 1 // 진행 중인 joinVoice를 무효화해 언마운트 뒤 roomRef 대입을 막는다
      void releaseRoom()
    }
  }, [lectureId, joinVoice, releaseRoom])

  // 경과 시간: 진행 중일 때만 1초마다 다시 그린다
  const ticking = lecture?.status === 'OPEN'
  useEffect(() => {
    if (!ticking) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [ticking])

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const start = () =>
    run(async () => {
      setLecture(await lectureApi.changeStatus(lectureId, 'OPEN'))
      await joinVoice()
    })

  const reconnect = () => run(joinVoice)

  const toggleMic = () =>
    run(async () => {
      const room = roomRef.current
      if (!room) return
      await room.setMicEnabled(!mic)
      setMic(room.micEnabled())
    })

  // 화면은 응답이 아니라 LiveKit 권한·속성 변경 이벤트(onRoster)로 갱신된다 — 권한이 유일한 상태다
  const toggleSpeaking = (student: RosterStudent) =>
    run(async () => {
      setPendingUserId(student.userId)
      try {
        await lectureApi.setSpeaking(lectureId, student.userId, !student.speakingAllowed)
      } finally {
        setPendingUserId(null)
      }
    })

  // 사용자 클릭 안에서 불러야 브라우저가 재생을 허락한다
  const enableAudio = () =>
    run(async () => {
      const room = roomRef.current
      if (!room) return
      const playing = await room.startAudio()
      if (roomRef.current === room) setAudioBlocked(!playing)
    })

  const end = () => {
    if (!window.confirm('수업을 종료할까요? 학생들의 음성 연결도 끝납니다.')) return
    void run(async () => {
      try {
        await lectureApi.changeStatus(lectureId, 'CLOSED')
      } catch (e) {
        // 상태는 저장됐고 방 삭제만 실패했다 — 같은 버튼으로 재시도한다 (API 명세 8.5절)
        if (e instanceof ApiError && e.code === 'MEDIA_SERVER_UNAVAILABLE') {
          setLecture((prev) => (prev ? { ...prev, status: 'CLOSED' } : prev))
          setEndFailed(true)
          // 서버가 방 삭제에 실패했더라도 화면은 CLOSED다 — 진행 중이던 접속을 무효화하고
          // 마이크 송출을 멈춰, 삭제되지 않은 방에 계속 송출되는 일이 없게 한다
          joinSeqRef.current += 1
          await releaseRoom()
          setMic(false)
          setRoomState('disconnected')
          setStudents([])
          setAudioBlocked(false)
        }
        throw e
      }
      await releaseRoom()
      navigate('/lectures', { replace: true })
    })
  }

  if (!lecture) {
    return (
      <main
        style={{
          minHeight: '100vh',
          padding: theme.layout.pagePadding,
          background: c.bg,
          fontFamily: theme.font.sans,
          color: c.text,
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
        }}
      >
        {error ? (
          <p role="alert" style={{ margin: 0, fontSize: 14, fontWeight: 700, color: c.danger }}>
            {error}
          </p>
        ) : (
          <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>불러오는 중…</p>
        )}
        <Link to="/lectures">수업 목록으로</Link>
      </main>
    )
  }

  const badge = STATUS_BADGE[lecture.status]
  const open = lecture.status === 'OPEN'
  const canControl = open && roomState === 'connected'
  const indicator = micIndicator(lecture.status, roomState, mic)
  const elapsedEnd = lecture.status === 'CLOSED' && lecture.endedAt ? Date.parse(lecture.endedAt) : now
  const elapsed = lecture.startedAt ? formatElapsed(elapsedEnd - Date.parse(lecture.startedAt)) : '--:--'
  const barButton: CSSProperties = { ...buttonStyle('darkOutline', 'sm'), fontSize: 13.5 }
  const micButton: CSSProperties = { ...buttonStyle('darkOutline', 'sm'), flex: 1, padding: 8, fontSize: 12.5, flexShrink: 1 }
  const percent = (value: number) => `${(value / mockProgress.total) * 100}%`

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: c.bg, color: c.text, fontFamily: theme.font.sans }}>
      {/* 실제: 수업 상태·이름·참여 코드·경과 시간·시작/종료 */}
      <header
        style={{
          height: theme.layout.headerHeight,
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 24,
          padding: '0 32px',
          background: c.dark,
          color: '#FFFFFF',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, minWidth: 0 }}>
          <Link to="/lectures" style={{ fontSize: 13, fontWeight: 700, color: c.darkTextSub, textDecoration: 'none', flexShrink: 0 }}>
            ← 목록
          </Link>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexShrink: 0 }}>
            <span style={{ width: 9, height: 9, borderRadius: '50%', background: badge.color }} />
            <span style={{ fontSize: 14, fontWeight: 800 }}>{badge.label}</span>
          </div>
          <div style={{ width: 1, height: 24, background: c.darkLine, flexShrink: 0 }} />
          <h1 style={{ margin: 0, fontSize: 15, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {lecture.title}
          </h1>
          <div
            style={{
              padding: '4px 12px',
              borderRadius: theme.radius.small,
              background: c.darkRaised,
              fontFamily: mono,
              fontSize: 13,
              fontWeight: 700,
              color: c.primary,
              flexShrink: 0,
            }}
          >
            코드 {lecture.participationCode}
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 18, flexShrink: 0 }}>
          <div style={{ fontSize: 13, color: c.darkTextSub }}>
            경과 <span style={{ fontFamily: mono, fontSize: 15, fontWeight: 700, color: '#FFFFFF' }}>{elapsed}</span>
          </div>
          {/* 목: 일시 정지는 MVP 범위 밖 */}
          <button type="button" disabled title="준비 중" style={{ ...barButton, ...inactive }}>
            일시 정지
          </button>
          {lecture.status === 'SCHEDULED' ? (
            <button
              type="button"
              disabled={busy || joining}
              onClick={start}
              style={{ ...buttonStyle('primary', 'sm'), fontSize: 13.5, boxShadow: 'none', ...(busy || joining ? dimmed : {}) }}
            >
              수업 시작
            </button>
          ) : (
            <button
              type="button"
              disabled={busy || joining}
              onClick={end}
              style={{ ...buttonStyle('dangerOutline', 'sm'), fontSize: 13.5, ...(busy || joining ? dimmed : {}) }}
            >
              {endFailed ? '종료 다시 시도' : '수업 종료'}
            </button>
          )}
        </div>
      </header>

      <div style={{ flex: 1, minHeight: 0, display: 'grid', gridTemplateColumns: `minmax(0, 1fr) ${theme.layout.livePanelWidth}px` }}>
        <main style={{ minWidth: 0, padding: theme.layout.pagePadding, display: 'flex', flexDirection: 'column', gap: 20 }}>
          {error && (
            <p role="alert" style={{ margin: 0, fontSize: 14, fontWeight: 700, color: c.danger }}>
              {error}
            </p>
          )}
          {lecture.status === 'CLOSED' && !error && (
            <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>
              종료된 수업이에요. <Link to="/lectures">수업 목록으로</Link>
            </p>
          )}

          {/* 목: 지금 문제 */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 32 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 22 }}>
              <div>
                <div style={{ fontSize: 13, color: c.textSub }}>지금 문제 · {mockCurrentProblem.order}</div>
                <div style={{ fontSize: 42, fontWeight: 900, letterSpacing: '-.02em', lineHeight: 1.1 }}>{mockCurrentProblem.text}</div>
              </div>
              <div
                style={{
                  ...cardStyle,
                  borderRadius: theme.radius.panel,
                  padding: '12px 16px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 16,
                }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                  <span style={{ fontSize: 12, color: c.textSub }}>정답 점형</span>
                  <span style={{ fontFamily: mono, fontSize: 14, fontWeight: 700 }}>{mockCurrentProblem.answerLabel}</span>
                </div>
                <div style={{ display: 'flex', gap: 10 }}>
                  {mockCurrentProblem.answerCells.map((dots, i) => (
                    <BrailleCell key={i} dots={dots} />
                  ))}
                </div>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 12, flexShrink: 0 }}>
              <button type="button" disabled title="준비 중" style={{ ...buttonStyle('outline'), ...inactive }}>
                한 번 더 읽어주기
              </button>
              <button type="button" disabled title="준비 중" style={{ ...buttonStyle('mintOutline'), fontWeight: 800, ...inactive }}>
                오른쪽에서 다음 문제 선택 ▸
              </button>
            </div>
          </div>

          {/* 목: 제출 진행 */}
          <div style={{ ...cardStyle, padding: '18px 24px', display: 'flex', alignItems: 'center', gap: 28, flexShrink: 0 }}>
            <div>
              <div style={{ fontSize: 12, color: c.textSub }}>제출</div>
              <div style={{ fontFamily: mono, fontSize: 24, fontWeight: 700 }}>
                {mockProgress.submitted} / {mockProgress.total}
              </div>
            </div>
            <div style={{ flex: 1, height: 10, borderRadius: theme.radius.pill, background: c.canvas, overflow: 'hidden', display: 'flex' }}>
              <span style={{ width: percent(mockProgress.correct), height: '100%', background: c.primaryTint }} />
              <span style={{ width: percent(mockProgress.wrong), height: '100%', background: c.warningTint }} />
            </div>
            <div style={{ display: 'flex', gap: 22 }}>
              {[
                { label: '정답', value: mockProgress.correct, color: c.primaryDark },
                { label: '오답', value: mockProgress.wrong, color: c.warningText },
                { label: '미착수', value: mockProgress.idle, color: c.textSub },
              ].map((stat) => (
                <div key={stat.label}>
                  <div style={{ fontSize: 12, color: c.textSub }}>{stat.label}</div>
                  <div style={{ fontFamily: mono, fontSize: 20, fontWeight: 700, color: stat.color }}>{stat.value}</div>
                </div>
              ))}
            </div>
          </div>

          {/* 실제: 입장한 학생(LiveKit 참가자)·손들기·발언 허용·마이크 / 목: 카드 안 점선 "예시" 칸의 답안 상태 */}
          <section style={{ ...cardStyle, flex: 1, minHeight: 0, padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>학생 · {students.length}명</h2>
                {canControl && audioBlocked && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={enableAudio}
                    style={{
                      ...buttonStyle('primary', 'sm'),
                      padding: '6px 14px',
                      fontSize: 12.5,
                      boxShadow: `0 2px 0 ${c.primaryStrong}`,
                      ...(busy ? dimmed : {}),
                    }}
                  >
                    학생 소리 켜기
                  </button>
                )}
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 14, fontSize: 12, color: c.textSub }}>
                <span style={{ fontWeight: 700 }}>답안 상태(예시)</span>
                {LEGEND.map((item) => (
                  <span key={item.label} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                    <span style={{ width: 9, height: 9, borderRadius: 2, ...item.swatch }} />
                    {item.label}
                  </span>
                ))}
                <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 9, height: 9, borderRadius: '50%', background: c.primaryDark }} />
                  점 = 장치 연결 여부
                </span>
              </div>
            </div>
            {students.length === 0 ? (
              <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>아직 입장한 학생이 없어요</p>
            ) : (
              <ul
                style={{
                  listStyle: 'none',
                  margin: 0,
                  padding: 0,
                  flex: 1,
                  minHeight: 0,
                  overflowY: 'auto',
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(168px, 1fr))',
                  alignContent: 'start',
                  gap: 10,
                }}
              >
                {students.map((student) => (
                  <StudentCard
                    key={student.userId}
                    student={student}
                    canControl={canControl}
                    pending={pendingUserId === student.userId}
                    disabled={busy || joining}
                    onToggleSpeaking={() => void toggleSpeaking(student)}
                  />
                ))}
              </ul>
            )}
          </section>
        </main>

        <aside
          style={{
            borderLeft: `1.5px solid ${c.border}`,
            background: c.surface,
            padding: '28px 24px',
            display: 'flex',
            flexDirection: 'column',
            gap: 18,
          }}
        >
          {/* 실제: 음성 송출 */}
          <section
            aria-label="내 마이크"
            style={{
              background: c.dark,
              borderRadius: theme.radius.card,
              padding: '18px 20px',
              display: 'flex',
              flexDirection: 'column',
              gap: 12,
              color: '#FFFFFF',
              flexShrink: 0,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: 14, fontWeight: 800 }}>내 마이크</span>
              <span role="status" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: indicator.color }}>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: indicator.color }} />
                {indicator.label}
              </span>
            </div>
            {/* 목: 장식용 음량 막대 — 송출 중일 때만 민트 */}
            <div aria-hidden="true" style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: 30 }}>
              {LEVEL_BARS.map((height, i) => (
                <span
                  key={i}
                  style={{ width: 6, height: `${height}%`, borderRadius: 2, background: indicator.live ? c.primary : c.darkLine }}
                />
              ))}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              {open && roomState === 'connected' ? (
                <button type="button" disabled={busy} onClick={toggleMic} style={{ ...micButton, ...(busy ? dimmed : {}) }}>
                  {mic ? '마이크 끄기' : '마이크 켜기'}
                </button>
              ) : open && roomState === 'disconnected' ? (
                <button
                  type="button"
                  disabled={busy || joining}
                  onClick={reconnect}
                  style={{ ...micButton, border: `1.5px solid ${c.primary}`, background: c.primary, ...(busy || joining ? dimmed : {}) }}
                >
                  음성 다시 연결
                </button>
              ) : (
                <button type="button" disabled style={{ ...micButton, ...dimmed }}>
                  마이크 끄기
                </button>
              )}
              {/* 목: 장치 선택은 MVP 범위 밖 */}
              <button type="button" disabled title="준비 중" style={{ ...micButton, ...inactive }}>
                장치 선택
              </button>
            </div>
            <div style={{ fontSize: 11.5, color: c.darkTextSub }}>발언을 허용한 학생만 말할 수 있어요 · 녹음·저장 없음</div>
          </section>

          {/* 목: 담아둔 세트 */}
          <section style={{ display: 'flex', flexDirection: 'column', gap: 10, flex: 1, minHeight: 0 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <h2 style={{ margin: 0, fontSize: 14, fontWeight: 800 }}>담아둔 세트 · {mockProblemSetTotal}</h2>
              <span style={{ fontSize: 11.5, color: c.textSub }}>골라서 출제 · 순서 무관</span>
            </div>
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 7, fontSize: 13.5 }}>
              {mockProblemSet.map((item) => (
                <SetItem key={item.text} item={item} />
              ))}
            </ul>
            <div style={{ fontSize: 12, color: c.textSub, textAlign: 'center' }}>+ {mockProblemSetMore}개 더 보기</div>
          </section>

          {/* 목: 문제 은행에서 바로 출제 */}
          <section
            style={{
              ...cardStyle,
              background: c.bg,
              padding: '16px 18px',
              display: 'flex',
              flexDirection: 'column',
              gap: 10,
              flexShrink: 0,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <h2 style={{ margin: 0, fontSize: 14, fontWeight: 800 }}>문제 은행에서 바로 출제</h2>
              <span style={{ fontSize: 11.5, color: c.textSub }}>검수된 문항만</span>
            </div>
            <input
              disabled
              aria-label="문제 은행 검색"
              placeholder="글자 또는 규칙 검색 — 예: 된소리"
              style={{
                border: `1.5px solid ${c.border}`,
                borderRadius: theme.radius.control,
                background: c.surface,
                padding: '9px 13px',
                fontSize: 13,
                fontFamily: 'inherit',
                color: c.textSub,
                ...inactive,
              }}
            />
            <div
              style={{
                padding: '10px 13px',
                borderRadius: theme.radius.control,
                background: c.surface,
                border: `1.5px solid ${c.border}`,
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                <span style={{ fontSize: 11, fontWeight: 700, color: c.textMuted }}>검색 결과</span>
                <span style={{ fontSize: 13.5, fontWeight: 800 }}>
                  {mockBankResult.text} <span style={{ fontSize: 18 }}>{mockBankResult.glyph}</span>
                </span>
                <span style={{ fontSize: 11.5, color: c.danger }}>{mockBankResult.note}</span>
              </div>
              <button
                type="button"
                disabled
                title="준비 중"
                style={{
                  ...buttonStyle('primary', 'sm'),
                  borderRadius: theme.radius.small,
                  padding: '6px 14px',
                  fontSize: 12,
                  boxShadow: `0 2px 0 ${c.primaryStrong}`,
                  ...inactive,
                }}
              >
                바로 출제
              </button>
            </div>
          </section>
        </aside>
      </div>
    </div>
  )
}
