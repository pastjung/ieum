import {
  CapsuleSession,
  ConceptExtractor,
  conceptValue,
  detectWakeWordIn,
  extractFeatures,
  ItdaCapsule,
  InMemoryTactileDevice,
  NlModel,
  NluPipeline,
  RuleNlu,
  StatisticalNlu,
  toJamo,
  normalize,
  toSerialLine,
  describeCommand,
  decodeDots,
  encodeDots,
  destinationById,
  type CapsuleResult,
  type NluResult,
} from "@ieum/itda";
import trainedModel from "../assets/nl/itda_nl_model.json";

/** 액션만 빠르게 꺼내 보는 헬퍼 */
const nlu = new RuleNlu();
const actionOf = (utterance: string) => nlu.parse(utterance).action;
const valueOf = (utterance: string, type: Parameters<typeof conceptValue>[1]) =>
  conceptValue(nlu.parse(utterance), type);

describe("한글 유틸", () => {
  it("공백과 문장부호를 지운다", () => {
    expect(normalize("복습, 열어 줘!")).toBe("복습열어줘");
  });

  it("자모로 풀어 쓴다", () => {
    expect(toJamo("잇다")).toBe("ㅇㅣㅅㄷㅏ");
  });

  it("피처는 문자 2·3-gram과 자모 3-gram이다", () => {
    const features = extractFeatures("복습");
    expect(features).toContain("c2:복습");
    expect(features.some((f) => f.startsWith("j3:"))).toBe(true);
  });
});

describe("호출어 판정", () => {
  it("그대로 말하면 잡는다", () => {
    expect(detectWakeWordIn("하이 잇다")).not.toBeNull();
  });

  it("받침이나 발음이 흔들려도 잡는다", () => {
    // 음성 인식기가 실제로 내놓는 표기들
    for (const variant of ["하이 있다", "하이잇다", "하이 이따", "하이 잇따", "헤이 잇다", "잇다야", "안녕 잇다"]) {
      expect(detectWakeWordIn(variant)).not.toBeNull();
    }
  });

  it("상관없는 말은 잡지 않는다", () => {
    for (const other of ["오늘 날씨 어때", "복습 열어줘", "여기 사람이 있다", "수업 시작할게요"]) {
      expect(detectWakeWordIn(other)).toBeNull();
    }
  });

  it("호출어 뒤에 붙은 명령을 잘라낸다", () => {
    expect(detectWakeWordIn("하이 잇다 복습 열어줘")?.command).toBe("복습열어줘");
  });

  it("호출어만 말하면 명령은 비어 있다", () => {
    expect(detectWakeWordIn("하이 잇다!")?.command).toBe("");
  });
});

describe("규칙 NLU — 행동 동사로 액션을 가른다", () => {
  it("같은 '복습'이라도 열어 달라는 건 이동, 시작해 달라는 건 학습", () => {
    expect(actionOf("복습 화면 열어줘")).toBe("OpenScreen");
    expect(actionOf("복습 시작해줘")).toBe("StartReview");
  });

  it("주요 액션을 구분한다", () => {
    expect(actionOf("오답 노트 보여줘")).toBe("ShowWrongNotes");
    expect(actionOf("수업 들어가줘")).toBe("JoinLesson");
    expect(actionOf("학습 기록 알려줘")).toBe("ShowProgress");
    expect(actionOf("촉각 기기 연결해줘")).toBe("ConnectDevice");
  });

  it("시스템 명령을 구분한다", () => {
    expect(actionOf("뒤로 가줘")).toBe("GoBack");
    expect(actionOf("뭐 할 수 있어")).toBe("ShowHelp");
    expect(actionOf("그만")).toBe("Cancel");
  });

  it("점 올리기·내리기는 화면 이동보다 먼저 본다", () => {
    expect(actionOf("3번 점 올려줘")).toBe("RaiseDots");
    expect(actionOf("점 전부 내려줘")).toBe("LowerDots");
    expect(actionOf("1 3 5번 켜줘")).toBe("RaiseDots");
  });

  it("조금 잘못 들려도 매칭된다", () => {
    expect(actionOf("복십 시작해줘")).toBe("StartReview");
  });

  it("모르는 말은 액션이 비어 있다", () => {
    expect(actionOf("내일 서울 날씨 알려줘")).toBeNull();
  });

  it("액션을 못 정해도 concept은 살아 있다", () => {
    // 되묻는 중에 값만 답하는 경우
    const result = nlu.parse("이번 주");
    expect(result.action).toBeNull();
    expect(conceptValue(result, "Period")).toBe("week");
  });
});

