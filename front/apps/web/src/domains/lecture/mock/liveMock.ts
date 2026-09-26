/**
 * 03 실시간 수업 화면의 목 데이터. 문제 출제·답안·학생 진행 상태 API는 MVP 범위 밖이다.
 * 학습자는 음성 방에서 숨김 참가자라 실제 입장 여부도 여기서는 알 수 없다.
 * 값은 와이어프레임 `이음 강사웹.html` 03 화면을 그대로 옮겼다.
 */
export type MockStudentState = 'correct' | 'wrong' | 'idle' | 'absent'

export interface MockStudent {
  name: string
  state: MockStudentState
  /** 제출한 점자(유니코드 점자 문자). 미착수·미입장은 null */
  glyph: string | null
  /** 정답: 걸린 시간, 오답: 틀린 이유, 그 외: 상태 문구 */
  detail: string
  /** 카드 우측 아래 점 — 점자 출력 장치 연결 여부 */
  deviceConnected: boolean
}

export const mockStudents: MockStudent[] = [
  { name: '강민서', state: 'correct', glyph: '⠛⠊', detail: '1.4초', deviceConnected: true },
  { name: '고은우', state: 'correct', glyph: '⠛⠊', detail: '2.1초', deviceConnected: true },
  { name: '김도윤', state: 'wrong', glyph: '⠓⠊', detail: '6점 누락', deviceConnected: false },
  { name: '김서아', state: 'correct', glyph: '⠛⠊', detail: '1.9초', deviceConnected: true },
  { name: '김유진', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '김하율', state: 'correct', glyph: '⠛⠊', detail: '2.6초', deviceConnected: true },
  { name: '문채원', state: 'wrong', glyph: '⠊⠛', detail: '칸 순서 바뀜', deviceConnected: true },
  { name: '박지호', state: 'correct', glyph: '⠛⠊', detail: '3.4초', deviceConnected: true },
  { name: '배시윤', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '서예린', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '성지안', state: 'correct', glyph: '⠛⠊', detail: '2.2초', deviceConnected: true },
  { name: '신하준', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '안소율', state: 'correct', glyph: '⠛⠊', detail: '1.7초', deviceConnected: true },
  { name: '오시우', state: 'wrong', glyph: '⠟⠊', detail: '3점 추가', deviceConnected: true },
  { name: '유하린', state: 'idle', glyph: null, detail: '미착수', deviceConnected: false },
  { name: '이서준', state: 'correct', glyph: '⠛⠊', detail: '4.1초', deviceConnected: true },
  { name: '이주원', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '장하빈', state: 'correct', glyph: '⠛⠊', detail: '2.8초', deviceConnected: true },
  { name: '정예준', state: 'wrong', glyph: '⠓⠊', detail: '6점 누락', deviceConnected: true },
  { name: '조윤서', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '최하은', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '한지우', state: 'correct', glyph: '⠛⠊', detail: '3.0초', deviceConnected: true },
  { name: '홍시현', state: 'idle', glyph: null, detail: '미착수', deviceConnected: true },
  { name: '황도현', state: 'absent', glyph: null, detail: '미입장', deviceConnected: false },
  { name: '황서진', state: 'absent', glyph: null, detail: '미입장', deviceConnected: false },
]

/** 제출 진행 카드 숫자 (와이어프레임 표기 그대로) */
export const mockProgress = { submitted: 14, total: 24, correct: 10, wrong: 4, idle: 10 }

export const mockCurrentProblem = {
  order: '3 / 14',
  text: '그리고',
  answerLabel: '1·3·5 / 2·4·6',
  /** 칸별 볼록한 점 번호(1~6). 그림은 answerLabel과 같은 점형으로 그린다 */
  answerCells: [
    [1, 3, 5],
    [2, 4, 6],
  ],
}

export type MockSetItemState = 'done' | 'current' | 'ready'

export interface MockSetItem {
  text: string
  state: MockSetItemState
  /** 출제됨 항목의 반 정답률 */
  rate?: string
}

export const mockProblemSet: MockSetItem[] = [
  { text: '그래서', state: 'done', rate: '88%' },
  { text: '그러나', state: 'done', rate: '71%' },
  { text: '그리고', state: 'current' },
  { text: '그러면', state: 'ready' },
  { text: '성', state: 'ready' },
  { text: '까', state: 'ready' },
]

export const mockProblemSetTotal = 14
export const mockProblemSetMore = 8

export const mockBankResult = { text: '정', glyph: '⠸⠻', note: "된소리 'ㅆ' · 반 정답률 50%" }
