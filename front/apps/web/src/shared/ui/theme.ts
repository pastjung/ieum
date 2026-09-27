/**
 * 강사 웹 디자인 토큰. 원본: 와이어프레임 `이음 강사웹.html`(라이트 민트) 01a·02·03의 인라인 style 값.
 * 인라인 style에서 쓴다. 값을 바꾸면 와이어프레임과 나란히 비교해 확인한다.
 */
export const theme = {
  color: {
    canvas: '#F0F1F3', // 로그인 배경·진행 막대 트랙
    bg: '#F7F8FA', // 화면 배경·종료 행·미착수 카드·문제 은행 카드
    surface: '#FFFFFF',
    border: '#DCE0E6',
    borderSoft: '#E4EAED', // 미착수 카드 테두리
    borderStrong: '#B4BCC4', // 빈 점·미입장 카드 테두리
    fill: '#EDF0F2', // 점형 칸·출제된 문항
    text: '#16181D',
    textSub: '#5B626D',
    textMuted: '#8A939B',
    primary: '#00C896',
    primaryStrong: '#00A87E', // 버튼 그림자·강조 테두리
    primaryDark: '#046B52', // 민트 위 글자·활성 탭
    primarySoft: '#E3FBF4', // 진행 중 행·활성 사이드바·정답 카드
    primaryTint: '#B9EFDF', // 진행 막대 정답 구간
    onPrimary: '#FFFFFF',
    live: '#F6465D', // 수업 중 점·수업 종료 버튼·장치 끊김 점
    danger: '#C43549', // 오류 문구
    warningSoft: '#FFF7D6', // 오답 카드
    warningTint: '#FFE99B', // 진행 막대 오답 구간
    warningLine: '#FFD400', // 오답 범례 테두리·재연결 중
    warningText: '#8A6D00', // 오답 글자
    dark: '#16181D', // 03 상단 바·내 마이크 카드
    darkRaised: '#2C2C30', // 03 참여 코드 칩
    darkLine: '#48484C', // 03 어두운 버튼 테두리·구분선
    darkTextSub: '#B9B9BD', // 03 어두운 배경 위 보조 글자
  },
  radius: { small: 6, control: 8, panel: 10, card: 12, pill: 99 },
  font: {
    sans: "'Noto Sans KR', sans-serif",
    mono: "'IBM Plex Mono', monospace", // 코드·숫자·타이머
  },
  layout: { headerHeight: 64, sidebarWidth: 240, pagePadding: 32, livePanelWidth: 420 },
} as const
