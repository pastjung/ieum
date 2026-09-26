import * as Haptics from "expo-haptics";
import { useSettingsStore } from "@/stores/settingsStore";

function enabled(): boolean {
  return useSettingsStore.getState().hapticsEnabled;
}

/** 정답·완료 등 성공 피드백 */
export async function hapticSuccess(): Promise<void> {
  if (!enabled()) return;
  await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
}

/** 오답·오류 피드백 */
export async function hapticError(): Promise<void> {
  if (!enabled()) return;
  await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
}

/** 점 버튼 눌림 등 가벼운 터치 피드백 */
export async function hapticTap(): Promise<void> {
  if (!enabled()) return;
  await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
}

/** 선택 변경 피드백 */
export async function hapticSelect(): Promise<void> {
  if (!enabled()) return;
  await Haptics.selectionAsync();
}