describe("규칙 NLU — concept 추출", () => {
  it("이 앱의 화면 이름을 뽑는다", () => {
    expect(valueOf("설정 열어줘", "ScreenName")).toBe("settings");
    expect(valueOf("점자 사전 열어줘", "ScreenName")).toBe("dictionary");
    expect(valueOf("내 실력 보여줘", "ScreenName")).toBe("record");
    expect(valueOf("내 목소리 설정 열어줘", "ScreenName")).toBe("voice");
  });

  it("문항 수와 난이도를 뽑는다", () => {
    expect(valueOf("복습 열 문제 시작해줘", "QuestionCount")).toBe("10");
    expect(valueOf("5문제만 풀래", "QuestionCount")).toBe("5");
    expect(valueOf("약자 복습 시작해줘", "BrailleLevel")).toBe("L3");
  });

  it("기간을 뽑는다", () => {
    expect(valueOf("이번 주 오답 보여줘", "Period")).toBe("week");
    expect(valueOf("지난주 오답 노트 보여줘", "Period")).toBe("last_week");
  });

  it("점 번호를 뽑는다 — 숫자·한글·전부", () => {
    expect(valueOf("3번 점 올려줘", "DotNumber")).toBe("3");
    expect(valueOf("1번하고 3번 점 켜줘", "DotNumber")).toBe("1,3");
    expect(valueOf("여섯 번째 점 올려", "DotNumber")).toBe("6");
    expect(valueOf("점 전부 내려줘", "DotNumber")).toBe("all");
  });

  it("점 번호와 헷갈릴 만한 말은 거른다", () => {
    // "이번 주"의 이번은 2번 점이 아니고, "5문제"는 문항 수다
    expect(valueOf("이번 주 오답 보여줘", "DotNumber")).toBeUndefined();
    expect(valueOf("5문제만 풀래", "DotNumber")).toBeUndefined();
  });
});

describe("촉각 명령", () => {
  it("아두이노가 받는 줄로 바꾼다", () => {
    expect(toSerialLine({ kind: "raise", dots: [1, 3, 5] })).toBe("ON 1 3 5");
    expect(toSerialLine({ kind: "lower", dots: [2] })).toBe("OFF 2");
    expect(toSerialLine({ kind: "raiseAll" })).toBe("ALLON");
    expect(toSerialLine({ kind: "lowerAll" })).toBe("ALLOFF");
  });

  it("음성 응답 문구를 만든다", () => {
    expect(describeCommand({ kind: "raise", dots: [1, 3] })).toBe("1번, 3번 점을 올렸어요.");
    expect(describeCommand({ kind: "lowerAll" })).toBe("모든 점을 내렸어요.");
  });

  it("점 번호 값을 주고받는다", () => {
    expect(encodeDots([5, 1, 3, 1])).toBe("1,3,5");
    expect(decodeDots("1,3,5")).toEqual([1, 3, 5]);
    expect(decodeDots("all")).toEqual([]);
    expect(decodeDots("0,7,3")).toEqual([3]); // 범위 밖은 버린다
  });
});

