import type { Dot } from "@ieum/domain";
import { conceptValue, type NluResult } from "../nlu/types";
import {
  ACTION_DESTINATION,
  actionSpec,
  destinationById,
  type ActionId,
  type ConceptType,
} from "./model";
import {
  DOT_ALL,
  decodeDots,
  describeCommand,
  InMemoryTactileDevice,
  TactileUnavailableError,
  type TactileCommand,
  type TactileDevice,
} from "./tactile";

/** 결과가 화면을 어떻게 할지. 실제 이동은 앱 계층(expo-router)이 한다. */
export type Navigation =
  | { readonly kind: "to"; readonly route: string; readonly title: string }
  | { readonly kind: "back" }
  | { readonly kind: "stay" };

export type CapsuleResult =
  /** 처리 완료. `speech`를 읽어 주고 `navigation`대로 화면을 옮긴다. */
  | {
      readonly kind: "done";
      readonly speech: string;
      readonly navigation: Navigation;
      /** 모듈 응답처럼 화면에만 덧붙일 상세. */
      readonly detail?: string;
    }
  /** 필요한 값이 비어 되묻는 상태(빅스비의 prompt). 다음 발화에서 빠진 값만 채운다. */
  | {
      readonly kind: "needsConcept";
      readonly pending: NluResult;
      readonly missing: ConceptType;
      readonly speech: string;
    }
  | { readonly kind: "notUnderstood"; readonly speech: string };

export interface ItdaCapsuleOptions {
  /** 없으면 기기 없이 도는 가상 모듈을 쓴다. */
  readonly tactile?: TactileDevice;
}

/**
 * ② 비즈니스 로직 — NLU가 뽑아낸 (action, concepts)를 받아 무엇을 할지 정한다.
 *
 * 부수효과 중 **촉각 모듈만** 여기서 직접 건드린다(포트를 통해). 화면 이동과 음성은
 * 결과로 돌려주기만 하고 앱 계층이 수행한다 — 그래야 기기 없이도 테스트된다.
 */
export class ItdaCapsule {
  private readonly tactile: TactileDevice;

  constructor(options: ItdaCapsuleOptions = {}) {
    this.tactile = options.tactile ?? new InMemoryTactileDevice();
  }

  async execute(nlu: NluResult): Promise<CapsuleResult> {
    const action = nlu.action;
    if (!action) {
      return { kind: "notUnderstood", speech: "무슨 말인지 잘 모르겠어요. 도움말이라고 말해 보세요." };
    }

    const spec = actionSpec(action);
    const missing = (spec.required ?? []).find((type) => conceptValue(nlu, type) === undefined);
    if (missing) {
      return {
        kind: "needsConcept",
        pending: nlu,
        missing,
        speech: spec.prompts?.[missing] ?? "조금 더 자세히 말씀해 주세요.",
      };
    }

    switch (action) {
      case "OpenScreen":
        return this.openScreen(nlu);
      case "RaiseDots":
        return this.raiseDots(nlu);
      case "LowerDots":
        return this.lowerDots(nlu);
      case "ConnectDevice":
        return this.connectDevice();
      case "StartReview":
        return this.startReview(nlu);
      case "ShowWrongNotes":
        return this.showWrongNotes(nlu);
      case "ShowProgress":
        return this.showProgress(nlu);
      case "JoinLesson":
        return moveTo("lesson", "수업 화면으로 갈게요.");
      case "GoBack":
        return { kind: "done", speech: "이전 화면으로 갈게요.", navigation: { kind: "back" } };
      case "ShowHelp":
        return { kind: "done", speech: HELP_SPEECH, navigation: { kind: "stay" } };
      case "Cancel":
        return { kind: "done", speech: "네, 취소했어요.", navigation: { kind: "stay" } };
    }
  }

  /** 모듈이 지금 올리고 있는 패턴. */
  get cell(): number {
    return this.tactile.cell;
  }

