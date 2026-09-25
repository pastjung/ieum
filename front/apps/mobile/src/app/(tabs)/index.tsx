import { useEffect, useRef } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { ListRow } from "@/components/ListRow";
import { AppButton } from "@/components/AppButton";
import { IeumFace } from "@/components/IeumFace";
import { authService } from "@/services/authService";
import { learningService } from "@/services/learningService";
import { LEVEL_LABEL, LEVEL_UNSET_LABEL } from "@/services/levelLabels";
import { LevelBadge } from "@/components/LevelBadge";
import { useSettingsStore } from "@/stores/settingsStore";
import { useTactileStore } from "@/stores/tactileStore";
import { announce, hapticError, hapticTap } from "@/a11y";
import { fontSize, radius, useFontScale, useTheme } from "@/theme";

/** level은 단계 번호가 아니라 학습 범주라 "3단계" 같은 문구를 만들 수 없다 */
const LOAD_FAILED_TEXT = "사용자 정보를 불러오지 못했어요.";

/**
 * 두 홈 변형이 공유하는 `GET /users/me` 조회.
 *
 * 실패를 화면 문구로만 남기면 음성으로만 앱을 쓰는 사용자에게는 "홈에 도착했는데 아무 말도 없음"이 되고,
 * 그건 앱이 죽은 것과 구분되지 않는다. 그래서 실패도 반드시 낭독·진동으로 알리고 재시도 수단을 함께 준다.
 * (401은 클라이언트가 자동 갱신 → 만료 화면으로 처리하므로 여기 오지 않는다)
 */
function useMeQuery() {
  const { data, isError, refetch } = useQuery({ queryKey: ["me"], queryFn: authService.getMe });
  // 같은 실패를 두 번 낭독하지 않기 위해 이미 알렸는지 기억한다.
  // 성공으로 돌아오면 되돌려서, 다음 실패는 다시 들릴 수 있게 한다
  const notifiedRef = useRef(false);

  useEffect(() => {
    if (!isError) {
      notifiedRef.current = false;
      return;
    }
    if (notifiedRef.current) return;
    notifiedRef.current = true;
    void hapticError();
    // 이름·단계를 몰라도 "홈에 도착했다"는 사실은 들려야 한다
    announce(`홈이에요. ${LOAD_FAILED_TEXT} 다시 시도를 누르면 다시 불러와요.`);
  }, [isError]);

  const retry = () => {
    announce("다시 불러올게요.");
    // 재시도가 또 실패하면 isError가 true 그대로라 위 effect가 다시 돌지 않는다 —
    // 재시도의 결과는 여기서 직접 알려야 조용한 실패가 되지 않는다
    const announceRetryFailure = () => {
      notifiedRef.current = true;
      void hapticError();
      announce(`아직 ${LOAD_FAILED_TEXT} 잠시 후 다시 시도해 주세요.`);
    };
    void refetch().then((result) => {
      // 성공은 각 변형의 낭독이 맡는다
      if (result.isError) announceRetryFailure();
    }, announceRetryFailure);
  };

  return { data, isError, retry };
}

