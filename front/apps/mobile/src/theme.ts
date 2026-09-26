import {
  themes,
  spacing,
  radius,
  controlHeight,
  fontSize,
  dotButtonSize,
  type IeumTheme,
} from "@ieum/design-tokens";
import { useSettingsStore } from "@/stores/settingsStore";

export { spacing, radius, controlHeight, fontSize, dotButtonSize };
export type { IeumTheme };

/** 현재 설정된 테마 토큰 (Dark 기본) */
export function useTheme(): IeumTheme {
  const name = useSettingsStore((s) => s.theme);
  return themes[name];
}

/** 글자 크기 설정 반영 배율 */
export function useFontScale(): number {
  const scale = useSettingsStore((s) => s.fontScale);
  return scale === "large" ? 1.15 : 1;
}
