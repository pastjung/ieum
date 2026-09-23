"""kbraille.py — 규칙 기반 한글→점자 변환기 프로토타입 (순수 한글 어절 전용)

구현 범위: 한국 점자 규정(2024) 한글 점자 제1항~제18항
  제1항 초성 / 제2항 된소리 / 제3항 종성 / 제4항 쌍받침 / 제5항 겹받침
  제6항·제7항 모음 / 제11항·제12항 구분표
  제13항 약자(가나다마바사자카타파하) / 제14항 모음 뒤 약자 미사용 / 팠 예외
  제15항 약자(억언얼연열영옥온옹운울은을인것) / 제16항 까싸껏껐 / 제17항 성썽정쩡청
  제18항 약어 7개 (+붙임/다만)
미구현: 숫자, 로마자, 문장부호, 옛글자, 줄바꿈 예외

핵심 출력: 셀마다 (dots, KC 태그, 적용 규칙) — 채점·오류귀속·KT 입력의 기반.
"""

from dataclasses import InitVar, dataclass, field

from app.infrastructure.braille_core.braille_utils import dots_to_cell, dots_to_int, str_to_dots

CHO = list("ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ")
JUNG = list("ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ")
JONG = [
    "",
    "ㄱ",
    "ㄲ",
    "ㄳ",
    "ㄴ",
    "ㄵ",
    "ㄶ",
    "ㄷ",
    "ㄹ",
    "ㄺ",
    "ㄻ",
    "ㄼ",
    "ㄽ",
    "ㄾ",
    "ㄿ",
    "ㅀ",
    "ㅁ",
    "ㅂ",
    "ㅄ",
    "ㅅ",
    "ㅆ",
    "ㅇ",
    "ㅈ",
    "ㅊ",
    "ㅋ",
    "ㅌ",
    "ㅍ",
    "ㅎ",
]