/** V10·V11 홈 (음성 중심) — 큰 버튼 중심 */
function VoiceHome() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  // inProgress는 학습 세션 API가 있어야 해서 목을 그대로 쓴다
  const { data: homeSummary } = useQuery({ queryKey: ["homeSummary"], queryFn: learningService.getHomeSummary });
  const { data, isError, retry } = useMeQuery();
  const userName = data?.username ?? "";
  const stepLabel = data?.level ? LEVEL_LABEL[data.level] : LEVEL_UNSET_LABEL;
  // 장치 연결은 서버 요약이 아니라 실제 블루투스 상태에서 읽는다.
  const deviceConnected = useTactileStore((s) => s.connected);

  useEffect(() => {
    // homeSummary(목)가 data(실제 사용자 정보)보다 먼저 도착하는 경우가 대부분이다.
    // 둘 다 준비되기 전에는 이름·단계가 들어간 문장을 만들지 않는다 — 빈 이름이나
    // "자가 진단 전"을 사실처럼 낭독하면 안 되고, 나중에 두 번째 낭독이 겹쳐도 안 된다
    if (!homeSummary || !data) return;
    announce(
      homeSummary.inProgress
        ? `홈이에요. ${userName}님, ${stepLabel}를 배우는 중이에요. 이어서 학습을 누르면 ${homeSummary.inProgress.title}을 계속해요.`
        : `홈이에요. 진행 중인 학습이 없어요. 복습을 시작하거나 새 단계를 배울 수 있어요.`,
    );
  }, [homeSummary, data, userName, stepLabel]);

  const goSession = () => {
    void hapticTap();
    router.push("/session/intro");
  };

  /** 복습은 단계를 고르지 않는다 — 서버가 내 기록에서 약한 요소를 골라 출제한다 */
  const goReview = () => {
    router.push({ pathname: "/session/intro", params: { mode: "review" } });
  };

  const bigButton = (label: string, sub: string | null, primary: boolean, onPress: () => void, flex = 1) => (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={sub ? `${label}, ${sub}` : label}
      onPress={onPress}
      style={({ pressed }) => [
        styles.big,
        { flex, borderRadius: radius.card },
        primary
          ? { backgroundColor: theme.color.primary }
          : { borderWidth: 2, borderColor: theme.color.border },
        theme.shadow != null && {
          boxShadow: `0 4px 0 ${primary ? theme.shadow.primary : theme.shadow.control}`,
        },
        pressed && { opacity: 0.8, boxShadow: "none" },
      ]}
    >
      <Text style={{ fontSize: 28 * fontScale, fontWeight: "900", color: primary ? theme.color.onPrimary : theme.color.text }}>
        {label}
      </Text>
      {sub != null && (
        <Text style={{ fontSize: 16 * fontScale, fontWeight: "600", color: primary ? theme.color.onPrimary : theme.color.textMuted }}>
          {sub}
        </Text>
      )}
    </Pressable>
  );

  return (
    <ScreenShell noBottomPadding>
      <View>
        <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12 }}>
          <Text accessibilityRole="header" style={{ fontSize: fontSize.title * fontScale, fontWeight: "900", color: theme.color.text }}>
            홈
          </Text>
          <LevelBadge level={data?.level} />
        </View>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, marginTop: 4 }}>
          {isError
            ? LOAD_FAILED_TEXT
            : homeSummary && data
              ? homeSummary.inProgress
                ? `${userName}님, ${stepLabel}를 배우는 중이에요.`
                : "진행 중인 학습이 없어요."
              : "불러오는 중이에요…"}
        </Text>
      </View>
      <View style={{ flex: 1, gap: 14, paddingBottom: 14 }}>
        {isError && (
          <AppButton
            label="다시 시도"
            variant="secondary"
            accessibilityHint="사용자 정보를 다시 불러와요"
            onPress={retry}
          />
        )}
        {homeSummary?.inProgress ? (
          <>
            {bigButton("이어서 학습", `${homeSummary.inProgress.title} · ${homeSummary.inProgress.progressLabel}`, true, goSession, 1.4)}
            {/*
              진행 중 세션이 있어도 복습은 닿을 수 있어야 한다. 예전에는 이 가지에 복습이
              없어서 — 그리고 목 데이터의 inProgress가 늘 값이 있어서 — 시각장애인용 홈에는
              복습 진입점이 아예 없었다. 화면 모드에 따라 쓸 수 있는 기능이 달라지면 안 된다
            */}
            {bigButton("복습 시작", null, false, goReview)}
            {bigButton("수업 참여", null, false, () => router.push("/(tabs)/class"))}
            <ListRow
              label="점자 장치"
              value={deviceConnected ? "연결됨" : "연결 안 됨"}
              accessibilityHint="장치 연결 상태를 확인해요"
              onPress={() => router.push("/device-error")}
            />
          </>
        ) : (
          <>
            {bigButton("복습 시작", null, true, goReview, 1.4)}
            {bigButton("새 단계 학습", null, false, () => router.push("/(tabs)/learn"))}
            {bigButton("수업 참여", null, false, () => router.push("/(tabs)/class"))}
          </>
        )}
      </View>
    </ScreenShell>
  );
}

