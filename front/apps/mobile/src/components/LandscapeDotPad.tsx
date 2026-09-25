import type { ReactNode } from "react";
import { StyleSheet, View } from "react-native";
import type { Cell, Dot } from "@ieum/domain";
import { radius, useTheme } from "@/theme";
import { DotButton } from "./DotButton";
import { useMultiTouchDots } from "./useMultiTouchDots";

interface Props {
  onCommitCell: (cell: Cell) => void;
  /** 가운데 세로 컨트롤 (다시 듣기·입력 완료 등) */
  center: ReactNode;
  /** 입력 잠금 — 세로 패드(`DotPad`)의 같은 이름 prop과 같은 규칙이다 */
  disabled?: boolean;
  /** 잠긴 상태의 터치를 무시할 때 제스처마다 한 번 — 지금 상태를 읽어 주는 자리 */
  onBlockedTouch?: () => void;
}

const SIZE = 96;

// 와이어프레임 V14b 배치 — 양손 파지: 왼손 3·2·1, 오른손 6·5·4
const leftPos: Partial<Record<Dot, object>> = {
  3: { left: "6%", top: "8%" },
  2: { left: "30%", top: "34%" },
  1: { left: "54%", bottom: "10%" },
};
const rightPos: Partial<Record<Dot, object>> = {
  6: { right: "6%", top: "8%" },
  5: { right: "30%", top: "34%" },
  4: { right: "54%", bottom: "10%" },
};

/**
 * 가로 두 손 6점 입력 패드 (V14b) — 세로 패드와 같은 제스처 규칙.
 * 두 영역에 걸친 동시 입력을 위해 행 전체가 하나의 터치 영역으로 동작한다.
 * 가운데 버튼은 점 입력 중이 아닐 때 누른다.
 */
export function LandscapeDotPad({ onCommitCell, center, disabled, onBlockedTouch }: Props) {
  const theme = useTheme();
  const { active, padHandlers, onMeasureDot } = useMultiTouchDots(onCommitCell, {
    disabled,
    onBlocked: onBlockedTouch,
  });

  const zone = (dots: Dot[], pos: Partial<Record<Dot, object>>, label: string) => (
    <View
      style={[styles.zone, { borderColor: theme.color.border, borderRadius: radius.card }]}
      accessibilityLabel={label}
    >
      {dots.map((dot) => (
        <DotButton key={dot} dot={dot} size={SIZE} on={active.has(dot)} onMeasure={onMeasureDot} style={pos[dot]!} />
      ))}
    </View>
  );

  return (
    <View {...padHandlers} style={styles.row}>
      {zone(
        [3, 2, 1],
        leftPos,
        disabled ? "왼손 입력 영역. 지금은 입력할 수 없어요." : "왼손 입력 영역. 위에서부터 3번, 2번, 1번 점이에요.",
      )}
      <View style={styles.center}>{center}</View>
      {zone(
        [6, 5, 4],
        rightPos,
        disabled ? "오른손 입력 영역. 지금은 입력할 수 없어요." : "오른손 입력 영역. 위에서부터 6번, 5번, 4번 점이에요.",
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flex: 1, minHeight: 0, flexDirection: "row", gap: 16 },
  zone: { flex: 1, borderWidth: 2, borderStyle: "dashed", position: "relative" },
  center: { width: 150, gap: 10 },
});
