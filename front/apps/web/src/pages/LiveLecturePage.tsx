import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@ieum/api-client'
import { lectureApi } from '../domains/lecture/api/lectureApi'
import {
  connectInstructorRoom,
  type InstructorRoom,
  type RoomState,
} from '../domains/lecture/live/instructorRoom'
import type { RosterStudent } from '../domains/lecture/live/roster'
import {
  progressCounts,
  studentAnswerView,
  type AnswerState,
  type StudentAnswerView,
} from '../domains/lecture/model/answerView'
import { dotLabel, dotsOf } from '../domains/lecture/model/braille'
import {
  mergeLinkedProblem,
  problemTitleSize,
  reconcileLinkedProblems,
} from '../domains/lecture/model/problemSet'
import type {
  InstructorLecture,
  LectureStatus,
  LinkedProblem,
  ProblemAnswers,
  StudentAnswer,
} from '../domains/lecture/model/types'
import { ProblemSetPanel } from '../domains/lecture/ui/ProblemSetPanel'
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
  return mic
    ? { label: '송출 중', color: c.primary, live: true }
    : { label: '마이크 꺼짐', color: c.darkTextSub, live: false }
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

const PROBLEM_UNLINKED_MESSAGE = '수업에 연결되지 않은 문제예요. 새로고침해 주세요.'

/**
 * 출제·답안 조회의 404는 문제 등록(구분어·글자 확인) 문구가 아니라 화면이 낡았다는 뜻이다 —
 * 이 화면과 무관하게 문제 연결이 끊겼을 때만 일어난다
 */
function problemErrorMessage(e: unknown): string {
  return e instanceof ApiError && e.code === 'PROBLEM_NOT_FOUND'
    ? PROBLEM_UNLINKED_MESSAGE
    : errorMessage(e)
}

/** 목: 장식용 음량 막대 높이(%) */
const LEVEL_BARS = [40, 75, 55, 90, 35, 65, 50]

/** 2열 그리드는 왼쪽→오른쪽, 위→아래로 채워지므로 점 번호를 1·4 / 2·5 / 3·6 순서로 놓는다 */
const DOT_ORDER = [1, 4, 2, 5, 3, 6]

/** 학생 카드 안 답안 칸. 정답 초록 · 오답 노랑 · 미착수 회색 */
const ANSWER_LOOK: Record<AnswerState, { box: CSSProperties; accent: string }> = {
  correct: {
    box: { background: c.primarySoft, border: `1px solid ${c.primaryStrong}` },
    accent: c.primaryDark,
  },
  wrong: {
    box: { background: c.warningSoft, border: `1px solid ${c.warningLine}` },
    accent: c.warningText,
  },
  idle: { box: { background: c.bg, border: `1px solid ${c.borderSoft}` }, accent: c.textSub },
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
          <span
            key={dot}
            style={{ width: 12, height: 12, borderRadius: '50%', background: c.text }}
          />
        ) : (
          <span
            key={dot}
            style={{
              width: 12,
              height: 12,
              borderRadius: '50%',
              border: `2px solid ${c.borderStrong}`,
              boxSizing: 'border-box',
            }}
          />
        ),
      )}
    </div>
  )
}

interface StudentCardProps {
  student: RosterStudent
  /** 지금 문제에 대한 답안 칸. 지금 문제가 없으면 null(칸을 그리지 않는다) */
  answer: StudentAnswerView | null
  /** 수업 중이고 음성 방에 연결돼 있을 때만 발언을 바꿀 수 있다 */
  canControl: boolean
  /** 이 학생의 발언 요청이 진행 중 */
  pending: boolean
  disabled: boolean
  onToggleSpeaking: () => void
}

