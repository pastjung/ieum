# gitleaks가 설정 컴파일 단계에서 죽어 커밋이 전면 차단된 문제

## 증상

pre-commit을 활성화한 시점부터 모든 커밋이 실패한다. gitleaks 훅이 panic으로 exit 2.
**스캔할 파일이 하나도 없는 빈 폴더를 스캔해도 죽는다** — 파일 내용과 무관하다는 신호다.

## 원인

`.gitleaks.toml` 규칙에 부정형 전방탐색 `(?!...)`을 사용했다. 이건 PCRE 문법이고,
gitleaks는 Go의 **RE2** 엔진을 쓴다. RE2는 선형 시간 성능을 보장하기 위해 전방탐색·
후방탐색을 아예 지원하지 않는다. 규칙을 컴파일하는 시작 단계에서 실패하므로 스캔
대상과 무관하게 항상 죽는다.

**왜 늦게 발견됐나:** 규칙을 추가하던 시점에 gitleaks 바이너리가 로컬에 없었고
(`scripts/run-gitleaks.sh`가 첫 실행 때 내려받는 구조), 검증을 파이썬 `re`로 했다.
파이썬은 전방탐색을 지원하므로 시뮬레이션은 통과했다. 실제 엔진에서 한 번도 실행되지
않은 채 dev에 병합됐고, 팀원이 pre-commit을 켜는 순간 드러났다.

## 해결

"~가 아니면 잡는다"를 정규식에서 빼고 gitleaks의 allowlist로 옮긴다. 넓게 잡은 뒤
자리표시자를 제외하는 순서로 뒤집으면 RE2로도 같은 동작이 된다.

```toml
[[rules]]
id = "itda-generic-password-assignment"
regex = '''(?i)[a-z0-9_.\-]*(password|passwd|secret|token|apikey|api_key)\s*[:=]\s*['"][^\s'"]{8,}['"]'''

  # RE2에는 부정형 전방탐색이 없다. 플레이스홀더 제외는 allowlist로 처리한다
  [rules.allowlist]
  regexTarget = "match"
  regexes = ['''['"](\$\{|\{\{|<|change|your|example|placeholder|dummy|test)''']
```

## 재발 방지

- **도구 설정·정규식은 그 도구의 실제 엔진으로 검증한다.** 다른 언어의 정규식으로
  돌린 시뮬레이션은 검증이 아니다. (같은 계보의 사고: `2026-09-07-pyright-libatomic-slim-image.md`)
- 검증 절차 3단계: ① 빈 폴더 스캔으로 설정 컴파일 확인 → ② 진짜 시크릿 샘플 탐지 확인
  → ③ 자리표시자 오탐 0건 확인.
- **테스트 데이터는 무작위처럼 보이는 값을 쓴다.** `abcdefghij...`처럼 뻔한 문자열은
  gitleaks 기본 설정의 stopwords에 걸려 탐지되지 않으므로, 멀쩡한 규칙이 고장난 것처럼
  보인다. 실제로 이번 검증 중 Mattermost 규칙을 오진할 뻔했다.

## 임시 우회 (수정 병합 전)

`--no-verify`(검사 전체 해제) 대신 그 훅만 건너뛴다:

```bash
SKIP=gitleaks git commit -m "..."
```
