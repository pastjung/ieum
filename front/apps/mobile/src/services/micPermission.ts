import { AudioModule } from "expo-audio";

/**
 * 수업 마이크를 켜기 전에 안드로이드 마이크 권한(RECORD_AUDIO)을 확인한다.
 * 이미 허용됐으면 대화상자 없이 바로 true다. 목소리 녹음(voiceSample)과 같은 요청을 쓴다.
 */
export async function ensureMicPermission(): Promise<boolean> {
  try {
    const permission = await AudioModule.requestRecordingPermissionsAsync();
    return permission.granted;
  } catch {
    return false;
  }
}
