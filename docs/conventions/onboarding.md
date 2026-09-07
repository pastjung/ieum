# 온보딩 체크리스트

새 팀원(또는 포맷한 노트북)이 개발을 시작하기 전에 완료해야 하는 목록. 위에서 아래로 순서대로.

## 1. 공통 도구 (Windows 기준)

- [ ] Git for Windows — 모든 훅·스크립트는 Git Bash에서 돈다. 터미널은 Git Bash를 표준으로
- [ ] Python 3.12+ — `winget install Python.Python.3.12` (Microsoft Store 버전 아님). pre-commit 실행에 필요
- [ ] Node.js LTS — `winget install OpenJS.NodeJS.LTS`. web·mobile 개발과 린트에 필요
- [ ] Docker Desktop — 로컬 개발 환경(`docker compose up --build --watch`) 실행에 필요

## 2. 레포 설정 (클론 후 1회)

- [ ] `git config --global user.name` / `user.email` 설정
- [ ] pre-commit 활성화 — **이걸 안 하면 커밋 검증이 조용히 꺼진 채 개발하게 된다**:

  ```bash
  python -m pip install --user pre-commit
  python -m pre_commit install --hook-type pre-commit --hook-type commit-msg
  ```

  `--hook-type commit-msg`까지 해야 커밋 메시지 검사가 켜진다.
  `python`이 Microsoft Store 안내를 띄우면: `winget install Python.Python.3.12` 후 터미널을 새로 연다.
  (모듈 이름은 `pre_commit` — 언더스코어. `pre-commit: command not found`는 PATH 문제이므로 모듈 방식이면 무관하다)
- [ ] `.env.example`을 복사해 `.env` 작성 — 실제 값은 팀 보안 채널에서 받는다 (절대 커밋 금지)

## 3. 파트별 의존성

- [ ] web: `cd apps/web && npm install`
- [ ] mobile: `cd apps/mobile && npm install`
- [ ] ai: `cd apps/ai && python -m venv .venv` → `source .venv/Scripts/activate` → `python -m pip install -r requirements-dev.txt`
- [ ] backend: 없음 — gradlew가 처음 실행될 때 알아서 받는다

## 4. AI 도구

- [ ] Claude Code CLI 설치(PowerShell: `irm https://claude.ai/install.ps1 | iex`)·로그인 — 레포 루트의 Git Bash에서 `claude` 실행
- [ ] **프로젝트 코드 작업은 Claude Code CLI에서 하는 것이 표준** — 팀 하네스(규약·권한·훅·스킬)는 CLI에서만 적용된다. Claude 데스크톱 앱(Cowork)에는 .claude/ 설정이 적용되지 않는다
- [ ] (선택) Codex 설치 — AGENTS.md를 자동으로 읽는다
- [ ] AGENTS.md와 docs/conventions/README.md 통독 (30분이면 충분)

## 5. 동작 확인

- [ ] 아무 파일 수정 후 커밋 → pre-commit 검사들이 실행되는지
- [ ] 일부러 `수정함` 같은 메시지로 커밋 시도 → 커밋 메시지 검사에 차단되는지 (확인 후 올바른 메시지로)
- [ ] `docker compose up --build` 로 로컬 서비스가 기동되는지
