# mobile — React Native (bare CLI)

학습자용 모바일 앱. BLE 촉각 장치 연동(`react-native-ble-plx`)을 위해 bare 프로젝트를 사용한다.

## 최초 셋업 — 네이티브 폴더 생성 (1회)

`android/`, `ios/` 폴더는 RN CLI 템플릿으로 생성한다. 저장소를 처음 받은 사람 중 **한 명**이 실행해서 커밋하면 된다.

```bash
# 1) 같은 RN 버전으로 임시 프로젝트 생성 (이름은 반드시 ItdaMobile — app.json과 일치해야 함)
cd ..
npx @react-native-community/cli@20.2.0 init ItdaMobile --version 0.87.1 --skip-install --skip-git-init

# 2) 네이티브 폴더만 가져오고 임시 프로젝트 삭제
#    (Windows PowerShell 기준)
Move-Item ItdaMobile/android mobile/android
Move-Item ItdaMobile/ios mobile/ios
Remove-Item -Recurse -Force ItdaMobile

# 3) 의존성 설치 후 실행
cd mobile
npm install
npx react-native run-android
```

> 이미 android/ 폴더가 저장소에 있으면 이 과정은 건너뛰고 `npm install && npx react-native run-android`만 하면 된다.

## 개발 메모

- 에뮬레이터에서 백엔드 주소: `http://10.0.2.2:8080` (`src/shared/api/client.ts`)
- 접근성: 모든 터치 요소에 `accessibilityLabel` 필수, 상태 변화 텍스트에는 `accessibilityLiveRegion` (기능명세 3.2)
- BLE 도입 시: `npm i react-native-ble-plx` 후 `android/app/src/main/AndroidManifest.xml`에 BLUETOOTH_SCAN/CONNECT 권한 추가

## DDD 지향 구조

```
src/
├── app/           # 앱 루트, (추후) navigation
├── screens/       # 화면 단위 (조립만 담당)
├── domains/       # 도메인 모듈 — user/ 구조를 복제해 확장
│   └── user/
│       ├── model/ # 타입·도메인 로직
│       └── api/   # 해당 도메인 API 호출
└── shared/        # api 클라이언트, 유틸, 공용 컴포넌트
```

의존 방향: `screens → domains → shared`
