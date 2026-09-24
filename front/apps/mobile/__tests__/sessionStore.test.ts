import { useSessionStore } from "@/stores/sessionStore";
import { completionRequestSchema } from "@ieum/contracts";

const session = {
  id: "12",
  title: "자음",
  problems: [
    { id: "7", prompt: "'가'를 점자로 입력하세요", target: "가", expectedCells: [1] },
    { id: "8", prompt: "'방'을 점자로 입력하세요", target: "방", expectedCells: [3] },
  ],
};

const meta = { serverSessionId: 12, problemSessionIds: [101, 102] };

describe("세션 스토어의 완료 페이로드", () => {
  beforeEach(() => useSessionStore.getState().reset());

  // 앱은 problemId → problemSessionId 대응을 알지 못한다(서버만 안다). 받은 순서를 그대로
  // 믿는 수밖에 없으므로, 순서가 어긋나면 답이 다른 문항에 기록된다
  it("배정 식별자를 문항 순서대로 붙인다", () => {
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().submit([1]);
    useSessionStore.getState().next();
    useSessionStore.getState().submit([3]);

    const attempts = useSessionStore.getState().buildAttempts();

    expect(attempts).toEqual([
      { problemSessionId: 101, inputPatterns: [1] },
      { problemSessionId: 102, inputPatterns: [3] },
    ]);
  });

  it("만든 페이로드가 백엔드 검증을 통과하는 모양이다", () => {
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().submit([1]);

    expect(() =>
      completionRequestSchema.parse({ attempts: useSessionStore.getState().buildAttempts() }),
    ).not.toThrow();
  });

  it("목 세션은 배정 식별자가 없어 제출 페이로드를 만들지 않는다", () => {
    // 목으로 시작한 세션을 실수로 제출하면 서버가 422를 준다. 앞에서 막는다
    const store = useSessionStore.getState();
    store.start(session);
    useSessionStore.getState().submit([1]);

    expect(useSessionStore.getState().buildAttempts()).toEqual([]);
    expect(useSessionStore.getState().serverSessionId).toBeNull();
  });
});
