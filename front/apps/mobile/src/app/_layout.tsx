import { useEffect, useState } from "react";
import { AppState } from "react-native";
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { startItda, stopItda } from "@/services/itda";
import { useSettingsStore } from "@/stores/settingsStore";
import { useTheme } from "@/theme";

export default function RootLayout() {
  const [queryClient] = useState(() => new QueryClient());
  const theme = useTheme();
  useItdaLifecycle();
  return (
    <QueryClientProvider client={queryClient}>
      <StatusBar style={theme.name === "dark" ? "light" : "dark"} />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: theme.color.bg },
        }}
      />
    </QueryClientProvider>
  );
}

/**
 * 잇다를 켜고 끄는 **유일한 자리**.
 *
 * 설정 화면은 스위치만 바꾸고, 실제 시작·중지는 여기서 한다. 저장된 설정이 늦게 올라와도
 * (AsyncStorage 복원) 값이 바뀌는 순간 반응하고, 앱이 뒤로 가면 마이크를 놓는다.
 * 화면이 꺼진 상태의 상시 대기는 지원하지 않는다.
 */
function useItdaLifecycle(): void {
  const enabled = useSettingsStore((s) => s.itdaEnabled);

  useEffect(() => {
    if (!enabled) {
      stopItda();
      return;
    }

    void startItda();

    // 실제로 앞뒤가 바뀔 때만 움직인다. 시스템 대화상자가 잠깐 덮는 것까지 "나갔다 왔다"로
    // 읽으면, 되돌아올 때마다 다시 시작을 걸어 끝없이 돌게 된다.
    let wasActive = AppState.currentState === "active";
    const subscription = AppState.addEventListener("change", (state) => {
      const isActive = state === "active";
      if (isActive === wasActive) return;
      wasActive = isActive;

      if (isActive) void startItda();
      else stopItda();
    });
    return () => {
      subscription.remove();
      stopItda();
    };
  }, [enabled]);
}
