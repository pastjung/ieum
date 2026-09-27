import { ApiError } from "@ieum/api-client";
import { brailleDictionarySchema } from "@ieum/contracts";
import { type Cell, cellToDots } from "@ieum/domain";
import { api } from "./apiClient";

export interface DictEntry {
  word: string;
  cells: Cell[];
  /** 칸마다 요소 이름("약자 가", "받침 ㅇ"). 등록된 문제에서 온 결과는 이름이 없어 null */
  labels: (string | null)[];
  /** 화면·낭독용 한 줄. 이름이 있으면 "약자 가 · 약자 바 · 받침 ㅇ", 없으면 "1·2·4·6 / 4·5 / 2·3·5·6" */
  breakdown: string;
}

export type LookupResult =
  | { kind: "found"; entry: DictEntry }
  | { kind: "unsupported" }
  | { kind: "error"; message: string };

function dotsText(cell: Cell): string {
  const dots = cellToDots(cell);
  return dots.length === 0 ? "빈 칸" : dots.join("·");
}

/**
 * 점자 사전 조회. 등록된 문제에 같은 글자가 있으면 그 정답 칸을, 없으면 서버가 AI 점역기로 바꾼 칸을 받는다
 * (`GET /braille-dictionary?word=`). 한글 단어·띄어쓴 여러 단어·낱자('ㄱ')를 찾을 수 있다.
 */
export async function lookupWord(word: string): Promise<LookupResult> {
  const query = word.trim();
  if (query.length === 0) return { kind: "unsupported" };
  try {
    const res = brailleDictionarySchema.parse(
      await api.get(`/braille-dictionary?word=${encodeURIComponent(query)}`),
    );
    const cells = res.cells.map((c) => c.pattern);
    const labels = res.cells.map((c) => c.label);
    const breakdown = labels.every((l) => l)
      ? labels.join(" · ")
      : cells.map(dotsText).join(" / ");
    return { kind: "found", entry: { word: res.word, cells, labels, breakdown } };
  } catch (error) {
    if (error instanceof ApiError && error.code === "BRAILLE_TEXT_UNSUPPORTED") return { kind: "unsupported" };
    if (error instanceof ApiError && error.code === "NETWORK_UNAVAILABLE") {
      return { kind: "error", message: "서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요." };
    }
    return { kind: "error", message: "점자로 바꾸지 못했어요. 잠시 후 다시 시도해 주세요." };
  }
}
