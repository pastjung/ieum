# 저장소 폴더 구조

## 1. 목적

이 문서는 이음(I:EUM) 모노레포의 표준 폴더 구조와 각 디렉터리의 책임을 정의한다.

폴더 구조는 다음 원칙을 따른다.

- GitLab 협업 설정은 `.gitlab/` 아래에 둔다.
- 실행 가능한 애플리케이션은 `apps/` 아래에 둔다.
- 팀 규칙과 설계 문서는 `docs/`에 둔다.
- 각 애플리케이션은 독립적으로 빌드하고 테스트할 수 있어야 한다.

## 2. 프로젝트 구조

```text
S15P21B201/
├─ .gitlab/
│  ├─ issue_templates/
│  │  ├─ Feature.md
│  │  ├─ Bug.md
│  │  └─ Chore.md
│  ├─ merge_request_templates/
│  │  └─ Default.md
│
├─ apps/
│  ├─ backend/
│  │  ├─ src/main/java/
│  │  ├─ src/main/resources/
│  │  ├─ src/test/java/
│  │  ├─ build.gradle
│  │  ├─ settings.gradle
│  │  └─ README.md
│  │
│  ├─ web/
│  │  ├─ src/
│  │  │  ├─ app/
│  │  │  ├─ domains/
│  │  │  ├─ pages/
│  │  │  └─ shared/
│  │  ├─ package.json
│  │  ├─ package-lock.json
│  │  └─ README.md
│  │
│  ├─ mobile/
│  │  ├─ src/
│  │  │  ├─ app/
│  │  │  ├─ domains/
│  │  │  ├─ screens/
│  │  │  └─ shared/
│  │  ├─ package.json
│  │  ├─ package-lock.json
│  │  └─ README.md
│  │
│  └─ ai/
│     ├─ app/
│     │  ├─ application/
│     │  ├─ core/
│     │  ├─ domain/
│     │  ├─ infrastructure/
│     │  └─ presentation/
│     ├─ tests/
│     ├─ requirements.txt
│     └─ README.md
│
├─ docs/
│  ├─ conventions/
│  └─ images/
│
├─ .editorconfig
├─ .gitattributes
├─ .gitignore
└─ README.md
```

프로젝트는 실제로 사용하는 디렉터리만 생성한다. Git은 빈 디렉터리를 추적하지 않으므로 단순히 구조를 보이기 위한 `.gitkeep`은 사용하지 않는다.

## 3. 디렉터리별 책임

### `.gitlab/`

GitLab 협업 설정을 저장한다.

- `issue_templates/`: 기능, 버그, 작업 이슈의 작성 형식을 통일한다.
- `merge_request_templates/`: MR 설명, 테스트 결과, 체크리스트 형식을 통일한다.

GitLab에서 템플릿을 인식하려면 해당 파일이 기본 브랜치에 존재해야 한다.

### `apps/`

직접 실행하거나 배포할 수 있는 애플리케이션을 저장한다.

- `backend`: Spring Boot API 서버
- `web`: 교육자용 React 웹 애플리케이션
- `mobile`: 학습자용 React Native 애플리케이션
- `ai`: 점역, 채점, 추천 등을 담당하는 Python 서비스

서비스별 `Dockerfile`, 빌드 파일, 테스트, README는 해당 서비스 폴더에 둔다. 한 서비스의 빌드가 다른 서비스 폴더의 내부 구현에 직접 의존하지 않도록 한다.

### `docs/`

- `conventions`: 폴더, 브랜치, 커밋 등 팀 규칙
- `images`: 문서에서 사용하는 이미지

## 4. 루트 파일 원칙

- `.editorconfig`: 에디터와 언어별 기본 들여쓰기, 문자셋, 줄바꿈을 통일한다.
- `.gitattributes`: 텍스트 줄바꿈과 바이너리 파일 처리 방식을 통일한다.
- `.gitignore`: 로컬 환경, 빌드 결과, 비밀값을 저장소에서 제외한다.
- `README.md`: 프로젝트 소개와 가장 기본적인 실행 방법만 제공하고, 세부 규칙은 `docs/`로 연결한다.

프로젝트는 Java, TypeScript, Python 애플리케이션의 빌드 설정을 각 애플리케이션 폴더에서 독립적으로 관리한다. 루트 통합 빌드 도구를 사용하지 않으므로 루트 `build.gradle`, `settings.gradle`, `package.json`을 두지 않는다.

## 5. 저장소에 포함하지 않는 항목

- `.env`와 비밀키
- IDE 개인 설정
- `node_modules/`, Gradle 빌드 결과, Python 가상환경
- 테스트 결과와 임시 로그
- Android 서명 키와 배포 인증서
- 로컬 데이터베이스 및 개인용 데이터 파일

예외적으로 공유해야 하는 설정은 비밀값을 제거한 예제 파일로 제공한다.
