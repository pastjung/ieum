import { useEffect } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { ListRow } from "@/components/ListRow";
import { useSettingsStore } from "@/stores/settingsStore";
import { tactileStatusLabel, useTactileStore } from "@/stores/tactileStore";
import { authService } from "@/services/authService";
import { announce, hapticSelect } from "@/a11y";
import { controlHeight, radius, useFontScale, useTheme } from "@/theme";

/** V27 설정 */
export default function Settings() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const s = useSettingsStore();
  const tactile = useTactileStore();

  useEffect(() => {
    announce("설정이에요.");
  }, []);

  /** 스위치만 바꾼다. 실제 시작·중지는 루트 레이아웃이 이 값을 보고 한다. */
  const toggleItda = () => {
    const next = !s.itdaEnabled;
    s.setItdaEnabled(next);
    announce(next ? '잇다를 켰어요. "하이 잇다"라고 불러 보세요.' : "잇다를 껐어요.");
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
        <ListRow
          label="잇다 음성 비서"
          description={s.itdaEnabled ? '"하이 잇다"라고 부르면 대답해요' : "켜면 호출어를 듣기 시작해요"}
          value={s.itdaEnabled ? "켜짐" : "꺼짐"}
          emphasizeValue={s.itdaEnabled}
          accessibilityHint="누르면 잇다를 켜고 꺼요"
          onPress={toggleItda}
        />
        <ListRow
          label="내 목소리로 답하기"
          description="녹음한 내 목소리로 잇다가 말해요"
          value={s.clonedVoice ? "설정됨" : "없음"}
          chevron
          accessibilityHint="목소리를 녹음하고 복제하는 화면으로 가요"
          onPress={() => router.push("/voice-clone")}
        />
        <ListRow
          label="점자 장치"
          value={tactileStatusLabel(tactile.connected, tactile.name)}
          emphasizeValue={tactile.connected}
          chevron
          accessibilityHint="장치 연결 상태를 확인해요"
          onPress={() => router.push("/device-error")}
        />
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
          onPress={() => {
            void authService.signOut().then(() => {
              announce("로그아웃했어요.");
              router.replace("/auth/sign-in");
            });
          }}
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
