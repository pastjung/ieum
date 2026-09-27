import { useEffect } from "react";
import { Tabs } from "expo-router";
import { checkForAppUpdateOnce } from "@/services/appUpdateCheck";
import { useTheme } from "@/theme";

const tabs = [
  { name: "index", title: "홈" },
  { name: "learn", title: "학습" },
  { name: "class", title: "수업" },
  { name: "settings", title: "설정" },
] as const;

export default function TabsLayout() {
  const theme = useTheme();

  // 로그인·온보딩을 마친 첫 안정 화면에서 새 버전을 확인한다(실행당 한 번, 실패는 조용히)
  useEffect(() => {
    void checkForAppUpdateOnce();
  }, []);

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: theme.color.primary,
        tabBarInactiveTintColor: theme.color.textMuted,
        tabBarStyle: {
          backgroundColor: theme.color.surface,
          borderTopColor: theme.color.border,
          borderTopWidth: 2,
          height: 76,
        },
        tabBarLabelStyle: { fontSize: 15, fontWeight: "800" },
        tabBarIconStyle: { display: "none" },
        tabBarLabelPosition: "beside-icon",
      }}
    >
      {tabs.map((t) => (
        <Tabs.Screen
          key={t.name}
          name={t.name}
          options={{ title: t.title, tabBarAccessibilityLabel: `${t.title} 탭` }}
        />
      ))}
    </Tabs>
  );
}
