import { useEffect } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQueryClient } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { ListRow } from "@/components/ListRow";
import { useSettingsStore } from "@/stores/settingsStore";
import { authService } from "@/services/authService";
import { announce, hapticError, hapticSelect, speakAsync } from "@/a11y";
import { controlHeight, radius, useFontScale, useTheme } from "@/theme";

/** 낭독이 무응답이어도 화면 전환을 막지 않을 상한 */
const SPEAK_WAIT_MS = 3000;

/**
 * 문장을 끝까지 읽어 준 뒤 화면을 넘기되, **그 기다림에 갇히지 않는다.**
 *
 * `speakAsync`의 TTS 경로는 expo-speech의 onDone·onStopped·onError에만 의존한다.
 * 셋 중 무엇도 불리지 않으면 promise가 영영 settle되지 않고, `Speech.speak`가 던지면 reject된다.
 * 어느 쪽이든 호출처의 화면 전환이 통째로 건너뛰어져 "버튼을 눌렀는데 아무 일도 안 일어남"이 된다 —
 * 실패를 들려주려고 넣은 낭독이 그 실패를 다시 침묵시키는 셈이다.
 * 거부는 흡수하고, 무응답은 상한에서 끊는다.
 */
async function speakBeforeLeaving(text: string): Promise<void> {
  try {
    await Promise.race([speakAsync(text), new Promise<void>((resolve) => setTimeout(resolve, SPEAK_WAIT_MS))]);
  } catch {
    // 낭독이 실패한 것은 화면 전환을 막을 이유가 되지 않는다
  }
}

/** V27 설정 */
export default function Settings() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const s = useSettingsStore();
  const queryClient = useQueryClient();

  useEffect(() => {
    announce("설정이에요.");
  }, []);

  const logOut = async () => {
    try {
      await authService.signOut();
      announce("로그아웃했어요.");
    } catch {
      // 기기 보안 저장소 정리가 거부돼도 메모리의 Access Token은 이미 지워졌다 —
      // 이 기기의 로그아웃은 성립하므로 화면 전환은 반드시 진행한다.
      // 조용히 넘어가면 사용자는 로그아웃됐다고 잘못 믿는다.
      // announce()는 다음 화면의 진입 안내가 Speech.stop()으로 즉시 잘라내므로
      // 이 문장만큼은 낭독이 끝날 때까지 기다렸다가 이동한다
      void hapticError();
      await speakBeforeLeaving("기기에서는 로그아웃했어요. 다만 저장된 로그인 정보를 지우지 못했어요.");
    }
    // QueryClient는 앱 프로세스 수명 동안 하나다. 캐시를 남기면 같은 기기에서 다른 계정으로
    // 로그인했을 때 홈이 이전 사용자의 이름·단계를 낭독한다 — 타인의 정보이자 거짓이다.
    // 화면 전환 직전에 비워, 아직 붙어 있는 홈 화면이 재조회를 일으키지 않게 한다
    queryClient.clear();
    router.replace("/auth/sign-in");
  };

  const setTheme = (name: "dark" | "green") => {
    void hapticSelect();
    s.setTheme(name);
    announce(name === "dark" ? "다크 테마로 바꿨어요" : "그린 테마로 바꿨어요");
  };

  return (
    <ScreenShell noBottomPadding>
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", color: theme.color.text }}>
        설정
      </Text>
      <ScrollView contentContainerStyle={{ gap: 12, paddingBottom: 14 }} showsVerticalScrollIndicator={false}>
        <ListRow label="소리와 진동" chevron accessibilityHint="음성 안내와 진동을 설정해요" onPress={() => router.push({ pathname: "/onboarding/accessibility", params: { from: "settings" } })} />
        <ListRow
          label={`사용 방식 · ${s.mode === "screen" ? "화면 중심" : "음성 중심"}`}
          chevron
          accessibilityHint="누르면 사용 방식이 바뀌어요"
          onPress={() => {
            const next = s.mode === "screen" ? "voice" : "screen";
            s.setMode(next);
            announce(next === "voice" ? "음성 중심으로 바꿨어요" : "화면 중심으로 바꿨어요");
          }}
        />
        <ListRow label="점자 장치 · 연결 안 됨" chevron accessibilityHint="장치 연결 상태를 확인해요" onPress={() => router.push("/device-error")} />
        <ListRow label="손 캘리브레이션" chevron accessibilityHint="여섯 점 위치를 다시 익혀요" onPress={() => router.push("/calibration")} />
        <View
          style={[styles.themeRow, { borderColor: theme.color.border, minHeight: controlHeight.row, borderRadius: radius.control }]}
          accessibilityLabel={`화면 테마, 현재 ${s.theme === "dark" ? "다크" : "그린"}`}
        >
          <Text style={{ fontSize: 19 * fontScale, fontWeight: "700", color: theme.color.text }}>화면 테마</Text>
          <View style={{ flexDirection: "row", gap: 6 }}>
            {(["dark", "green"] as const).map((name) => {
              const selected = s.theme === name;
              return (
                <Pressable
                  key={name}
                  accessibilityRole="button"
                  accessibilityLabel={`${name === "dark" ? "다크" : "그린"} 테마`}
                  accessibilityState={{ selected }}
                  onPress={() => setTheme(name)}
                  style={[
                    styles.themeChip,
                    selected
                      ? { backgroundColor: theme.color.primary }
                      : { borderWidth: 2, borderColor: theme.color.border },
                  ]}
                >
                  <Text style={{ fontSize: 14, fontWeight: "800", color: selected ? theme.color.onPrimary : theme.color.textMuted }}>
                    {name === "dark" ? "Dark" : "Green"}
                  </Text>
                </Pressable>
              );
            })}
          </View>
        </View>
        <ListRow label="사용 안내 다시 보기" chevron onPress={() => router.push({ pathname: "/onboarding/guide", params: { from: "settings" } })} />
        <ListRow
          label="로그아웃"
          chevron
          accessibilityHint="로그아웃하고 로그인 화면으로 가요"
          onPress={() => void logOut()}
        />
        <ListRow label="회원 탈퇴" chevron accessibilityHint="계정을 삭제하는 화면으로 가요" onPress={() => router.push("/account/withdraw")} />
      </ScrollView>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  themeRow: {
    borderWidth: 2,
    paddingHorizontal: 18,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  themeChip: {
    borderRadius: 99,
    paddingHorizontal: 14,
    paddingVertical: 6,
    alignItems: "center",
    justifyContent: "center",
    minHeight: 36,
  },
});
