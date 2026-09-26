import { useCallback, useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { router, useLocalSearchParams } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { classService } from "@/services/classService";
import { liveLecture } from "@/services/liveLecture";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticSuccess, speakAsync } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V22 수업 대기 — 선생님이 시작하면 자동 입장 */
export default function ClassWaiting() {
  const theme = useTheme();
  const fontScale = useFontScale();
  const { lectureId, title } = useLocalSearchParams<{ lectureId: string; title?: string }>();
  const start = useSessionStore((s) => s.start);
  const className = title ?? "수업";
  const [failed, setFailed] = useState<string | null>(null);
  // 언마운트(풀이 화면으로 이동 등) 뒤에는 상태를 바꾸거나 화면을 옮기지 않는다. onExit은
  // 그대로 살려 둔다 — 대기 화면을 떠난 뒤에도 liveLecture 쪽 handlers는 계속 불릴 수 있다.
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const attempt = useCallback(() => {
    setFailed(null);
    void hapticSuccess();
    void speakAsync(`입장 완료. ${className}, 곧 시작해요. 선생님이 시작하면 알려 드릴게요.`);
    liveLecture
      .join(lectureId, {
        onOpen: () => {
          // 문제 출제는 아직 목이다 — 수업이 시작되면 목 문제로 풀이를 시작한다
          void classService.getClassSession().then((session) => {
            start(session, "class");
            router.replace("/class/play");
          });
        },
        onExit: (exit) => {
          if (exit === "ended") router.replace("/class/end");
          else if (exit === "lost") router.replace({ pathname: "/class/disconnected", params: { lectureId, title } });
        },
      })
      .catch(() => {
        if (!mountedRef.current) return;
        const message = "음성 수업에 연결하지 못했어요. 다시 시도해 주세요.";
        setFailed(message);
        announce(message);
      });
  }, [lectureId, title, className, start]);

  useEffect(() => {
    attempt();
  }, [attempt]);

  const leaveAndBack = async () => {
    await liveLecture.leave();
    router.back();
  };

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} expression={failed ? "worried" : undefined} accessibilityLabel={failed ? "연결 실패 캐릭터" : "대기 중 캐릭터"} />
        <Text style={{ fontSize: 15, fontWeight: "700", color: theme.color.primary }}>입장 완료</Text>
        {failed ? (
          <>
            <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text, textAlign: "center" }}>
              연결하지{"\n"}못했어요
            </Text>
            <Text accessibilityLiveRegion="polite" style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
              {failed}
            </Text>
          </>
        ) : (
          <>
            <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
              {className}{"\n"}곧 시작해요
            </Text>
            <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29 }}>
              선생님이 시작하면 알려 드릴게요.
            </Text>
          </>
        )}
      </View>
      <View style={{ gap: 12 }}>
        {failed && <AppButton label="다시 시도" onPress={attempt} />}
        <AppButton
          label="수업 나가기"
          variant={failed ? "secondary" : "primary"}
          onPress={() => void leaveAndBack()}
          accessibilityHint="대기를 취소하고 수업 참여 화면으로 돌아가요"
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
