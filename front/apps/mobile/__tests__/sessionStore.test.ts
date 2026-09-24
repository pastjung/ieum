import { useSessionStore } from "@/stores/sessionStore";
import { completionRequestSchema } from "@ieum/contracts";
import { gradeAnswer } from "@ieum/domain";

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

  it("같은 문항을 다시 풀면 마지막 답만 보낸다", () => {
    // 백엔드는 중복 problemSessionId가 하나라도 있으면 세션 전체를 거부한다
    // (LearningSessionCompletionService.validateAndIndexAttempts)
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().submit([1]);
    useSessionStore.getState().submit([3]); // 같은 문항 재제출

    const attempts = useSessionStore.getState().buildAttempts();

    expect(attempts).toEqual([{ problemSessionId: 101, inputPatterns: [3] }]);
  });
});

// 백엔드는 문항 하나라도 칸 수가 어긋나면 세션 전체를 롤백한다
// (LearningSessionCompletionService.validateCellInputs + @Transactional). 그런데 칸 수가
// 모자란 답은 초보 학습자의 가장 흔한 오답이고 앱은 그것을 정상적인 오답으로 채점한다 —
// 정규화가 없으면 오답 하나가 세션 전체를 영구 실패시킨다.
describe("칸 수가 다른 답안의 정규화", () => {
  const multiCell = {
    id: "13",
    title: "단어",
    problems: [
      { id: "21", prompt: "'가방'을 점자로 입력하세요", target: "가방", expectedCells: [1, 32] },
    ],
  };
  const multiCellMeta = { serverSessionId: 13, problemSessionIds: [201] };

  beforeEach(() => useSessionStore.getState().reset());

  it("모자란 칸은 빈 셀로 채워 기대 칸 수를 맞춘다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([1]); // 두 칸짜리 문항에 한 칸만 입력

    const attempts = useSessionStore.getState().buildAttempts();

    expect(attempts).toEqual([{ problemSessionId: 201, inputPatterns: [1, 0] }]);
    expect(attempts[0]!.inputPatterns).toHaveLength(2);
  });

  it("넘치는 칸은 기대 칸 수까지 자른다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([1, 32, 5]);

    const attempts = useSessionStore.getState().buildAttempts();

    expect(attempts).toEqual([{ problemSessionId: 201, inputPatterns: [1, 32] }]);
  });

  it("정규화한 답안의 채점 결과가 사용자가 들은 로컬 채점과 같다", () => {
    // gradeAnswer가 모자란 칸을 EMPTY_CELL로 읽는 것과 같은 규칙으로 채우기 때문이다.
    // 규칙이 다르면 화면에서 들은 결과와 서버가 저장하는 결과가 갈라진다.
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    const local = useSessionStore.getState().submit([1]);

    const [attempt] = useSessionStore.getState().buildAttempts();

    expect(gradeAnswer(multiCell.problems[0]!.expectedCells, attempt!.inputPatterns)).toEqual(local);
    expect(local.correct).toBe(false);
  });

  it("정규화한 페이로드가 백엔드 검증을 통과하는 모양이다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([1]);

    expect(() =>
      completionRequestSchema.parse({ attempts: useSessionStore.getState().buildAttempts() }),
    ).not.toThrow();
  });
});
