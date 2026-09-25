import { QueryClient } from "@tanstack/react-query";
import type { LearningSession } from "@ieum/contracts";
import type { ServerSessionMeta } from "../src/stores/sessionStore";
import {
  beginLearningSession,
  learningSessionQueryKey,
  type SessionRequest,
} from "../src/services/learningSessionCache";

/**
 * 세션 소유권이 조회 캐시에서 스토어로 넘어가는 지점을 지킨다.
 *
 * 이 테스트는 `intro.tsx`가 실제로 부르는 함수(`beginLearningSession`)를 직접 호출한다 —
 * 화면을 띄우지 않고도 캐시 정리가 실제로 일어나는지 볼 수 있기 때문이다.
 * `beginLearningSession`을 호출하면 (1) `start`가 세션·메타로 불리고, (2) 그 요청의
 * 조회 캐시가 비워져야 한다.
 */
describe("beginLearningSession", () => {
  const session: LearningSession = {
    id: "1",
    title: "자음",
    problems: [{ id: "p1", prompt: "테스트", target: "테스트", expectedCells: [1] }],
  };
  const meta: ServerSessionMeta = { serverSessionId: 1, problemSessionIds: [10] };

  function runWith(request: SessionRequest) {
    const queryClient = new QueryClient();
    const key = learningSessionQueryKey(request);
    // intro.tsx의 useQuery가 이미 이 키로 세션을 캐시해 둔 상황을 흉내낸다
    queryClient.setQueryData(key, { session, meta });

    const start = jest.fn();
    beginLearningSession(queryClient, request, { session, meta }, start);
    return { queryClient, key, start };
  }

  it("세션 소유권을 스토어로 넘기고, 그 주제의 조회 캐시를 비운다", () => {
    const { queryClient, key, start } = runWith({ kind: "theme", themeId: "consonant" });

    expect(start).toHaveBeenCalledWith(session, "free", meta);
    // 캐시가 비어 있어야, 같은 주제로 다시 들어왔을 때 서버에 새 세션을 요청한다
    expect(queryClient.getQueryData(key)).toBeUndefined();
  });

  it("복습 세션도 같은 방식으로 캐시를 비운다", () => {
    // 복습이 캐시에 남으면 "복습 시작"을 다시 눌러도 이미 끝낸 세션이 되살아나고,
    // 재제출을 백엔드가 거부한다
    const { queryClient, key, start } = runWith({ kind: "review" });

    expect(start).toHaveBeenCalledWith(session, "free", meta);
    expect(queryClient.getQueryData(key)).toBeUndefined();
  });
});

describe("learningSessionQueryKey", () => {
  it("복습과 자유 학습이 서로 다른 캐시를 쓴다", () => {
    // 같은 키를 쓰면 자음 세션을 만든 뒤 복습을 눌렀을 때 자음 세션이 그대로 나온다
    expect(learningSessionQueryKey({ kind: "review" })).not.toEqual(
      learningSessionQueryKey({ kind: "theme", themeId: "consonant" }),
    );
  });

  it("주제가 다르면 캐시도 다르다", () => {
    expect(learningSessionQueryKey({ kind: "theme", themeId: "consonant" })).not.toEqual(
      learningSessionQueryKey({ kind: "theme", themeId: "vowel" }),
    );
  });

  it("주제가 없는 자유 학습은 하나의 캐시를 쓴다", () => {
    expect(learningSessionQueryKey({ kind: "theme", themeId: undefined })).toEqual(
      learningSessionQueryKey({ kind: "theme", themeId: undefined }),
    );
  });
});
