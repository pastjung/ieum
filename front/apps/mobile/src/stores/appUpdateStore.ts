import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import AsyncStorage from "@react-native-async-storage/async-storage";
import type { UpdateNotice } from "@/services/appRelease";

/**
 * 새 버전 알림 기록. "같은 버전은 하루에 한 번"을 앱을 껐다 켜도 지키려고 기기에 남긴다.
 * 사용자 설정(settingsStore)과 수명이 달라 따로 둔다.
 */
interface AppUpdateState {
  lastNotice: UpdateNotice | null;
  setLastNotice: (notice: UpdateNotice) => void;
}

export const useAppUpdateStore = create<AppUpdateState>()(
  persist(
    (set) => ({
      lastNotice: null,
      setLastNotice: (lastNotice) => set({ lastNotice }),
    }),
    {
      name: "ieum-app-update",
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({ lastNotice: state.lastNotice }),
    },
  ),
);

/** AsyncStorage 복원은 비동기다. 복원 전에 읽으면 어제 알린 기록을 못 보고 또 알린다 */
export function whenAppUpdateStoreHydrated(): Promise<void> {
  if (useAppUpdateStore.persist.hasHydrated()) return Promise.resolve();
  return new Promise((resolve) => {
    const unsubscribe = useAppUpdateStore.persist.onFinishHydration(() => {
      unsubscribe();
      resolve();
    });
  });
}
