# 윈도우에서 mobile 네이티브 빌드가 `Filename longer than 260 characters`로 죽음

## 증상

`front/apps/mobile`의 Android 빌드(`gradlew :app:assembleDebug`, Android Studio 동일)가 C++ 단계에서 실패한다.

```
Execution failed for task ':app:buildCMakeDebug[arm64-v8a]'.
ninja: error: Stat(rngesturehandler_codegen_autolinked_build/CMakeFiles/react_codegen_rngesturehandler_codegen.dir/
  C_/Users/.../front/node_modules/react-native-gesture-handler/shared/shadowNodes/.../RNGestureHandlerDetectorShadowNode.cpp.o):
  Filename longer than 260 characters
```

Kotlin·JS·Gradle 설정은 전부 정상이고, 로컬 모듈(`:itda-tactile:assembleDebug`)만 따로 빌드하면 성공한다.

## 원인

RN 새 아키텍처는 gesture-handler·reanimated·screens 등의 코드젠을 C++로 컴파일한다. CMake는 빌드 트리 밖 소스의
오브젝트 파일 이름에 **소스 절대경로를 통째로 넣는다**. 저장소 경로 + `node_modules/...` + 코드젠 경로가 합쳐져
오브젝트 경로가 **약 400자**가 되고, 윈도우 MAX_PATH(260)를 넘는다.

Android SDK가 함께 설치하는 `cmake/3.22.1/bin/ninja.exe`(1.10.2)는 실행 파일 매니페스트에 `longPathAware`가
없다. 그래서 **레지스트리 `LongPathsEnabled=1`을 켜도 이 프로세스에는 적용되지 않는다** — 긴 경로 지원은
매니페스트가 있는 프로세스에만 유효하다.

### 안 되는 것 (시도해 봤다)

| 시도 | 결과 |
|---|---|
| `HKLM\SYSTEM\CurrentControlSet\Control\FileSystem\LongPathsEnabled = 1` + 재부팅 | ninja가 매니페스트가 없어 그대로 실패 |
| `-DCMAKE_OBJECT_PATH_MAX=200` | Ninja 제너레이터가 무시한다 (Makefile 제너레이터 전용) |
| 저장소를 `C:\i\`로 옮기기, `subst`로 드라이브 단축, 빌드 폴더를 `C:\c`로 | 최선이 ~270자 — `..._autolinked_build/CMakeFiles/..._codegen.dir/` 고정 부분만 96자라 260 아래로 못 내려간다 |

## 해결

**`longPathAware` 매니페스트가 있는 ninja를 CMake에 물린다.** PyPI의 `ninja` 패키지(1.13.x)가 그렇다.

```bash
pip install ninja      # → <python>/Scripts/ninja.exe
```

매니페스트 확인: `grep -a -c longPathAware <경로>/ninja.exe` 가 1 이상이면 된다. (OS 쪽은 `LongPathsEnabled=1`이
켜져 있어야 하며, 켠 뒤 재부팅은 필요 없다 — 파이썬으로 444자 경로를 만들어 확인했다.)

Gradle에는 `externalNativeBuild.cmake.arguments "-DCMAKE_MAKE_PROGRAM=<ninja 경로>"`로 넘긴다.
생성물 `android/app/build.gradle`에 직접 적으면 `expo prebuild` 때 사라지므로 **config plugin**으로 넣는다:

- `front/apps/mobile/plugins/withCmakeNinja.js` — `defaultConfig`에 위 인자를 주입 (`app.json`의 `plugins`에 등록됨)
- 경로는 저장소에 두지 않고 각 개발자의 **`~/.gradle/gradle.properties`** 에서 읽는다:

```properties
ieum.ninja=C:/Users/<me>/miniconda3/Scripts/ninja.exe
```

이 속성이 없으면 plugin은 아무것도 하지 않아 macOS·리눅스 빌드에는 영향이 없다.

## 재발 방지

- plugin이 `app.json`에 등록돼 있어 `expo prebuild --clean`을 해도 우회가 유지된다.
- 윈도우 개발자 온보딩 체크리스트: ① `LongPathsEnabled=1` ② `pip install ninja` ③ `~/.gradle/gradle.properties`에 `ieum.ninja`.
- 같은 문제를 먼저 의심할 힌트: 오류 메시지에 `Stat(` 와 `260 characters` 가 함께 나오고, `.cxx/` 아래 `CMakeCache.txt`의
  `CMAKE_MAKE_PROGRAM`이 `Sdk/cmake/3.22.1/bin/ninja.exe`를 가리킨다.
