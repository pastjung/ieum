import { QueryClient } from "@tanstack/react-query";
import type { LearningSession } from "@ieum/contracts";
import type { ServerSessionMeta } from "../src/stores/sessionStore";
import { beginLearningSession, learningSessionQueryKey } from "../src/services/learningSessionCache";

/**
 * 리뷰 Important [1] 재검증.
 *
 * 이 테스트는 `intro.tsx`가 실제로 부르는 함수(`beginLearningSession`)를 직접 호출한다 —
 * 쿼리 클라이언트를 손으로 흉내 내지 않는다. 이 테스트가 보장하는 것은 딱 하나다:
 * `beginLearningSession`을 호출하면 (1) `start`가 세션·메타로 불리고, (2) 그 주제의
 * 조회 캐시가 비워진다는 것. `intro.tsx`의 "시작하기" `onPress`가 이 함수 하나만
 * 부르므로, 이 함수의 동작이 화면의 동작이다 — 화면을 렌더링해서 확인한 것은 아니다.
 */
describe("beginLearningSession", () => {
  const themeId = "consonant" as const;
  const session: LearningSession = {
    id: "1",
    title: "자음",
    problems: [{ id: "p1", prompt: "테스트", target: "테스트", expectedCells: [1] }],
  };
  const meta: ServerSessionMeta = { serverSessionId: 1, problemSessionIds: [10] };

  it("세션 소유권을 스토어로 넘기고, 그 주제의 조회 캐시를 비운다", () => {
    const queryClient = new QueryClient();
    const key = learningSessionQueryKey(themeId);
    // intro.tsx의 useQuery가 이미 이 키로 세션을 캐시해 둔 상황을 흉내낸다
    queryClient.setQueryData(key, { session, meta });

    const start = jest.fn();
    beginLearningSession(queryClient, themeId, { session, meta }, start);

    expect(start).toHaveBeenCalledWith(session, "free", meta);
    // 캐시가 비어 있어야, 같은 주제로 다시 들어왔을 때 서버에 새 세션을 요청한다
    expect(queryClient.getQueryData(key)).toBeUndefined();
  });
});
