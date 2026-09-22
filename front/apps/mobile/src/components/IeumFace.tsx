import { View } from "react-native";
import { useTheme } from "@/theme";

export type FaceExpression = "happy" | "worried" | "frown";

interface Props {
  /** 정사각형 한 변 (기본 130) */
  size?: number;
  expression?: FaceExpression;
  accessibilityLabel?: string;
}

/**
 * 이음 캐릭터 얼굴 — 노란 라운드 사각형 + 눈 + 입 (와이어프레임 로고).
 * happy: 점 눈 + 웃는 입 / worried: 동그란 눈 + 벌린 입 / frown: 점 눈 + 시무룩한 입
 */
export function IeumFace({ size = 130, expression = "happy", accessibilityLabel }: Props) {
  const theme = useTheme();
  const dark = "#0B0B0C";
  const eyeTop = size * 0.34;
  const eyeSide = size * 0.24;

  const dotEye = (side: "left" | "right") => (
    <View
      style={{
        position: "absolute",
        [side]: eyeSide,
        top: eyeTop,
        width: size * 0.123,
        height: size * 0.123,
        borderRadius: size,
        backgroundColor: dark,
      }}
    />
  );

  const ringEye = (side: "left" | "right") => (
    <View
      style={{
        position: "absolute",
        [side]: size * 0.2,
        top: size * 0.28,
        width: size * 0.17,
        height: size * 0.17,
        borderRadius: size,
        borderWidth: size * 0.054,
        borderColor: dark,
      }}
    />
  );

  return (
    <View
      accessibilityLabel={accessibilityLabel ?? "이음 캐릭터"}
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.29,
        backgroundColor: theme.color.primary,
        position: "relative",
      }}
    >
      {expression === "worried" ? ringEye("left") : dotEye("left")}
      {expression === "worried" ? ringEye("right") : dotEye("right")}
      {expression === "happy" && (
        <View
          style={{
            position: "absolute",
            alignSelf: "center",
            bottom: size * 0.2,
            width: size * 0.37,
            height: size * 0.185,
            borderWidth: size * 0.062,
            borderTopWidth: 0,
            borderColor: dark,
            borderBottomLeftRadius: size * 0.37,
            borderBottomRightRadius: size * 0.37,
          }}
        />
      )}
      {expression === "worried" && (
        <View
          style={{
            position: "absolute",
            alignSelf: "center",
            bottom: size * 0.17,
            width: size * 0.23,
            height: size * 0.28,
            borderRadius: size,
            borderWidth: size * 0.062,
            borderColor: dark,
          }}
        />
      )}
      {expression === "frown" && (
        <View
          style={{
            position: "absolute",
            alignSelf: "center",
            bottom: size * 0.15,
            width: size * 0.37,
            height: size * 0.185,
            borderWidth: size * 0.062,
            borderBottomWidth: 0,
            borderColor: dark,
            borderTopLeftRadius: size * 0.37,
            borderTopRightRadius: size * 0.37,
          }}
        />
      )}
    </View>
  );
}
