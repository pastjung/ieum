/** 강사 웹이 쓰는 수업 응답 (API 명세 8.2·8.5절). ID는 10진수 문자열이다 */
export type LectureStatus = 'SCHEDULED' | 'OPEN' | 'CLOSED'

export interface InstructorLecture {
  lectureId: string
  title: string
  description: string | null
  status: LectureStatus
  scheduledAt: string | null
  startedAt: string | null
  endedAt: string | null
  createdAt: string
  participationCode: string
  /** 지금 출제된 문제. 없으면 null */
  currentProblem: LinkedProblem | null
  /** 이 수업에 연결된 문제 (연결 순서) */
  linkedProblems: LinkedProblem[]
}

/** 수업 목록 한 줄 — 목록 API에만 붙는 집계 값 (API 명세 8.2절) */
export interface LectureListItem extends InstructorLecture {
  /** 연결된 문제 수 */
  problemCount: number
  /** 지금 입장해 있는 학생 수 (퇴장하지 않은 참여자) */
  joinedCount: number
  /** 한 번이라도 입장한 학생 수 */
  participantCount: number
  /** 이 수업 LIVE 답안의 정답률(0~100, 반올림). 제출이 없으면 null */
  correctRate: number | null
}

export interface LecturePage {
  items: LectureListItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface MediaToken {
  serverUrl: string
  token: string
  expiresAt: string
}

export interface CreateLectureInput {
  title: string
  description?: string
  scheduledAt?: string
}

/** 발언 허용·회수 응답 (API 명세 8.5절). 화면은 이 응답이 아니라 LiveKit 권한 변경 이벤트로 갱신한다 */
export interface SpeakingResult {
  userId: string
  allowed: boolean
}

/** problems.level */
export type ProblemType = 'CONSONANT' | 'VOWEL' | 'CONTRACTION' | 'CONTRACTION_WORD' | 'WORD' | 'SENTENCE'

/** 수업에 연결된 문제. 문제 등록 응답·강사 Snapshot에 쓴다 */
export interface LinkedProblem {
  problemId: string
  targetText: string
  /** 구분어가 필요한 문제(초성·받침·모음·음절)는 구분어를 붙인 표시용 문자열 */
  displayText: string
  problemType: ProblemType
  cellCount: number
  /** 칸별 정답 점형 (6비트, bit0 = 1점) */
  expectedPattern: number[]
}

/** 칸별 채점. 칸은 max(입력, 정답) 길이이고 없는 쪽은 0 */
export interface AnswerCell {
  cellIndex: number
  inputPattern: number
  expectedPattern: number
  diffMask: number
  isCorrect: boolean
}

export interface StudentAnswer {
  userId: string
  username: string
  isCorrect: boolean
  attemptedAt: string
  cells: AnswerCell[]
}

/** 강사 답안 목록. 제출한 학습자만 담는다 */
export interface ProblemAnswers {
  problemId: string
  answers: StudentAnswer[]
}

/** 409 PROBLEM_TEXT_AMBIGUOUS 후보. input으로 다시 등록한다 */
export interface ProblemCandidate {
  input: string
  displayText: string
}