# ── 점형 표 (점 번호 문자열) ────────────────────────────────────────────
CHO_DOTS = {
    "ㄱ": "4",
    "ㄴ": "14",
    "ㄷ": "24",
    "ㄹ": "5",
    "ㅁ": "15",
    "ㅂ": "45",
    "ㅅ": "6",
    "ㅈ": "46",
    "ㅊ": "56",
    "ㅋ": "124",
    "ㅌ": "125",
    "ㅍ": "145",
    "ㅎ": "245",
}
JONG_DOTS = {
    "ㄱ": "1",
    "ㄴ": "25",
    "ㄷ": "35",
    "ㄹ": "2",
    "ㅁ": "26",
    "ㅂ": "12",
    "ㅅ": "3",
    "ㅇ": "2356",
    "ㅈ": "13",
    "ㅊ": "23",
    "ㅋ": "235",
    "ㅌ": "236",
    "ㅍ": "256",
    "ㅎ": "356",
}
JUNG_DOTS = {
    "ㅏ": ["126"],
    "ㅑ": ["345"],
    "ㅓ": ["234"],
    "ㅕ": ["156"],
    "ㅗ": ["136"],
    "ㅛ": ["346"],
    "ㅜ": ["134"],
    "ㅠ": ["146"],
    "ㅡ": ["246"],
    "ㅣ": ["135"],
    "ㅐ": ["1235"],
    "ㅒ": ["345", "1235"],
    "ㅔ": ["1345"],
    "ㅖ": ["34"],
    "ㅘ": ["1236"],
    "ㅙ": ["1236", "1235"],
    "ㅚ": ["13456"],
    "ㅝ": ["1234"],
    "ㅞ": ["1234", "1235"],
    "ㅟ": ["134", "1235"],
    "ㅢ": ["2456"],
}
DOUBLE_CHO = {"ㄲ": "ㄱ", "ㄸ": "ㄷ", "ㅃ": "ㅂ", "ㅆ": "ㅅ", "ㅉ": "ㅈ"}
DOUBLE_JONG = {
    "ㄳ": "ㄱㅅ",
    "ㄵ": "ㄴㅈ",
    "ㄶ": "ㄴㅎ",
    "ㄺ": "ㄹㄱ",
    "ㄻ": "ㄹㅁ",
    "ㄼ": "ㄹㅂ",
    "ㄽ": "ㄹㅅ",
    "ㄾ": "ㄹㅌ",
    "ㄿ": "ㄹㅍ",
    "ㅀ": "ㄹㅎ",
    "ㅄ": "ㅂㅅ",
    "ㄲ": "ㄱㄱ",
}
DDN = "6"  # 된소리표
SEP = "36"  # 구분표
# 제13항 약자 (초성+ㅏ)
ABBR_A = {
    "ㄱ": "1246",
    "ㄴ": "14",
    "ㄷ": "24",
    "ㅁ": "15",
    "ㅂ": "45",
    "ㅅ": "123",
    "ㅈ": "46",
    "ㅋ": "124",
    "ㅌ": "125",
    "ㅍ": "145",
    "ㅎ": "245",
}
ABBR_A_VOWEL_BLOCK = set("ㄴㄷㅁㅂㅈㅋㅌㅍㅎ")  # 제14항: 뒤에 모음이 오면 약자 X (가·사 제외)
# 제15항 약자 (중성, 종성) → 점형
ABBR_VC = {
    ("ㅓ", "ㄱ"): "1456",
    ("ㅓ", "ㄴ"): "23456",
    ("ㅓ", "ㄹ"): "2345",
    ("ㅕ", "ㄴ"): "16",
    ("ㅕ", "ㄹ"): "1256",
    ("ㅕ", "ㅇ"): "12456",
    ("ㅗ", "ㄱ"): "1346",
    ("ㅗ", "ㄴ"): "12356",
    ("ㅗ", "ㅇ"): "123456",
    ("ㅜ", "ㄴ"): "1245",
    ("ㅜ", "ㄹ"): "12346",
    ("ㅡ", "ㄴ"): "1356",
    ("ㅡ", "ㄹ"): "2346",
    ("ㅣ", "ㄴ"): "12345",
}
ABBR_VC_NAME = {
    ("ㅓ", "ㄱ"): "억",
    ("ㅓ", "ㄴ"): "언",
    ("ㅓ", "ㄹ"): "얼",
    ("ㅕ", "ㄴ"): "연",
    ("ㅕ", "ㄹ"): "열",
    ("ㅕ", "ㅇ"): "영",
    ("ㅗ", "ㄱ"): "옥",
    ("ㅗ", "ㄴ"): "온",
    ("ㅗ", "ㅇ"): "옹",
    ("ㅜ", "ㄴ"): "운",
    ("ㅜ", "ㄹ"): "울",
    ("ㅡ", "ㄴ"): "은",
    ("ㅡ", "ㄹ"): "을",
    ("ㅣ", "ㄴ"): "인",
}
GEOT = ["456", "234"]  # 것
# 제18항 약어
ABBR_WORDS = {
    "그래서": ["1", "234"],
    "그러나": ["1", "14"],
    "그러면": ["1", "25"],
    "그러므로": ["1", "26"],
    "그런데": ["1", "1345"],
    "그리고": ["1", "136"],
    "그리하여": ["1", "156"],
}


@dataclass
class Cell:
    """점자 한 칸 + 지식요소 태그 + 근거 조항."""

    dots_raw: InitVar[str | list[int]]
    kc: str  # 지식요소 태그, e.g. "초성:ㄱ", "약자:ㄱㅏ", "규칙:구분표(예)"
    rule: str  # 근거 조항, e.g. "제13항"
    syl: int  # 원문 음절 인덱스
    dots: list[int] = field(init=False)  # 점 번호 리스트, e.g. [1, 2, 4, 6]

    def __post_init__(self, dots_raw: str | list[int]) -> None:
        self.dots = str_to_dots(dots_raw) if isinstance(dots_raw, str) else list(dots_raw)

    @property
    def value(self) -> int:
        return dots_to_int(self.dots)

    def uni(self) -> str:
        return dots_to_cell(self.dots)


@dataclass
class Syllable:
    idx: int
    text: str
    cho: str
    jung: str
    jong: str
    cells: list[int]  # 이 음절이 차지하는 셀 인덱스
    rule_kcs: list[str]  # 셀을 만들지 않는 규칙 KC


@dataclass
class RuleKC:
    syl: int
    kc: str
    rule: str


@dataclass
class AnalyzeResult:
    cells: list[Cell]
    syllables: list[Syllable]
    kc_rules: list[RuleKC]
    kc_all: list[str]


