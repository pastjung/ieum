import { useSessionStore } from "@/stores/sessionStore";
import { completionRequestSchema, type AttemptAnalysisResponse } from "@ieum/contracts";
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

const analysis: AttemptAnalysisResponse = {
  problemSessionId: 101,
  problemId: 7,
  attemptId: 55,
  correct: false,
  attemptedAt: "2026-09-25T02:00:00Z",
  cells: [
    {
      cellIndex: 1,
      expectedPattern: 5,
      inputPattern: 1,
      diffMask: 4,
      missingMask: 4,
      extraMask: 0,
      missingDots: [3],
      extraDots: [],
    },
  ],
  feedback: { tts: "첫소리 이응은 점자로 적지 않아요.", detail: "제13항" },
};

describe("세션 스토어의 완료 페이로드", () => {
  beforeEach(() => useSessionStore.getState().reset());

  // 앱은 problemId → problemSessionId 대응을 알지 못한다(서버만 안다). 받은 순서를 그대로
  // 믿는 수밖에 없으므로, 순서가 어긋나면 답이 다른 문항에 기록된다
  it("배정 식별자를 문항 순서대로 붙인다", () => {
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().submit([1]);
    useSessionStore.getState().next(0);
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

// 예전에는 여기서 답안을 기대 칸 수에 맞춰 잘라내고 채웠다. 근거로 든 백엔드 함수
// `LearningSessionCompletionService.validateCellInputs`는 **존재하지 않는다** — dev가 길이
// 동등 검사를 걷어냈다. 그 사이 자르기·채우기는 해로웠다: `attempt-analysis`는 원본 cells를
// 보내 서버가 그대로 저장하는데 `/completion`만 손댄 값을 보냈고, 백엔드의
// `prepareAnalysisAttempts`가 그 손댄 값을 AI 세션 요약에 먹였다. 요약이 학습자가 실제로
// 내지 않은 답으로 계산됐다.
describe("칸 수가 기대와 다른 답안", () => {
  const multiCell = {
    id: "13",
    title: "단어",
    problems: [
      { id: "21", prompt: "'가방'을 점자로 입력하세요", target: "가방", expectedCells: [1, 32] },
    ],
  };
  const multiCellMeta = { serverSessionId: 13, problemSessionIds: [201] };

  beforeEach(() => useSessionStore.getState().reset());

  it("모자란 답을 빈 셀로 채우지 않고 그대로 보낸다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([1]); // 두 칸짜리 문항에 한 칸만 입력

    const attempts = useSessionStore.getState().buildAttempts();

    expect(attempts).toEqual([{ problemSessionId: 201, inputPatterns: [1] }]);
  });

  it("넘치는 답을 자르지 않고 그대로 보낸다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([1, 32, 5]);

    const attempts = useSessionStore.getState().buildAttempts();

    expect(attempts).toEqual([{ problemSessionId: 201, inputPatterns: [1, 32, 5] }]);
  });

  it("완료 제출이 문항 분석에 보낸 것과 같은 셀을 보낸다", () => {
    // 두 경로가 서로 다른 값을 보내면 서버가 저장한 답과 AI 요약이 계산한 답이 갈린다
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    const submitted = [1];
    useSessionStore.getState().submit(submitted);

    const [attempt] = useSessionStore.getState().buildAttempts();

    expect(attempt!.inputPatterns).toEqual(submitted);
  });

  it("보낸 답안의 채점 결과가 사용자가 들은 로컬 채점과 같다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    const local = useSessionStore.getState().submit([1]);

    const [attempt] = useSessionStore.getState().buildAttempts();

    expect(gradeAnswer(multiCell.problems[0]!.expectedCells, attempt!.inputPatterns)).toEqual(local);
    expect(local.correct).toBe(false);
  });

  it("페이로드가 백엔드 검증을 통과하는 모양이다", () => {
    useSessionStore.getState().start(multiCell, "free", multiCellMeta);
    useSessionStore.getState().submit([1]);

    expect(() =>
      completionRequestSchema.parse({ attempts: useSessionStore.getState().buildAttempts() }),
    ).not.toThrow();
  });
});

describe("문항별 분석 결과 보관", () => {
  beforeEach(() => useSessionStore.getState().reset());

  it("문항 분석 결과를 보관하고 다음 문항에서 비운다", () => {
    // 이전 문항의 피드백이 남아 있으면 다음 문항에서 엉뚱한 설명을 읽는다
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().setLastAnalysis(analysis);
    expect(useSessionStore.getState().lastAnalysis).toEqual(analysis);

    useSessionStore.getState().next(0);
    expect(useSessionStore.getState().lastAnalysis).toBeNull();
  });

  it("reset이 분석 결과도 지운다", () => {
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().setLastAnalysis(analysis);

    useSessionStore.getState().reset();

    expect(useSessionStore.getState().lastAnalysis).toBeNull();
  });

  it("reset 없이 다시 start해도 이전 분석 결과를 비운다", () => {
    // beforeEach의 reset()이 아니라 start() 자신이 비워야 한다 — 세션을 이어서 다시
    // 시작하는 경로(reset을 거치지 않는 경로)에서 이전 문항의 분석이 새어 나가면 안 된다
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().setLastAnalysis(analysis);
    expect(useSessionStore.getState().lastAnalysis).toEqual(analysis);

    useSessionStore.getState().start(session, "free", meta);

    expect(useSessionStore.getState().lastAnalysis).toBeNull();
  });

  it("새로 제출하면 이전 분석 결과를 비운다", () => {
    // 재제출 사이(서버 응답을 기다리는 동안)에 이전 문항 또는 이전 시도의 분석이 남아
    // 있으면, 그 사이 화면이 열려 방금 낸 답과 무관한 설명을 읽게 된다
    const store = useSessionStore.getState();
    store.start(session, "free", meta);
    useSessionStore.getState().setLastAnalysis(analysis);
    expect(useSessionStore.getState().lastAnalysis).toEqual(analysis);

    useSessionStore.getState().submit([1]);

    expect(useSessionStore.getState().lastAnalysis).toBeNull();
  });
});

// LEARNING_PROBLEM_ALREADY_ANALYZED 는 오류가 아니라 응답 유실·앱 재시작으로 서버가 이미
// 끝낸 문항이다. 그 문항의 서버 설명은 만들 수 없는데, play.tsx 에서 화면에 띄워 봐야
// 바로 다음 줄의 router.replace 가 치우고 낭독도 grade.tsx 의 announce 가 끊는다 —
// 그래서 안내를 스토어에 실어 화면 간에 나른다.
describe("서버 설명이 없는 문항의 안내", () => {
  beforeEach(() => useSessionStore.getState().reset());

  it("안내를 보관해 다음 화면이 읽을 수 있게 한다", () => {
    useSessionStore.getState().start(session, "free", meta);
    useSessionStore.getState().setLastAttemptNotice("이미 확인한 문제라 이번 설명은 없어요.");

    expect(useSessionStore.getState().lastAttemptNotice).toBe(
      "이미 확인한 문제라 이번 설명은 없어요.",
    );
  });

  it("다음 문항으로 넘어가면 안내를 비운다", () => {
    // 이전 문항의 안내가 남아 있으면 다음 문항의 채점 화면이 엉뚱한 말을 한다
    useSessionStore.getState().start(session, "free", meta);
    useSessionStore.getState().setLastAttemptNotice("이미 확인한 문제예요.");

    useSessionStore.getState().next(0);

    expect(useSessionStore.getState().lastAttemptNotice).toBeNull();
  });

  it("같은 문항에서 두 번 넘겨도 한 문항만 넘어간다", () => {
    // QA: 채점 화면의 다음 버튼을 연타하면 문항이 1~2개씩 건너뛰어졌다
    const three = {
      ...session,
      problems: [...session.problems, { id: "9", prompt: "'다'를 점자로 입력하세요", target: "다", expectedCells: [2] }],
    };
    useSessionStore.getState().start(three, "free", { serverSessionId: 12, problemSessionIds: [101, 102, 103] });

    expect(useSessionStore.getState().next(0)).toBe(true);
    expect(useSessionStore.getState().next(0)).toBe(false);

    expect(useSessionStore.getState().index).toBe(1);
  });

  it("마지막 문항에서는 넘어가지 않는다", () => {
    useSessionStore.getState().start(session, "free", meta);
    useSessionStore.getState().next(0);

    expect(useSessionStore.getState().next(1)).toBe(false);
    expect(useSessionStore.getState().index).toBe(1);
  });

  it("새로 제출하면 안내를 비운다", () => {
    useSessionStore.getState().start(session, "free", meta);
    useSessionStore.getState().setLastAttemptNotice("이미 확인한 문제예요.");

    useSessionStore.getState().submit([1]);

    expect(useSessionStore.getState().lastAttemptNotice).toBeNull();
  });

  it("reset과 start가 안내를 비운다", () => {
    useSessionStore.getState().start(session, "free", meta);
    useSessionStore.getState().setLastAttemptNotice("이미 확인한 문제예요.");
    useSessionStore.getState().reset();
    expect(useSessionStore.getState().lastAttemptNotice).toBeNull();

    useSessionStore.getState().start(session, "free", meta);
    useSessionStore.getState().setLastAttemptNotice("이미 확인한 문제예요.");
    useSessionStore.getState().start(session, "free", meta);

    expect(useSessionStore.getState().lastAttemptNotice).toBeNull();
  });
});