/** L10 홈 (화면 중심) — 정보 밀도 높은 카드형 */
function ScreenHome() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  // inProgress는 학습 세션 API가 있어야 해서 목을 그대로 쓴다
  const { data: homeSummary } = useQuery({ queryKey: ["homeSummary"], queryFn: learningService.getHomeSummary });
  const { data, isError, retry } = useMeQuery();
  const userName = data?.username ?? "";
  const stepLabel = data?.level ? LEVEL_LABEL[data.level] : LEVEL_UNSET_LABEL;
  // 장치 연결은 서버 요약이 아니라 실제 블루투스 상태에서 읽는다.
  const deviceConnected = useTactileStore((s) => s.connected);

  return (
    <ScreenShell noBottomPadding>
      <ScrollView contentContainerStyle={{ gap: 14, paddingBottom: 14 }} showsVerticalScrollIndicator={false}>
        <View style={styles.greetRow}>
          <View style={{ flex: 1 }}>
            <Text accessibilityRole="header" style={{ fontSize: 24 * fontScale, fontWeight: "900", color: theme.color.text }}>
              안녕하세요, {userName || "학습자"}님
            </Text>
            <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted, marginTop: 4 }}>
              {isError ? LOAD_FAILED_TEXT : data ? `${stepLabel}를 배우는 중이에요` : "불러오는 중이에요…"}
            </Text>
          </View>
          <View style={{ alignItems: "flex-end", gap: 6 }}>
            <LevelBadge level={data?.level} />
            <IeumFace size={56} />
          </View>
        </View>

        {isError && (
          <AppButton
            label="다시 시도"
            variant="secondary"
            accessibilityHint="사용자 정보를 다시 불러와요"
            onPress={retry}
          />
        )}

        {homeSummary?.inProgress && (
          <View
            style={[
              styles.card,
              { backgroundColor: theme.color.primary, borderRadius: radius.card },
              theme.shadow != null && { boxShadow: `0 6px 0 ${theme.shadow.primary}` },
            ]}
            accessibilityLabel={`이어서 학습. ${homeSummary.inProgress.title}, ${homeSummary.inProgress.progressLabel}`}
          >
            <Text style={{ fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.onPrimary, opacity: 0.85 }}>이어서 학습</Text>
            <Text style={{ fontSize: 24 * fontScale, fontWeight: "900", color: theme.color.onPrimary }}>{homeSummary.inProgress.title}</Text>
            <Text style={{ fontSize: 15 * fontScale, fontWeight: "600", color: theme.color.onPrimary }}>{homeSummary.inProgress.progressLabel}</Text>
            <AppButton label="이어서 풀기" variant="compact" onPress={() => router.push("/session/intro")} />
          </View>
        )}

        <View
          style={[
            styles.card,
            { backgroundColor: theme.color.surface, borderRadius: radius.card },
            theme.shadow != null && { boxShadow: `0 4px 0 ${theme.shadow.control}` },
          ]}
        >
          <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
            <Text style={{ fontSize: 17 * fontScale, fontWeight: "800", color: theme.color.text }}>오늘의 복습</Text>
            <Text
              accessibilityRole="button"
              accessibilityLabel="복습 전체 보기"
              onPress={() => router.push("/tools/skills")}
              style={{ fontSize: 14 * fontScale, fontWeight: "700", color: theme.color.primary }}
            >
              전체 보기 ▸
            </Text>
          </View>
          {/*
            무엇을 복습할지 미리 보여주던 칩 세 개(받침 ㅆ·자음 ㅅ·모음 ㅕ)는 고정값이었다.
            내 약한 요소를 세션 밖에서 알려주는 API가 아직 없어(weakSkills는 완료 응답에만
            실린다) 지금은 지운다. 고정값을 내 실력처럼 보여주느니 말하지 않는 편이 낫다
          */}
          <Text style={{ fontSize: 15 * fontScale, color: theme.color.textMuted, lineHeight: 24 }}>
            약했던 것부터 다시 풀어요.
          </Text>
          <AppButton
            label="복습 시작"
            variant="compact"
            onPress={() => router.push({ pathname: "/session/intro", params: { mode: "review" } })}
          />
        </View>

        <ListRow
          label="수업 참여"
          description="참여 코드로 실시간 수업 입장"
          chevron
          onPress={() => router.push("/(tabs)/class")}
        />
        <ListRow
          label="점자 장치"
          value={deviceConnected ? "연결됨" : "연결 안 됨"}
          accessibilityHint="장치 연결 상태를 확인해요"
          onPress={() => router.push("/device-error")}
        />
      </ScrollView>
    </ScreenShell>
  );
}

export default function Home() {
  const mode = useSettingsStore((s) => s.mode);
  return mode === "screen" ? <ScreenHome /> : <VoiceHome />;
}

const styles = StyleSheet.create({
  big: { alignItems: "center", justifyContent: "center", gap: 8 },
  greetRow: { flexDirection: "row", alignItems: "center", gap: 12 },
  card: { padding: 18, gap: 10 },
});
