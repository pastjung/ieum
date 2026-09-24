import { QueryClient } from "@tanstack/react-query";
import { learningSessionQueryKey } from "../src/services/learningService";

/**
 * 리뷰 Important [1] 재현·검증.
 *
 * intro.tsx의 쿼리는 `staleTime: Infinity` + `gcTime: Infinity`로 만들어진다.
 * "시작하기"를 누를 때 `queryClient.removeQueries`로 캐시를 지우지 않으면, 같은 주제로
 * 화면에 다시 들어왔을 때 이미 완료된 세션이 그대로 재사용된다 — 서버에 새 세션을
 * 요청하지 않는다. 이 세션을 다시 풀어 제출하면 백엔드가 완료된 배정을 거부한다
 * (LearningSessionCompletionService.validateAndIndexAttempts →
 * INVALID_COMPLETION_REQUEST).
 *
 * RN 컴포넌트를 렌더링하지 않고 intro.tsx와 정확히 같은 쿼리 키·옵션으로
 * `QueryClient`를 직접 움직여, "제거하지 않으면 재사용되고 / 제거하면 다시 요청한다"를
 * 확인한다.
 */
describe("intro 화면의 세션 쿼리 캐시", () => {
  const themeId = "consonant" as const;
  const queryOptions = {
    queryKey: learningSessionQueryKey(themeId),
    staleTime: Infinity,
    gcTime: Infinity,
  };

  it("캐시를 지우지 않으면 같은 주제로 다시 들어와도 서버를 다시 부르지 않는다 (버그 재현)", async () => {
    const queryClient = new QueryClient();
    const startSession = jest.fn().mockResolvedValue({ session: "server-session-1" });

    await queryClient.fetchQuery({ ...queryOptions, queryFn: startSession });
    // 화면을 나갔다가 같은 주제로 다시 들어온 상황을 흉내낸다 — QueryClient는 앱 수명
    // 동안 하나이므로 캐시가 그대로 남아 있다
    await queryClient.fetchQuery({ ...queryOptions, queryFn: startSession });

    expect(startSession).toHaveBeenCalledTimes(1); // 두 번째 진입에서도 서버 호출이 없다 — 버그
  });

  it("시작하기에서 removeQueries를 부르면 다음 진입에서 서버를 다시 부른다 (수정된 동작)", async () => {
    const queryClient = new QueryClient();
    const startSession = jest.fn().mockResolvedValue({ session: "server-session-1" });

    await queryClient.fetchQuery({ ...queryOptions, queryFn: startSession });

    // "시작하기" 버튼이 하는 것과 동일한 호출
    queryClient.removeQueries({ queryKey: learningSessionQueryKey(themeId) });

    await queryClient.fetchQuery({ ...queryOptions, queryFn: startSession });

    expect(startSession).toHaveBeenCalledTimes(2); // 캐시가 비워져 새 세션을 요청한다
  });
});
