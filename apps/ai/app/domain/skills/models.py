"""학습 요소(learning skill) 도메인 모델 — 백엔드 learning_skills 와 1:1.

요소를 식별하는 키는 (skill_type, metadata) 이며 id 는 카탈로그(kc_catalog.json)에서 1~100 으로 고정된다.
도메인 계층은 FastAPI·파일 IO 에 의존하지 않는다.
"""

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class LearningSkill:
    id: int
    kc: str  # 내부 태그, e.g. "종성:ㄱ"
    skill_type: str  # 백엔드 SkillType enum 값, e.g. "SKILL_03" (규정 조항 번호)
    metadata: str  # 유형 안에서 요소를 구분하는 값(≤10자), e.g. "ㄱ"
    display_name: str  # "받침 ㄱ"
    stage: str  # 자음·모음·약자·약어·단어·문장
    level: str  # 백엔드 Level enum: CONSONANT VOWEL CONTRACTION CONTRACTION_WORD WORD SENTENCE


@dataclass(frozen=True)
class SkillSet:
    """백엔드 SkillSet(skillType, metadata) 에 대응."""

    skill_type: str
    metadata: str


class SkillCatalog(Protocol):
    def by_kc(self, kc: str) -> LearningSkill: ...

    def by_id(self, skill_id: int) -> LearningSkill | None: ...

    def all(self) -> list[LearningSkill]: ...
