import { useCallback, useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { router, useLocalSearchParams, useNavigation } from "expo-router";
import { ApiError } from "@ieum/api-client";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { classService } from "@/services/classService";
import { liveLecture } from "@/services/liveLecture";
import { isUserBackAction } from "@/services/lectureSignals";
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
  // 파라미터가 없거나 비어 있으면 입장할 방을 알 수 없다 — undefined로 join하지 않는다
  const validLectureId = typeof lectureId === "string" && lectureId.trim() !== "" ? lectureId : null;
  // retryable이 false면 다시 시도해도 같은 결과라 "다시 시도" 대신 나가기만 권한다
  const [failed, setFailed] = useState<{ message: string; retryable: boolean } | null>(null);
  // 언마운트(풀이 화면으로 이동 등) 뒤에는 상태를 바꾸거나 화면을 옮기지 않는다. onExit은
  // 그대로 살려 둔다 — 대기 화면을 떠난 뒤에도 liveLecture 쪽 handlers는 계속 불릴 수 있다.
  const mountedRef = useRef(true);
  // "입장 완료" 안내는 화면에 들어올 때 한 번만 읽는다. 다시 시도할 때마다 되풀이하지 않는다.
  // 실패 안내는 이 낭독이 끝난 뒤에 읽는다 — 겹치면 announce가 입장 안내를 중간에 끊는다
  const introRef = useRef<Promise<void> | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // 하드웨어 뒤로 가기·제스처로 떠나도 "수업 나가기"처럼 음성 방을 정리한다. 풀이·종료·끊김
  // 화면으로의 이동은 앱이 router.replace(REPLACE)로 하므로 방을 그대로 둔다
  const navigation = useNavigation();
  useEffect(
    () =>
      navigation.addListener("beforeRemove", (event) => {
        if (isUserBackAction(event.data.action)) void liveLecture.leave();
      }),
    [navigation],
  );

  const attempt = useCallback(() => {
    if (validLectureId === null) {
      const message = "수업 정보를 찾지 못했어요. 수업 나가기를 눌러 주세요.";
      setFailed({ message, retryable: false });
      announce(message);
      return;
    }
    setFailed(null);
    if (introRef.current === null) {
      void hapticSuccess();
      introRef.current = speakAsync(`입장 완료. ${className}, 곧 시작해요. 선생님이 시작하면 알려 드릴게요.`);
    }
    const intro = introRef.current;
    const fail = (message: string, retryable: boolean) => {
      setFailed({ message, retryable });
      void intro.then(() => {
        if (mountedRef.current) announce(message);
      });
    };
    liveLecture
      .join(validLectureId, {
        onOpen: () => {
          // 문제 출제는 아직 목이다 — 수업이 시작되면 목 문제로 풀이를 시작한다
          void classService.getClassSession().then((session) => {
            // getClassSession은 300ms 뒤에 응답한다 — 그 사이 방이 끊겨 onExit이 이미
            // /class/end나 /class/disconnected로 옮겼을 수 있다. 이 화면이 언마운트됐거나
            // liveLecture가 더 이상 이 lectureId를 살아 있는 세션으로 보지 않으면(onExit이
            // 나가면서 지운다) 늦게 온 시작 알림을 버린다 — 아니면 끝난 수업으로 되돌아간다.
            if (!mountedRef.current || liveLecture.currentLectureId() !== validLectureId) return;
            start(session, "class");
            router.replace("/class/play");
          });
        },
        onExit: (exit) => {
          if (exit === "ended") router.replace("/class/end");
          else if (exit === "lost") router.replace({ pathname: "/class/disconnected", params: { lectureId: validLectureId, title } });
        },
      })
      .catch((error: unknown) => {
        if (!mountedRef.current) return;
        // 참여 기록이 없어 토큰이 거부됐다 — 다시 시도해도 같으니 코드 재입장을 안내한다
        if (error instanceof ApiError && error.code === "LECTURE_PARTICIPATION_REQUIRED") {
          fail("코드로 다시 입장해 주세요.", false);
          return;
        }
        fail("음성 수업에 연결하지 못했어요. 다시 시도해 주세요.", true);
      });
  }, [validLectureId, title, className, start]);

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
        {!failed && <Text style={{ fontSize: 15, fontWeight: "700", color: theme.color.primary }}>입장 완료</Text>}
        {failed ? (
          <>
            <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text, textAlign: "center" }}>
              연결하지{"\n"}못했어요
            </Text>
            <Text accessibilityLiveRegion="polite" style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
              {failed.message}
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
        {failed?.retryable && <AppButton label="다시 시도" onPress={attempt} />}
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