function StudentCard({
  student,
  answer,
  canControl,
  pending,
  disabled,
  onToggleSpeaking,
}: StudentCardProps) {
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
      <div
        style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}
      >
        <span
          style={{
            fontSize: 14,
            fontWeight: 800,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {student.name}
        </span>
        {student.handUp && (
          <span
            role="img"
            aria-label="손 들었어요"
            style={{ fontSize: 18, lineHeight: 1, flexShrink: 0 }}
          >
            ✋
          </span>
        )}
      </div>
      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 6,
          fontSize: 11.5,
          fontWeight: 700,
        }}
      >
        {student.speakingAllowed && (
          <span
            style={{
              padding: '2px 8px',
              borderRadius: theme.radius.pill,
              background: c.primarySoft,
              color: c.primaryDark,
            }}
          >
            발언 허용
          </span>
        )}
        <span style={{ color: student.micOn ? c.primaryDark : c.textMuted }}>
          {student.micOn ? '🎙 마이크 켜짐' : '마이크 꺼짐'}
        </span>
        {student.isSpeaking && <span style={{ color: c.primaryDark }}>말하는 중</span>}
      </div>
      {/* 실제: 지금 문제에 제출한 점자와 결과 (걸린 시간은 저장하지 않으므로 표시하지 않는다) */}
      {answer && (
        <div
          style={{
            ...ANSWER_LOOK[answer.state].box,
            borderRadius: theme.radius.small,
            padding: '8px 10px',
            minHeight: 44,
            boxSizing: 'border-box',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            gap: 6,
            minWidth: 0,
          }}
        >
          {answer.glyph && (
            <span
              style={{
                fontSize: 28,
                lineHeight: 1.1,
                letterSpacing: '.04em',
                color: ANSWER_LOOK[answer.state].accent,
                overflowWrap: 'anywhere',
              }}
            >
              {answer.glyph}
            </span>
          )}
          <span style={{ fontSize: 13, fontWeight: 800, color: ANSWER_LOOK[answer.state].accent }}>
            {answer.detail}
          </span>
        </div>
      )}
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
  /** 마지막으로 받은 답안 목록. problemId가 지금 문제와 같을 때만 화면에 쓴다 */
  const [answerSet, setAnswerSet] = useState<ProblemAnswers | null>(null)
  /** 이번 세션에 한 번이라도 지금 문제였던 problemId (새로고침하면 지금 문제만 남는다) */
  const [deliveredIds, setDeliveredIds] = useState<ReadonlySet<string>>(() => new Set())
  const [deliveringId, setDeliveringId] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const roomRef = useRef<InstructorRoom | null>(null)
  // joinVoice 호출을 직렬화하는 세대 번호. 진입 자동 접속과 "음성 다시 연결" 클릭이
  // 겹치거나 언마운트 중에도 이어질 수 있어, 매 await 뒤 이 값으로 내가 아직 최신 호출인지 확인한다
  const joinSeqRef = useRef(0)
  // 답안 목록 조회의 세대 번호. 진입·출제·제출 신호 조회가 겹치면 마지막 요청의 응답만 반영한다
  const answersSeqRef = useRef(0)
  // 방 콜백(답안 신호) 안에서 지금 문제를 확인하려고 최신 값을 둔다
  const currentProblemIdRef = useRef<string | null>(null)

  const releaseRoom = useCallback(async () => {
    const room = roomRef.current
    roomRef.current = null
    if (room) await room.disconnect()
  }, [])

  const loadAnswers = useCallback(
    async (problemId: string | null) => {
      const seq = ++answersSeqRef.current
      if (problemId === null) {
        setAnswerSet(null)
        return
      }
      try {
        const loaded = await lectureApi.listAnswers(lectureId, problemId)
        // 화면 전체 오류 배너는 다른 작업(발언 허용·마이크 등)의 실패도 보여 주므로,
        // 답안 재조회 성공만으로 지우지 않는다
        if (seq === answersSeqRef.current) setAnswerSet(loaded)
      } catch (e) {
        if (seq === answersSeqRef.current) setError(problemErrorMessage(e))
      }
    },
    [lectureId],
  )

  const joinVoice = useCallback(async () => {
    const seq = ++joinSeqRef.current
    setJoining(true)
    // 재연결 전환(reconnecting → connected)을 알아채려고 이 접속 안에서만 마지막 상태를 기억한다
    let lastRoomState: RoomState | undefined
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
          if (seq !== joinSeqRef.current) return
          setRoomState(state)
          // 방이 끊겨 있던 동안 놓쳤을 ANSWER_SUBMITTED 신호를 HTTP 재조회로 되찾는다
          if (state === 'connected' && lastRoomState === 'reconnecting')
            void loadAnswers(currentProblemIdRef.current)
          lastRoomState = state
        },
        {
          onRoster: (next) => {
            if (seq === joinSeqRef.current) setStudents(next)
          },
          onAudioPlayback: (playing) => {
            if (seq === joinSeqRef.current) setAudioBlocked(!playing)
          },
          // 지금 문제에 대한 신호만 반영한다 — 이전 문제의 늦은 신호는 화면과 무관하다
          onAnswerSubmitted: (problemId) => {
            if (seq === joinSeqRef.current && problemId === currentProblemIdRef.current)
              void loadAnswers(problemId)
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
      // 방이 끊겨 있던 동안(예: "음성 다시 연결") 놓쳤을 신호도 접속 직후 한 번 다시 불러온다
      void loadAnswers(currentProblemIdRef.current)
    } finally {
      if (seq === joinSeqRef.current) setJoining(false)
    }
  }, [lectureId, releaseRoom, loadAnswers])

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
        const current = loaded.currentProblem?.problemId ?? null
        currentProblemIdRef.current = current
        setDeliveredIds(new Set(current ? [current] : []))
        // 답안 목록은 수업 상태와 무관하게 조회된다 (종료 뒤에도 복원)
        void loadAnswers(current)
        if (loaded.status === 'OPEN') await joinVoice()
      })
      .catch((e) => !cancelled && setError(errorMessage(e)))
    return () => {
      cancelled = true
      joinSeqRef.current += 1 // 진행 중인 joinVoice를 무효화해 언마운트 뒤 roomRef 대입을 막는다
      answersSeqRef.current += 1 // 진행 중인 답안 조회가 언마운트 뒤 화면을 덮어쓰지 않게 한다
      void releaseRoom()
    }
  }, [lectureId, joinVoice, releaseRoom, loadAnswers])

  // 경과 시간: 진행 중일 때만 1초마다 다시 그린다
  const ticking = lecture?.status === 'OPEN'
  useEffect(() => {
    if (!ticking) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [ticking])

  // 수업 시작·종료 응답 등 다른 경로로 Snapshot이 바뀌어도 신호 비교 값이 따라가게 한다
  const currentProblemId = lecture?.currentProblem?.problemId ?? null
  useEffect(() => {
    currentProblemIdRef.current = currentProblemId
  }, [currentProblemId])

  async function run(action: () => Promise<void>, mapError: (e: unknown) => string = errorMessage) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(mapError(e))
    } finally {
      setBusy(false)
    }
  }

  // 응답 그대로 덮지 않고, 요청이 진행되는 동안 로컬에서 새로 등록된 문제가 있으면 되살린다
  const applyLecture = (updated: InstructorLecture) =>
    setLecture((prev) =>
      prev
        ? {
            ...updated,
            linkedProblems: reconcileLinkedProblems(updated.linkedProblems, prev.linkedProblems),
          }
        : updated,
    )

  const start = () =>
    run(async () => {
      applyLecture(await lectureApi.changeStatus(lectureId, 'OPEN'))
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

  // 출제 → 서버가 방 metadata로 학습자 앱에 알린다. 503이면 DB는 저장됐고 화면은 그대로라
  // 같은 "출제" 버튼을 다시 누르면 서버가 metadata만 다시 쓴다
  const deliver = (problemId: string) =>
    run(async () => {
      setDeliveringId(problemId)
      try {
        const updated = await lectureApi.deliverProblem(lectureId, problemId)
        applyLecture(updated)
        const current = updated.currentProblem?.problemId ?? problemId
        // 다음 렌더 전에 도착하는 답안 신호도 새 문제로 비교되게 바로 바꾼다
        currentProblemIdRef.current = current
        setDeliveredIds((prev) => new Set(prev).add(current))
        await loadAnswers(current)
      } finally {
        setDeliveringId(null)
      }
    }, problemErrorMessage)

  // 등록 응답(201 새 연결·200 이미 연결)을 목록에 합친다. 이미 있으면 목록이 늘지 않는다
  const addLinkedProblem = (problem: LinkedProblem) =>
    setLecture((prev) =>
      prev ? { ...prev, linkedProblems: mergeLinkedProblem(prev.linkedProblems, problem) } : prev,
    )

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
  const elapsedEnd =
    lecture.status === 'CLOSED' && lecture.endedAt ? Date.parse(lecture.endedAt) : now
  const elapsed = lecture.startedAt
    ? formatElapsed(elapsedEnd - Date.parse(lecture.startedAt))
    : '--:--'
  const barButton: CSSProperties = { ...buttonStyle('darkOutline', 'sm'), fontSize: 13.5 }
  const micButton: CSSProperties = {
    ...buttonStyle('darkOutline', 'sm'),
    flex: 1,
    padding: 8,
    fontSize: 12.5,
    flexShrink: 1,
  }
  const currentProblem = lecture.currentProblem
  const currentIndex = currentProblem
    ? lecture.linkedProblems.findIndex((p) => p.problemId === currentProblem.problemId)
    : -1
  // 방금 출제해 새 목록이 오기 전이면 이전 문제의 답안을 보여 주지 않는다
  const answers: StudentAnswer[] =
    currentProblem && answerSet?.problemId === currentProblem.problemId ? answerSet.answers : []
  const answersByUser = new Map(answers.map((a) => [a.userId, a]))
  const progress = progressCounts(
    students.map((s) => s.userId),
    answers,
  )
  const percent = (value: number) =>
    `${progress.connected > 0 ? (value / progress.connected) * 100 : 0}%`

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        background: c.bg,
        color: c.text,
        fontFamily: theme.font.sans,
      }}
    >
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
          <Link
            to="/lectures"
            style={{
              fontSize: 13,
              fontWeight: 700,
              color: c.darkTextSub,
              textDecoration: 'none',
              flexShrink: 0,
            }}
          >
            ← 목록
          </Link>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexShrink: 0 }}>
            <span style={{ width: 9, height: 9, borderRadius: '50%', background: badge.color }} />
            <span style={{ fontSize: 14, fontWeight: 800 }}>{badge.label}</span>
          </div>
          <div style={{ width: 1, height: 24, background: c.darkLine, flexShrink: 0 }} />
          <h1
            style={{
              margin: 0,
              fontSize: 15,
              fontWeight: 700,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
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
            경과{' '}
            <span style={{ fontFamily: mono, fontSize: 15, fontWeight: 700, color: '#FFFFFF' }}>
              {elapsed}
            </span>
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
              style={{
                ...buttonStyle('primary', 'sm'),
                fontSize: 13.5,
                boxShadow: 'none',
                ...(busy || joining ? dimmed : {}),
              }}
            >
              수업 시작
            </button>
          ) : (
            <button
              type="button"
              disabled={busy || joining}
              onClick={end}
              style={{
                ...buttonStyle('dangerOutline', 'sm'),
                fontSize: 13.5,
                ...(busy || joining ? dimmed : {}),
              }}
            >
              {endFailed ? '종료 다시 시도' : '수업 종료'}
            </button>
          )}
        </div>
      </header>

      <div
        style={{
          flex: 1,
          minHeight: 0,
          display: 'grid',
          gridTemplateColumns: `minmax(0, 1fr) ${theme.layout.livePanelWidth}px`,
        }}
      >
        <main
          style={{
            minWidth: 0,
            padding: theme.layout.pagePadding,
            display: 'flex',
            flexDirection: 'column',
            gap: 20,
          }}
        >
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

          {/* 실제: 지금 문제 (강사 Snapshot currentProblem) — 윗줄 제목·버튼, 아랫줄 칸별 정답 점형 */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'flex-start',
                flexWrap: 'wrap',
                gap: 16,
              }}
            >
              {currentProblem ? (
                // 좁으면 버튼이 먼저 다음 줄로 내려간다 — 제목이 낱말 안에서 꺾이지 않게 최소 폭을 둔다
                <div style={{ minWidth: 0, flex: '1 1 320px' }}>
                  <div style={{ fontSize: 13, color: c.textSub }}>
                    지금 문제
                    {currentIndex >= 0
                      ? ` · ${currentIndex + 1} / ${lecture.linkedProblems.length}`
                      : ''}
                  </div>
                  <div
                    style={{
                      fontSize: problemTitleSize(currentProblem.displayText),
                      fontWeight: 900,
                      letterSpacing: '-.02em',
                      lineHeight: 1.2,
                      wordBreak: 'keep-all',
                      overflowWrap: 'anywhere',
                    }}
                  >
                    {currentProblem.displayText}
                  </div>
                </div>
              ) : (
                <p style={{ margin: 0, fontSize: 16, fontWeight: 700, color: c.textSub }}>
                  오른쪽에서 문제를 골라 출제하세요
                </p>
              )}
              <div style={{ display: 'flex', gap: 12, flexShrink: 0 }}>
                <button
                  type="button"
                  disabled
                  title="준비 중"
                  style={{ ...buttonStyle('outline'), ...inactive }}
                >
                  한 번 더 읽어주기
                </button>
                <button
                  type="button"
                  disabled
                  title="준비 중"
                  style={{ ...buttonStyle('mintOutline'), fontWeight: 800, ...inactive }}
                >
                  오른쪽에서 다음 문제 선택 ▸
                </button>
              </div>
            </div>
            {currentProblem && (
              <div
                style={{
                  ...cardStyle,
                  borderRadius: theme.radius.panel,
                  padding: '12px 16px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 10,
                }}
              >
                <span style={{ fontSize: 12, color: c.textSub }}>
                  정답 점형 · {currentProblem.cellCount}칸
                </span>
                <ol
                  aria-label={`정답 점형 ${dotLabel(currentProblem.expectedPattern)}`}
                  style={{
                    listStyle: 'none',
                    margin: 0,
                    padding: 0,
                    display: 'flex',
                    flexWrap: 'wrap',
                    gap: 12,
                  }}
                >
                  {currentProblem.expectedPattern.map((pattern, i) => (
                    <li
                      key={i}
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        gap: 4,
                      }}
                    >
                      <BrailleCell dots={dotsOf(pattern)} />
                      <span style={{ fontFamily: mono, fontSize: 12, fontWeight: 700 }}>
                        {dotLabel([pattern])}
                      </span>
                    </li>
                  ))}
                </ol>
              </div>
            )}
          </div>

          {/* 실제: 제출 현황 — 답안 목록과 LiveKit 접속자로 계산 (접속 학생 기준) */}
          <div
            style={{
              ...cardStyle,
              padding: '18px 24px',
              display: 'flex',
              alignItems: 'center',
              gap: 28,
              flexShrink: 0,
            }}
          >
            <div>
              <div style={{ fontSize: 12, color: c.textSub }}>제출 / 접속 학생</div>
              <div style={{ fontFamily: mono, fontSize: 24, fontWeight: 700 }}>
                {progress.submitted} / {progress.connected}
              </div>
            </div>
            <div
              style={{
                flex: 1,
                height: 10,
                borderRadius: theme.radius.pill,
                background: c.canvas,
                overflow: 'hidden',
                display: 'flex',
              }}
            >
              <span
                style={{
                  width: percent(progress.correct),
                  height: '100%',
                  background: c.primaryTint,
                }}
              />
              <span
                style={{
                  width: percent(progress.wrong),
                  height: '100%',
                  background: c.warningTint,
                }}
              />
            </div>
            <div style={{ display: 'flex', gap: 22 }}>
              {[
                { label: '정답', value: progress.correct, color: c.primaryDark },
                { label: '오답', value: progress.wrong, color: c.warningText },
                { label: '미착수', value: progress.notStarted, color: c.textSub },
              ].map((stat) => (
                <div key={stat.label}>
                  <div style={{ fontSize: 12, color: c.textSub }}>{stat.label}</div>
                  <div
                    style={{ fontFamily: mono, fontSize: 20, fontWeight: 700, color: stat.color }}
                  >
                    {stat.value}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* 실제: 입장한 학생(LiveKit 참가자)·손들기·발언 허용·마이크·지금 문제 답안 */}
          <section
            style={{
              ...cardStyle,
              flex: 1,
              minHeight: 0,
              padding: 24,
              display: 'flex',
              flexDirection: 'column',
              gap: 14,
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                gap: 16,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>
                  학생 · {students.length}명
                </h2>
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
              <div
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  alignItems: 'center',
                  gap: 14,
                  fontSize: 12,
                  color: c.textSub,
                }}
              >
                <span style={{ fontWeight: 700 }}>답안 상태</span>
                {LEGEND.map((item) => (
                  <span key={item.label} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                    <span style={{ width: 9, height: 9, borderRadius: 2, ...item.swatch }} />
                    {item.label}
                  </span>
                ))}
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
                  gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
                  alignContent: 'start',
                  gap: 10,
                }}
              >
                {students.map((student) => (
                  <StudentCard
                    key={student.userId}
                    student={student}
                    answer={
                      currentProblem ? studentAnswerView(answersByUser.get(student.userId)) : null
                    }
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
              <span
                role="status"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  fontSize: 12,
                  color: indicator.color,
                }}
              >
                <span
                  style={{ width: 8, height: 8, borderRadius: '50%', background: indicator.color }}
                />
                {indicator.label}
              </span>
            </div>
            {/* 목: 장식용 음량 막대 — 송출 중일 때만 민트 */}
            <div
              aria-hidden="true"
              style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: 30 }}
            >
              {LEVEL_BARS.map((height, i) => (
                <span
                  key={i}
                  style={{
                    width: 6,
                    height: `${height}%`,
                    borderRadius: 2,
                    background: indicator.live ? c.primary : c.darkLine,
                  }}
                />
              ))}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              {open && roomState === 'connected' ? (
                <button
                  type="button"
                  disabled={busy}
                  onClick={toggleMic}
                  style={{ ...micButton, ...(busy ? dimmed : {}) }}
                >
                  {mic ? '마이크 끄기' : '마이크 켜기'}
                </button>
              ) : open && roomState === 'disconnected' ? (
                <button
                  type="button"
                  disabled={busy || joining}
                  onClick={reconnect}
                  style={{
                    ...micButton,
                    border: `1.5px solid ${c.primary}`,
                    background: c.primary,
                    ...(busy || joining ? dimmed : {}),
                  }}
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
            <div style={{ fontSize: 11.5, color: c.darkTextSub }}>
              발언을 허용한 학생만 말할 수 있어요 · 녹음·저장 없음
            </div>
          </section>

          {/* 실제: 문제 세트 — 글자 입력으로 연결하고 골라서 출제 (문제 은행 검색은 범위 밖이라 뺐다) */}
          <ProblemSetPanel
            key={lectureId}
            lectureId={lectureId}
            status={lecture.status}
            problems={lecture.linkedProblems}
            currentProblemId={currentProblemId}
            deliveredIds={deliveredIds}
            deliveringId={deliveringId}
            disabled={busy || joining}
            onRegistered={addLinkedProblem}
            onDeliver={(problemId) => void deliver(problemId)}
          />
        </aside>
      </div>
    </div>
  )
}
