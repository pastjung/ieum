/** 이음 디자인 토큰 — 학습자 앱 (Dark 우선, Green은 동일 구조로 추후 값 확정) */

export interface IeumTheme {
  name: "dark" | "green";
  color: {
    bg: string;
    surface: string;
    border: string;
    text: string;
    textMuted: string;
    primary: string;
    onPrimary: string;
  };
  /** B모드(Green) 하드 섀도 — 버튼·카드 아래 4px 단색 그림자. 없으면 플랫(Dark). */
  shadow?: {
    primary: string;
    control: string;
  };
}

/** 시각장애인용 Dark 와이어프레임 기준 팔레트 (본문 대비 7:1 이상) */
export const darkTheme: IeumTheme = {
  name: "dark",
  color: {
    bg: "#0B0B0C",
    surface: "#1C1C1E",
    border: "#48484C",
    text: "#FFFFFF",
    textMuted: "#B9B9BD",
    primary: "#FFD400",
    onPrimary: "#111111",
  },
};

/** 시각장애인용 Green 와이어프레임 기준 라이트 팔레트 */
export const greenTheme: IeumTheme = {
  name: "green",
  color: {
    bg: "#FFFFFF",
    surface: "#EDF1F3",
    border: "#E4EAED",
    text: "#16181D",
    textMuted: "#7C8A93",
    primary: "#00C896",
    onPrimary: "#FFFFFF",
  },
  shadow: {
    primary: "#00A87E",
    control: "#DCE3E7",
  },
};

export const themes = { dark: darkTheme, green: greenTheme } as const;
export type ThemeName = keyof typeof themes;

export const spacing = { xs: 8, sm: 12, md: 16, lg: 20, xl: 28 } as const;

export const radius = { control: 14, card: 16, screen: 24, pill: 99 } as const;

/** 조작 요소 최소 높이 — 계약: 조작 요소 56dp 이상 */
export const controlHeight = {
  primary: 72,
  secondary: 60,
  compact: 56,
  row: 64,
} as const;

/** 6점 패드 점 버튼 최소 지름 — 계약: 92px 이상 */
export const dotButtonSize = 112;

export const fontSize = {
  display: 30,
  title: 26,
  heading: 22,
  button: 24,
  buttonSecondary: 19,
  body: 17,
  caption: 15,
} as const;

export const fontWeight = {
  black: "900",
  extraBold: "800",
  bold: "700",
  medium: "500",
} as const;
