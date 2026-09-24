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

// 칸 수가 기대와 달라도 서버는 요청을 거부하지 않는다 — 모자란 답도 넘친 답도 정상적인
// 오답으로 채점한다(LearningSessionCompletionService.isCorrect). 그래서 앱은 입력한 칸을
// 그대로 보낸다. 길이를 맞춰 채우거나 자르면 사용자가 입력하지 않은 답을 보내는 셈이 되고,
// 화면에서 들은 판정과 서버가 저장하는 판정이 갈라진다.
describe("칸 수가 기대와 다른 답안", () => {
  const multiCell = {
    id: "13",
    title: "단어",
    problems: [
      { id: "21", prompt: "'가방'을 점자로 입력하세요", target: "가방", expectedCells: [11, 1] },
    ],
  };
  const multiCellMeta = { serverSessionId: 13, problemSessionIds: [201] };

  beforeEach(() => useSessionStore.getState().reset());

  it("모자란 답을 빈 칸으로 채우지 않고 입력한 그대로 보낸다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([11]); // 두 칸짜리 문항에 한 칸만 입력

    expect(useSessionStore.getState().buildAttempts()).toEqual([
      { problemSessionId: 201, inputPatterns: [11] },
    ]);
  });

  it("넘치는 답을 자르지 않고 입력한 그대로 보낸다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([11, 1, 4]); // 두 칸짜리 문항에 세 칸 입력

    expect(useSessionStore.getState().buildAttempts()).toEqual([
      { problemSessionId: 201, inputPatterns: [11, 1, 4] },
    ]);
  });

  it("칸 수가 다른 답안의 페이로드도 백엔드 검증을 통과하는 모양이다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([11, 1, 4]);

    expect(() =>
      completionRequestSchema.parse({ attempts: useSessionStore.getState().buildAttempts() }),
    ).not.toThrow();
  });

  // 서버 채점(LearningSessionCompletionService.isCorrect)과 짝을 이루는 테스트다. 같은 문항
  // (기대 2칸 [11, 1])·같은 입력 세 가지에 대해 백엔드
  // `LearningSessionCompletionServiceTest.칸_수가_부족_정확_초과인_세_경우의_판정이_로컬_채점과_같다`
  // 가 같은 판정을 단언한다. 두 채점이 갈라지면 사용자는 문항마다 들은 결과와 결과 화면의
  // 점수가 다른 것을 보게 된다.
  it.each([
    ["칸이 모자라면 오답", [11], false],
    ["칸 수와 값이 모두 맞으면 정답", [11, 1], true],
    ["칸이 넘치면 오답", [11, 1, 4], false],
  ] as const)("로컬 채점: %s", (_label, input, expected) => {
    useSessionStore.getState().reset();
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);

    const local = useSessionStore.getState().submit([...input]);

    expect(local.correct).toBe(expected);
    // 서버는 이 배열을 그대로 받아 같은 규칙으로 채점한다
    expect(useSessionStore.getState().buildAttempts()).toEqual([
      { problemSessionId: 201, inputPatterns: [...input] },
    ]);
  });
});
