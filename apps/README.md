# 애플리케이션 로컬 실행 가이드

이 문서는 `apps/` 아래의 AI, Backend, Web, Mobile 프로젝트를 로컬에서 설치하고 실행하는 방법을 설명한다. 모든 명령은 저장소 루트에서 시작하는 것을 기준으로 한다.

## 1. 서비스 구성

| 프로젝트 | 기술 | 기본 주소 | 역할 |
| --- | --- | --- | --- |
| `ai` | FastAPI | `http://localhost:8000` | 점자 변환 및 규칙 검증 API |
| `backend` | Spring Boot | `http://localhost:8080/api/v1` | 사용자 및 서비스 API |
| `web` | React + Vite | `http://localhost:5173` | 교육자용 웹 |
| `mobile` | React Native | Metro `http://localhost:8081` | 학습자용 Android 앱 |

Web과 Mobile은 Backend API를 사용한다. 전체 서비스를 실행할 때는 다음 순서를 권장한다.

```text
AI → Backend → Web → Mobile
```

각 서비스는 별도의 터미널에서 실행한다.

## 2. 사전 요구사항

- Git
- Java 25
- Node.js 20 이상과 npm
- Python 3.12 이상
- Android Studio, Android SDK 및 Android 에뮬레이터
- iOS 실행 시 macOS와 Xcode

설치된 버전은 다음 명령으로 확인한다.

```powershell
java --version
node --version
npm --version
python --version
```

> Backend의 Gradle 배포판과 각 프로젝트의 패키지는 최초 실행 시 내려받으므로 인터넷 연결이 필요하다.

## 3. AI 실행

### Windows PowerShell

```powershell
cd apps/ai

py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --reload --port 8000
```

`py` 명령을 사용할 수 없다면 설치된 Python 실행 파일로 가상 환경을 생성한다.

```powershell
python -m venv .venv
```

### macOS/Linux

```bash
cd apps/ai

python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --reload --port 8000
```

실행 확인:

- Swagger UI: `http://localhost:8000/docs`
- Health Check: `http://localhost:8000/api/v1/health`

테스트:

```powershell
python -m pytest
```

상세한 프로젝트 구조는 [AI README](./ai/README.md)를 참고한다.

## 4. Backend 실행

Backend는 기본적으로 `local` 프로필과 인메모리 H2 데이터베이스를 사용하므로 별도의 데이터베이스 설치 없이 실행할 수 있다.

### Windows PowerShell

```powershell
cd apps/backend
.\gradlew.bat bootRun
```

### macOS/Linux

```bash
cd apps/backend
chmod +x gradlew
./gradlew bootRun
```

실행 확인:

- Health Check: `http://localhost:8080/api/v1/health`
- H2 Console: `http://localhost:8080/api/v1/h2-console`

H2 Console 접속 정보:

| 항목 | 값 |
| --- | --- |
| JDBC URL | `jdbc:h2:mem:itda` |
| User Name | `sa` |
| Password | 입력하지 않음 |

테스트:

```powershell
# Windows
.\gradlew.bat test

# macOS/Linux
./gradlew test
```

상세한 프로젝트 구조와 운영 프로필은 [Backend README](./backend/README.md)를 참고한다.

## 5. Web 실행

Backend를 먼저 실행한 뒤 Web을 실행한다. 개발 서버는 `/api` 요청을 `http://localhost:8080`으로 전달한다.

```powershell
cd front
pnpm install
pnpm dev:web
```

브라우저에서 `http://localhost:5173`으로 접속한다.

프로덕션 빌드 확인:

```powershell
cd front
pnpm --filter @ieum/web build
```

상세한 프로젝트 구조는 [Web README](../front/apps/web/README.md)를 참고한다.

## 6. Mobile 실행

현재 저장소에는 React Native 네이티브 프로젝트인 `android/`, `ios/`가 포함되어 있지 않다. 최초 한 번 네이티브 폴더를 생성한 후 실행해야 한다.

### 6.1 네이티브 폴더 생성

저장소 루트에서 다음 명령을 실행한다.

#### Windows PowerShell

```powershell
cd apps

npx @react-native-community/cli@20.2.0 init ItdaMobile `
  --version 0.87.1 `
  --skip-install `
  --skip-git-init

Move-Item -LiteralPath .\ItdaMobile\android -Destination .\mobile\android
Move-Item -LiteralPath .\ItdaMobile\ios -Destination .\mobile\ios
Remove-Item -LiteralPath .\ItdaMobile -Recurse -Force
```

#### macOS/Linux

```bash
cd apps

npx @react-native-community/cli@20.2.0 init ItdaMobile \
  --version 0.87.1 \
  --skip-install \
  --skip-git-init

mv ItdaMobile/android mobile/android
mv ItdaMobile/ios mobile/ios
rm -rf ItdaMobile
```

> 이미 `apps/mobile/android`와 `apps/mobile/ios`가 존재하면 이 과정은 건너뛴다.

### 6.2 Android 앱 실행

Android Studio에서 에뮬레이터를 먼저 실행한다.

첫 번째 터미널에서 Metro 개발 서버를 실행한다.

```powershell
cd apps/mobile
npm install
npm start
```

두 번째 터미널에서 Android 앱을 설치하고 실행한다.

```powershell
cd apps/mobile
npm run android
```

Android 에뮬레이터는 호스트 PC의 Backend에 `http://10.0.2.2:8080/api/v1`로 접근한다. 실기기에서는 [API 클라이언트](./mobile/src/shared/api/client.ts)의 주소를 개발 PC의 LAN IP로 변경해야 한다.

테스트:

```powershell
npm test -- --runInBand
```

상세한 프로젝트 구조와 BLE 개발 메모는 [Mobile README](./mobile/README.md)를 참고한다.

## 7. 전체 서비스 실행 요약

의존성 설치와 Mobile 네이티브 폴더 생성을 완료한 뒤, 네 개의 터미널에서 다음 명령을 실행한다.

### Terminal 1 — AI

```powershell
cd apps/ai
.\.venv\Scripts\Activate.ps1
python -m uvicorn app.main:app --reload --port 8000
```

### Terminal 2 — Backend

```powershell
cd apps/backend
.\gradlew.bat bootRun
```

### Terminal 3 — Web

```powershell
cd front
pnpm dev:web
```

### Terminal 4 — Mobile

```powershell
cd apps/mobile
npm start
```

Metro 실행 후 별도 터미널에서 `npm run android`를 실행한다.

## 8. 자주 발생하는 문제

### PowerShell에서 가상 환경을 활성화할 수 없음

현재 터미널에서만 스크립트 실행 정책을 완화한 뒤 다시 활성화한다.

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1
```

### Backend가 Java Toolchain 오류로 실행되지 않음

`java --version`으로 Java 25가 설치되어 있는지 확인하고 `JAVA_HOME`이 해당 JDK를 가리키도록 설정한다.

### Mobile에서 Backend에 연결할 수 없음

- Android 에뮬레이터에서는 `localhost` 대신 `10.0.2.2`를 사용한다.
- 실기기에서는 개발 PC와 같은 네트워크에 연결하고 개발 PC의 LAN IP를 사용한다.
- Windows 방화벽에서 Backend의 `8080` 포트 접근이 허용되어 있는지 확인한다.

### 포트가 이미 사용 중임

기본 포트 `8000`, `8080`, `5173`, `8081`을 사용하는 다른 프로세스를 종료한 뒤 서비스를 다시 실행한다.
