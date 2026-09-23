import { useEffect, useState } from "react";
import { Stack, useRouter } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setSessionExpiredHandler } from "@/services/apiClient";
import { useTheme } from "@/theme";

export default function RootLayout() {
  const [queryClient] = useState(() => new QueryClient());
  const theme = useTheme();
  const router = useRouter();

  // 만료 처리는 라우터를 아는 이곳에서만 할 수 있다. 클라이언트는 콜백만 부른다
  useEffect(() => {
    setSessionExpiredHandler(() => router.replace("/auth/expired"));
  }, [router]);

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