describe("캡슐 실행", () => {
  const capsule = () => new ItdaCapsule();
  const parse = (utterance: string) => nlu.parse(utterance);

  it("화면 이동은 expo 라우트를 돌려준다", async () => {
    const result = await capsule().execute(parse("설정 열어줘"));
    expect(result.kind).toBe("done");
    if (result.kind !== "done") return;
    expect(result.navigation).toEqual({ kind: "to", route: "/(tabs)/settings", title: "설정" });
  });

  it("복습 시작은 복습 세션 안내로 데려간다", async () => {
    // 파라미터 없이 안내 화면으로 가면 단계 학습으로 읽혀 curriculumId 없이 세션을 만들려다 실패했다
    const result = (await capsule().execute(parse("복습 시작해줘"))) as Extract<CapsuleResult, { kind: "done" }>;
    expect(result.navigation).toEqual({
      kind: "to",
      route: "/session/intro?mode=review",
      title: "문제 풀기",
    });
  });

  it("점 올리기는 모듈에 ON 명령을 보낸다", async () => {
    const device = new InMemoryTactileDevice();
    const result = (await new ItdaCapsule({ tactile: device }).execute(
      parse("1 3 5번 점 올려줘"),
    )) as Extract<CapsuleResult, { kind: "done" }>;

    expect(device.sentLines).toEqual(["ON 1 3 5"]);
    expect(device.dots).toEqual([1, 3, 5]);
    expect(result.speech).toContain("1번, 3번, 5번 점을 올렸어요");
    expect(result.navigation).toEqual({ kind: "stay" });
  });

  it("번호 없이 내리면 전부 내린다", async () => {
    const device = new InMemoryTactileDevice();
    const capsuleWithDevice = new ItdaCapsule({ tactile: device });
    await capsuleWithDevice.execute(parse("2번 점 올려줘"));

    const result = (await capsuleWithDevice.execute(parse("점 내려줘"))) as Extract<
      CapsuleResult,
      { kind: "done" }
    >;

    expect(device.sentLines).toEqual(["ON 2", "ALLOFF"]);
    expect(device.dots).toEqual([]);
    expect(result.speech).toContain("모든 점을 내렸어요");
  });

  it("점 번호를 안 말하면 되묻는다", async () => {
    const result = await capsule().execute(parse("점 올려줘"));
    expect(result.kind).toBe("needsConcept");
    if (result.kind !== "needsConcept") return;
    expect(result.missing).toBe("DotNumber");
  });

  it("모듈을 못 찾으면 이유를 말해 준다", async () => {
    const offline = new ItdaCapsule({ tactile: new InMemoryTactileDevice(false) });
    const result = (await offline.execute(parse("3번 점 올려줘"))) as Extract<
      CapsuleResult,
      { kind: "done" }
    >;
    expect(result.speech).toContain("찾을 수 없어요");
  });

  it("액션을 못 찾으면 못 알아들었다고 한다", async () => {
    const result = await capsule().execute(parse("내일 서울 날씨 알려줘"));
    expect(result.kind).toBe("notUnderstood");
  });
});

describe("되묻기 세션", () => {
  it("번호만 답해도 원래 액션이 이어진다", async () => {
    const device = new InMemoryTactileDevice();
    const session = new CapsuleSession(() => nlu, new ItdaCapsule({ tactile: device }));

    const first = await session.handle("점 올려줘");
    expect(first.result.kind).toBe("needsConcept");
    expect(session.pendingQuestion).toContain("몇 번 점");

    const second = await session.handle("3번");
    expect(second.nlu.action).toBe("RaiseDots");
    expect(device.sentLines).toEqual(["ON 3"]);
    expect(session.pendingQuestion).toBeNull();
  });

  it("되묻는 중에 다른 요청을 하면 그쪽을 따른다", async () => {
    const session = new CapsuleSession(() => nlu, new ItdaCapsule());

    await session.handle("점 올려줘");
    const second = await session.handle("설정 열어줘");

    expect(second.nlu.action).toBe("OpenScreen");
    expect(second.result.kind).toBe("done");
  });
});