def decompose(word: str) -> list[tuple[str, str, str]]:
    out = []
    for ch in word:
        code = ord(ch) - 0xAC00
        if not 0 <= code < 11172:
            raise ValueError(f"non-Hangul: {ch}")
        out.append((CHO[code // 588], JUNG[(code % 588) // 28], JONG[code % 28]))
    return out


def _cho(c: str, i: int, out: list[Cell]) -> None:
    if c == "ㅇ":
        return
    if c in DOUBLE_CHO:
        out.append(Cell(DDN, "규칙:된소리표", "제2항", i))
        out.append(Cell(CHO_DOTS[DOUBLE_CHO[c]], f"초성:{DOUBLE_CHO[c]}", "제2항", i))
    else:
        out.append(Cell(CHO_DOTS[c], f"초성:{c}", "제1항", i))


def _jung(v: str, i: int, out: list[Cell]) -> None:
    rule = "제6항" if v in "ㅏㅑㅓㅕㅗㅛㅜㅠㅡㅣ" else "제7항"
    for d in JUNG_DOTS[v]:
        out.append(Cell(d, f"중성:{v}", rule, i))


def _jong(j: str, i: int, out: list[Cell]) -> None:
    if not j:
        return
    if j == "ㅆ":
        out.append(Cell("34", "종성:ㅆ", "제4항", i))
        return
    if j == "ㄲ":
        out.append(Cell("1", "종성:ㄲ", "제4항", i))
        out.append(Cell("1", "종성:ㄲ", "제4항", i))
        return
    if j in DOUBLE_JONG:
        for c in DOUBLE_JONG[j]:
            out.append(Cell(JONG_DOTS[c], f"종성:{j}", "제5항", i))
        return
    out.append(Cell(JONG_DOTS[j], f"종성:{j}", "제3항", i))


def convert(word: str, use_word_abbr: bool = True) -> list[Cell]:
    """순수 한글 어절 → [Cell].  use_word_abbr=False 면 제18항 약어를 쓰지 않고 풀어 쓴다(오류 모델용)."""
    # 제18항 약어: 단어 전체 또는 약어로 시작(붙임). 약어 앞에 글자가 붙으면 미사용(다만).
    for aw, dots in ABBR_WORDS.items() if use_word_abbr else ():
        if word.startswith(aw):
            out: list[Cell] = [
                Cell(dots[0], f"약어:{aw}", "제18항", 0),
                Cell(dots[1], f"약어:{aw}", "제18항", 0),
            ]
            if len(word) > len(aw):
                rest = convert(word[len(aw) :])
                for c in rest:
                    c.syl += len(aw)
                out += rest
            return out

    syls = decompose(word)
    out: list[Cell] = []
    for i, (c, v, j) in enumerate(syls):
        nxt = syls[i + 1] if i + 1 < len(syls) else None
        prev = syls[i - 1] if i > 0 else None
        next_starts_with_vowel = nxt is not None and nxt[0] == "ㅇ"

        # ── 구분표 (제11항·제12항): 앞 음절이 받침 없이 끝나고 현재가 '예'/'애'
        if c == "ㅇ" and prev is not None and prev[2] == "":
            if v == "ㅖ":
                out.append(Cell(SEP, "규칙:구분표(예)", "제11항", i))
            elif v == "ㅐ" and prev[1] in "ㅑㅘㅜㅝ":
                out.append(Cell(SEP, "규칙:구분표(애)", "제12항", i))

        # ── 것 / 껏 / 껐 (제15항, 제16항)
        if v == "ㅓ" and j == "ㅅ" and c in ("ㄱ", "ㄲ"):
            if c == "ㄲ":
                out.append(Cell(DDN, "규칙:된소리표", "제16항", i))
            out.append(Cell(GEOT[0], "약자:것", "제15항", i))
            out.append(Cell(GEOT[1], "약자:것", "제15항", i))
            continue
        if c == "ㄲ" and v == "ㅓ" and j == "ㅆ":  # 껐 = 꺼 + ㅆ약자
            _cho(c, i, out)
            _jung(v, i, out)
            _jong(j, i, out)
            continue

        # ── 팠 예외 (제14항 붙임)
        if c == "ㅍ" and v == "ㅏ" and j == "ㅆ":
            _cho(c, i, out)
            _jung(v, i, out)
            _jong(j, i, out)
            continue

        # ── 제13항 약자 (초성+ㅏ, 받침·된소리 있어도 사용) / 제14항 모음 뒤 미사용
        base = DOUBLE_CHO.get(c, c)
        if v == "ㅏ" and base in ABBR_A:
            use = True
            # 제14항은 받침 없는 '나,다,…' 음절 바로 뒤에 모음이 올 때만 적용 (받침이 있으면 약자 사용)
            if base in ABBR_A_VOWEL_BLOCK and next_starts_with_vowel and j == "":
                use = False
            if use:
                if c in DOUBLE_CHO:
                    out.append(
                        Cell(DDN, "규칙:된소리표", "제16항" if base in "ㄱㅅ" else "제13항", i)
                    )
                out.append(Cell(ABBR_A[base], f"약자:{base}ㅏ", "제13항", i))
                _jong(j, i, out)
                continue
            # 제14항: 약자 미사용 → 풀어쓰기 (KC로 규칙 자체를 태깅)
            _cho(c, i, out)
            out.append(Cell(JUNG_DOTS[v][0], "중성:ㅏ", "제14항", i))
            _jong(j, i, out)
            continue

        # ── 제17항 성썽정쩡청
        if v == "ㅓ" and j == "ㅇ" and c in "ㅅㅆㅈㅉㅊ":
            _cho(c, i, out)
            out.append(Cell("12456", "약자:영(성정청)", "제17항", i))
            continue

        # ── 제15항 (중성+종성) 약자, 겹받침 첫 자음 포함
        j_first, j_rest = j, ""
        if j in DOUBLE_JONG:  # 쌍받침 ㄲ 포함: 꺾 = 억약자 + ㄱ (제15항 붙임 예시)
            j_first, j_rest = DOUBLE_JONG[j][0], DOUBLE_JONG[j][1]
        # 제17항의 귀결: ㅅㅆㅈㅉㅊ 뒤의 '영'약자는 '성정청'을 뜻하므로 셩/졍/쳥은 풀어쓴다 (규정 예시 '얄라셩')
        if (v, j_first) in ABBR_VC and not (v == "ㅕ" and j_first == "ㅇ" and c in "ㅅㅆㅈㅉㅊ"):
            _cho(c, i, out)
            out.append(
                Cell(ABBR_VC[(v, j_first)], f"약자:{ABBR_VC_NAME[(v, j_first)]}", "제15항", i)
            )
            if j_rest:
                out.append(Cell(JONG_DOTS[j_rest], f"종성:{j}", "제5항", i))
            continue

        # ── 일반 풀어쓰기
        _cho(c, i, out)
        _jung(v, i, out)
        _jong(j, i, out)
    return out


def analyze(word: str) -> AnalyzeResult:
    """convert 결과에 음절 구조와 '셀을 만들지 않는 규칙 KC'를 붙인다.

    점자에서 첫소리 ㅇ은 적지 않으므로(제1항 다만1) '으'와 'ㅡ'는 같은 셀이다. 학습자가 이 규칙을 아는지는
    셀 태그로는 보이지 않으니 음절 단위의 zero-width KC '규칙:초성ㅇ생략' 으로 따로 기록한다.
    """
    cells = convert(word)
    syls: list[Syllable] = []
    kc_rules: list[RuleKC] = []
    try:
        decomposed = decompose(word)
    except ValueError:
        decomposed = []
    for i, ch in enumerate(word):
        idxs = [k for k, c in enumerate(cells) if c.syl == i]
        cho, jung, jong = decomposed[i] if decomposed else ("", "", "")
        rules: list[str] = []
        if cho == "ㅇ":
            rules.append("규칙:초성ㅇ생략")
            kc_rules.append(RuleKC(i, "규칙:초성ㅇ생략", "제1항 다만1"))
        if any(cells[k].rule == "제14항" for k in idxs):
            rules.append("규칙:약자미사용")
            kc_rules.append(RuleKC(i, "규칙:약자미사용", "제14항"))
        syls.append(Syllable(i, ch, cho, jung, jong, idxs, rules))
    kc_all = sorted({c.kc for c in cells} | {r.kc for r in kc_rules})
    return AnalyzeResult(cells, syls, kc_rules, kc_all)


def to_unicode(word: str) -> str:
    return "".join(c.uni() for c in convert(word))


def to_cells(word: str) -> list[list[int]]:
    """[[dots], ...] — 팀 규약 셀 배열"""
    return [c.dots for c in convert(word)]