  private openScreen(nlu: NluResult): CapsuleResult {
    const destination = destinationById(conceptValue(nlu, "ScreenName"));
    if (!destination) {
      return {
        kind: "needsConcept",
        pending: nlu,
        missing: "ScreenName",
        speech: actionSpec("OpenScreen").prompts?.ScreenName ?? "어느 화면으로 갈까요?",
      };
    }
    return {
      kind: "done",
      speech: `${destination.title} 화면으로 갈게요.`,
      navigation: { kind: "to", route: destination.route, title: destination.title },
    };
  }

  private startReview(nlu: NluResult): CapsuleResult {
    const count = conceptValue(nlu, "QuestionCount");
    const level = conceptValue(nlu, "BrailleLevel");
    const parts = [level ? `${level} 난이도로` : "", count ? `${count}문제` : ""].filter(Boolean);
    const detail = parts.length > 0 ? ` ${parts.join(" ")}` : "";
    return moveTo("review", `학습을${detail} 시작할게요.`);
  }

  private showWrongNotes(nlu: NluResult): CapsuleResult {
    const period = periodLabel(conceptValue(nlu, "Period"));
    return moveTo("wrong-note", `${period} 오답을 보여드릴게요.`);
  }

  private showProgress(nlu: NluResult): CapsuleResult {
    const period = periodLabel(conceptValue(nlu, "Period"));
    return moveTo("record", `${period} 학습 기록을 보여드릴게요.`);
  }

  private async raiseDots(nlu: NluResult): Promise<CapsuleResult> {
    const value = conceptValue(nlu, "DotNumber") ?? DOT_ALL;
    const command: TactileCommand =
      value === DOT_ALL ? { kind: "raiseAll" } : { kind: "raise", dots: decodeDots(value) as Dot[] };
    return this.runTactile(command);
  }

  private async lowerDots(nlu: NluResult): Promise<CapsuleResult> {
    // 번호를 안 말하면 전부 내린다 — "점 내려줘"는 초기화에 가깝다.
    const value = conceptValue(nlu, "DotNumber") ?? DOT_ALL;
    const dots = value === DOT_ALL ? [] : (decodeDots(value) as Dot[]);
    const command: TactileCommand =
      dots.length === 0 ? { kind: "lowerAll" } : { kind: "lower", dots };
    return this.runTactile(command);
  }

  private async runTactile(command: TactileCommand): Promise<CapsuleResult> {
    try {
      const reply = await this.tactile.send(command);
      return {
        kind: "done",
        speech: describeCommand(command),
        navigation: { kind: "stay" },
        detail: reply,
      };
    } catch (error) {
      return { kind: "done", speech: tactileMessage(error), navigation: { kind: "stay" } };
    }
  }

  private async connectDevice(): Promise<CapsuleResult> {
    try {
      const name = await this.tactile.connect();
      return { kind: "done", speech: `${name}에 연결했어요.`, navigation: { kind: "stay" } };
    } catch (error) {
      return { kind: "done", speech: tactileMessage(error), navigation: { kind: "stay" } };
    }
  }
}

function moveTo(destinationId: string, speech: string): CapsuleResult {
  const destination = destinationById(destinationId);
  if (!destination) return { kind: "done", speech, navigation: { kind: "stay" } };
  return {
    kind: "done",
    speech,
    navigation: { kind: "to", route: destination.route, title: destination.title },
  };
}

/** 모듈을 못 쓸 때는 이유를 그대로 읽어 준다 — 조용히 실패하면 원인을 알 수 없다. */
function tactileMessage(error: unknown): string {
  if (error instanceof TactileUnavailableError) return error.message;
  return "촉각 모듈과 통신하지 못했어요. 연결을 확인해 주세요.";
}

function periodLabel(period: string | undefined): string {
  switch (period) {
    case "today":
      return "오늘";
    case "week":
      return "이번 주";
    case "last_week":
      return "지난주";
    case "all":
      return "전체 기간";
    default:
      return "최근";
  }
}

const HELP_SPEECH =
  "학습 시작해줘, 이번 주 오답 보여줘, 내 실력 알려줘, 설정 열어줘, 3번 점 올려줘 처럼 말씀해 주세요.";

export type { ActionId };
