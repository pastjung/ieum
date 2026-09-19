import { StyleSheet, View } from "react-native";
import type { Cell, Dot } from "@ieum/domain";
import { dotButtonSize, radius, useTheme } from "@/theme";
import { DotButton } from "./DotButton";
import { useDotGesture } from "./useDotGesture";

interface Props {
  /** 한 칸 입력이 확정될 때 (모든 손가락을 떼면) 호출 */
  onCommitCell: (cell: Cell) => void;
  /** 데모 모드: 강조할 점들 (사용 안내·캘리브레이션 화면) */
  highlightDots?: Dot[];
}

// 와이어프레임 V14 배치: 왼쪽 위→아래 3·2·1, 오른쪽 위→아래 6·5·4
const positions: Record<Dot, object> = {
  3: { left: "3%", top: "5%" },
  6: { right: "3%", top: "5%" },
  2: { left: "10%", top: "42%" },
  5: { right: "10%", top: "42%" },
  1: { left: "14%", bottom: "3%" },
  4: { right: "14%", bottom: "3%" },
};

/** 세로(V) 6점 입력 패드 — 점을 동시에 짚고 손을 떼면 한 칸 입력 */
export function DotPad({ onCommitCell, highlightDots }: Props) {
  const theme = useTheme();
  const { active, pressIn, pressOut } = useDotGesture(onCommitCell);

  return (
    <View
      style={[styles.pad, { borderColor: theme.color.border, borderRadius: radius.card }]}
      accessibilityLabel="점자 입력 영역. 1번부터 6번까지 점 버튼이 있어요. 점을 동시에 짚고 손을 떼면 한 칸이 입력돼요."
    >
      {([3, 6, 2, 5, 1, 4] as Dot[]).map((dot) => (
        <DotButton
          key={dot}
          dot={dot}
          size={dotButtonSize}
          on={active.has(dot) || !!highlightDots?.includes(dot)}
          selected={active.has(dot)}
          onPressIn={() => pressIn(dot)}
          onPressOut={() => pressOut(dot)}
          style={positions[dot]}
        />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  pad: {
    flex: 1,
    minHeight: 0,
    borderWidth: 2,
    borderStyle: "dashed",
    position: "relative",
  },
});