describe("학습 모델 해석", () => {
  // 손으로 쓴 작은 모델 — 실제 가중치는 apps/ai 파이프라인이 학습한다
  const modelJson = JSON.stringify({
    version: "test",
    trainedAt: "2026-09-21T00:00:00Z",
    labels: ["StartReview", "OpenScreen", "__ood__"],
    logPrior: { StartReview: -1.1, OpenScreen: -1.1, __ood__: -1.1 },
    logLikelihood: {
      StartReview: { "c2:복습": -1, "c2:시작": -1 },
      OpenScreen: { "c2:설정": -1, "c2:열어": -1 },
      __ood__: { "c2:날씨": -1 },
    },
    defaultLogLikelihood: { StartReview: -8, OpenScreen: -8, __ood__: -8 },
    minConfidence: 0.5,
    concepts: { ScreenName: { settings: ["설정"] } },
  });

  it("가중치가 높은 라벨을 고른다", () => {
    const model = NlModel.parse(modelJson);
    expect(model).not.toBeNull();
    expect(model!.classify("복습 시작해줘")?.label).toBe("StartReview");
    expect(model!.classify("설정 열어줘")?.label).toBe("OpenScreen");
  });

  it("확신도는 0과 1 사이다", () => {
    const prediction = NlModel.parse(modelJson)!.classify("복습 시작해줘");
    expect(prediction!.confidence).toBeGreaterThan(0.5);
    expect(prediction!.confidence).toBeLessThanOrEqual(1);
  });

  it("캡슐 밖 라벨(__ood__)은 액션으로 매핑되지 않는다", () => {
    const engine = new StatisticalNlu(NlModel.parse(modelJson)!, new ConceptExtractor());
    expect(engine.parse("날씨 알려줘").action).toBeNull();
  });

  it("형식이 어긋난 JSON은 null", () => {
    expect(NlModel.parse('{"labels": []}')).toBeNull();
    expect(NlModel.parse("이건 JSON이 아니다")).toBeNull();
  });

  it("모델이 없으면 규칙 NLU로 동작한다", () => {
    const pipeline = NluPipeline.create(null);
    expect(pipeline.hasModel).toBe(false);
    expect(pipeline.description).toContain("규칙 기반");
    expect(pipeline.parse("설정 열어줘").action).toBe("OpenScreen");
  });

  it("모델이 있으면 모델을 먼저 쓰고, 모르는 화면은 앱 사전이 받쳐 준다", () => {
    const pipeline = NluPipeline.create(modelJson);
    expect(pipeline.hasModel).toBe(true);

    const result: NluResult = pipeline.parse("복습 시작해줘");
    expect(result.action).toBe("StartReview");
    expect(result.source).toBe("model");

    // 모델 사전에 없는 '점자 사전'도 앱 기본 사전 덕분에 살아 있다
    expect(conceptValue(pipeline.parse("점자 사전 열어줘"), "ScreenName")).toBe("dictionary");
  });
});

describe("번들에 실린 학습 모델 (ai/scripts/train_nl.py 산출물)", () => {
  const pipeline = NluPipeline.create(trainedModel);

  it("모델이 실제로 실려 있다", () => {
    expect(pipeline.hasModel).toBe(true);
    expect(pipeline.description).toContain("학습 모델");
  });

  it.each([
    ["설정 화면 열어줘", "OpenScreen", "settings"],
    ["내 목소리 설정 열어줘", "OpenScreen", "voice"],
    ["점자 사전 열어줘", "OpenScreen", "dictionary"],
    ["수업 들어가줘", "JoinLesson", null],
    ["오늘의 복습 시작해줘", "StartReview", null],
    ["오답 노트 보여줘", "ShowWrongNotes", null],
    ["점자 기기 연결해줘", "ConnectDevice", null],
  ])("'%s' → %s", (utterance, action, screen) => {
    const result = pipeline.parse(utterance);
    expect(result.action).toBe(action);
    if (screen !== null) expect(conceptValue(result, "ScreenName")).toBe(screen);
  });

  it("점 명령은 번호까지 집어낸다", () => {
    const raise = pipeline.parse("3번 점 올려줘");
    expect(raise.action).toBe("RaiseDots");
    expect(conceptValue(raise, "DotNumber")).toBe("3");

    const lower = pipeline.parse("점 전부 내려줘");
    expect(lower.action).toBe("LowerDots");
    expect(conceptValue(lower, "DotNumber")).toBe("all");
  });

  it("모델이 아는 화면 id는 모두 실제 경로로 이어진다", () => {
    const screens = Object.keys(trainedModel.concepts.ScreenName);
    expect(screens.length).toBeGreaterThan(0);
    for (const id of screens) {
      expect(destinationById(id)).not.toBeNull();
    }
  });

  it("캡슐 밖 발화는 거절한다", () => {
    for (const utterance of ["내일 서울 날씨 알려줘", "엄마한테 전화해줘", "노래 틀어줘"]) {
      expect(pipeline.parse(utterance).action).toBeNull();
    }
  });
});
