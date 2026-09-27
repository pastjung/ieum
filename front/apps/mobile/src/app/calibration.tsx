import { useEffect, useState } from "react";
import { Text } from "react-native";
import { useRouter } from "expo-router";
import { cellToDots, type Dot } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { DotPad } from "@/components/DotPad";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

const order: Dot[] = [1, 2, 3, 4, 5, 6];

/** V06 손 캘리브레이션 (확장) — 1번부터 6번까지 순서대로 짚어 위치를 익힌다 */
export default function Calibration() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const [step, setStep] = useState(0);
  const target = order[step];

  useEffect(() => {
    if (target) announce(`여섯 손가락을 편한 곳에 올려요. ${target}번 점을 짚었다 떼 보세요.`);
  }, [target]);

  const onCommit = (cell: number) => {
    if (!target) return;
    const dots = cellToDots(cell);
    if (dots.length === 1 && dots[0] === target) {
      void hapticSuccess();
      if (step + 1 >= order.length) {
        announce("캘리브레이션 완료! 여섯 점 위치를 모두 확인했어요.");
        router.back();
      } else {
        setStep(step + 1);
      }
    } else {
      void hapticError();
      announce(`${target}번 점을 짚어 보세요. 지금은 ${dots.join("번, ")}번을 눌렀어요.`);
    }
  };

  return (
    <ScreenShell>
      <BackHeader title="손 캘리브레이션" />
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
        여섯 손가락을{"\n"}편한 곳에 올려요
      </Text>
      <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted }}>
        {target ? `${target}번 점을 짚었다 떼 보세요. (${step + 1} / ${order.length})` : ""}
      </Text>
      <DotPad highlightDots={target ? [target] : []} onCommitCell={onCommit} />
    </ScreenShell>
  );
}
