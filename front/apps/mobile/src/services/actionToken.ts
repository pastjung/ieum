/**
 * 메일 링크가 실어 보낸 action token을 딥링크 URL에서 꺼낸다.
 *
 * 백엔드는 토큰을 **fragment**로만 붙인다 (`<base>#token=<urlencoded>`). fragment는 서버로
 * 전송되지 않아 접근 로그에 토큰이 남지 않기 때문이고, 중계 페이지도 그 성질을 지켜
 * `ieum://auth/...#token=...` 형태로 넘긴다.
 *
 * expo-router는 fragment를 라우트 파라미터로 주지 않는다. `useLocalSearchParams()`로는
 * 잡히지 않으므로 `Linking.useURL()`이 준 원문에서 직접 읽는다.
 *
 * `URLSearchParams`를 쓰지 않는다. React Native의 폴리필은 버전에 따라 동작이 달라
 * 여기서만 조용히 빗나갈 수 있다 — 형식이 `token=<값>` 하나로 고정돼 있으니 직접 자른다.
 *
 * @param url `Linking.useURL()`이 돌려준 값. 앱을 딥링크로 열지 않았으면 `null`이다
 * @returns 토큰 원문. 없거나 비어 있으면 `null`
 */
export function extractActionToken(url: string | null | undefined): string | null {
  if (!url) return null;

  const hashIndex = url.indexOf("#");
  if (hashIndex < 0) return null;

  const fragment = url.slice(hashIndex + 1);
  for (const part of fragment.split("&")) {
    if (!part.startsWith("token=")) continue;
    const raw = part.slice("token=".length);
    if (raw === "") return null;
    try {
      const decoded = decodeURIComponent(raw);
      return decoded === "" ? null : decoded;
    } catch {
      // 퍼센트 인코딩이 깨진 링크다. 원문을 그대로 서버에 보내면 400이 날 뿐이므로
      // 토큰이 없는 것으로 다룬다 — 화면이 "링크를 다시 요청해 주세요"로 안내한다
      return null;
    }
  }
  return null;
}
