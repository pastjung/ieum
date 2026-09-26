import { useState } from "react";
import { StyleSheet, View } from "react-native";
import type { Cell, Dot } from "@ieum/domain";
import { radius, useTheme } from "@/theme";
import { fitDotSize } from "./dotPadLayout";
import { DotButton } from "./DotButton";
import { useMultiTouchDots } from "./useMultiTouchDots";

interface Props {
  /** 한 칸 입력이 확정될 때 (모든 손가락을 떼면) 호출 */
  onCommitCell: (cell: Cell) => void;
  /** 데모 모드: 강조할 점들 (사용 안내·캘리브레이션 화면) */
  highlightDots?: Dot[];
  /**
   * 입력 잠금. 호출부가 `onCommitCell`을 버릴 상황이라면 반드시 켠다 — 켜지 않으면 패드는
   * 확정 낭독("3번 입력")과 진동을 그대로 내보내고, 사용자는 기록되지 않은 입력을
   * 기록됐다고 믿는다 (`useMultiTouchDots`의 `disabled` 주석).
   */
  disabled?: boolean;
  /** 잠긴 상태의 터치를 무시할 때 제스처마다 한 번 — 지금 상태를 읽어 주는 자리 */
  onBlockedTouch?: () => void;
}

// 점 배치: 왼쪽 위→아래 1·2·3, 오른쪽 위→아래 4·5·6 (ERD 「6점 번호」 절).
//
// 좌우로 벌어지는 들여쓰기(3%→10%→14%)는 손가락이 자연히 벌어지는 각도에 맞춘 것이라
// 그대로 둔다 — 고친 것은 그 자리에 붙는 **번호**뿐이다.
//
// 예전에는 위→아래가 3·2·1이었다. 번호는 버튼에 찍히므로 기록되는 값은 맞았지만,
// 같은 칸을 채점 화면(`BrailleCellView`, 위→아래 1·2·3)이 뒤집어 그려서 학습자에게는
// 자기가 누르지 않은 점이 찍힌 것처럼 보였다. 두 배치 중 ERD를 따르는 쪽이 표시이므로
// 패드를 맞춘다.
const positions: Record<Dot, object> = {
  1: { left: "3%", top: "5%" },
  4: { right: "3%", top: "5%" },
  2: { left: "10%", top: "42%" },
  5: { right: "10%", top: "42%" },
  3: { left: "14%", bottom: "3%" },
  6: { right: "14%", bottom: "3%" },
};

/** 세로(V) 6점 입력 패드 — 여러 점을 동시에 짚고 손을 떼면 한 칸 입력 */
export function DotPad({ onCommitCell, highlightDots, disabled, onBlockedTouch }: Props) {
  const theme = useTheme();
  const { active, padHandlers, onMeasureDot } = useMultiTouchDots(onCommitCell, {
    disabled,
    onBlocked: onBlockedTouch,
  });

  const [padHeight, setPadHeight] = useState(0);
  const size = fitDotSize(padHeight);

  return (
    <View
      {...padHandlers}
      onLayout={(e) => setPadHeight(e.nativeEvent.layout.height)}
      style={[styles.pad, { borderColor: theme.color.border, borderRadius: radius.card }]}
      accessibilityLabel={
        disabled
          ? "점자 입력 영역. 지금은 입력할 수 없어요."
          : "점자 입력 영역. 1번부터 6번까지 점이 있어요. 점을 동시에 짚고 손을 떼면 한 칸이 입력돼요."
      }
    >
      {([1, 4, 2, 5, 3, 6] as Dot[]).map((dot) => (
        <DotButton
          key={dot}
          dot={dot}
          size={size}
          on={active.has(dot) || !!highlightDots?.includes(dot)}
          onMeasure={onMeasureDot}
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
