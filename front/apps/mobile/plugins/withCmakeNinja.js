const { withAppBuildGradle } = require("expo/config-plugins");

/**
 * 윈도우 빌드 우회 — CMake가 쓸 ninja를 바꿔 끼운다.
 *
 * Android SDK에 딸려 오는 ninja 1.10.2는 매니페스트에 longPathAware가 없어, 레지스트리로 긴 경로를
 * 켜도 260자를 넘는 오브젝트 파일을 못 연다. RN 새 아키텍처의 코드젠 오브젝트 경로는 400자에 이르러
 * `ninja: error: ... Filename longer than 260 characters` 로 빌드가 죽는다. (docs/solutions 참고)
 *
 * 각 개발자가 `~/.gradle/gradle.properties` 에 longPathAware ninja(예: `pip install ninja`) 경로를
 *   ieum.ninja=C:/Users/<me>/miniconda3/Scripts/ninja.exe
 * 처럼 두면 그걸 CMAKE_MAKE_PROGRAM 으로 넘긴다. 없으면 아무것도 하지 않아 macOS·리눅스에는 영향이 없다.
 *
 * 생성물(android/)을 직접 고치면 prebuild 때 날아가므로 plugin 으로 둔다.
 */
const PROPERTY = "ieum.ninja";

const SNIPPET = `
        externalNativeBuild {
            cmake {
                // 윈도우 260자 경로 우회 — ~/.gradle/gradle.properties 의 ${PROPERTY} (plugins/withCmakeNinja.js)
                def ieumNinja = findProperty('${PROPERTY}')
                if (ieumNinja) arguments "-DCMAKE_MAKE_PROGRAM=\${ieumNinja}"
            }
        }`;

module.exports = function withCmakeNinja(config) {
  return withAppBuildGradle(config, (mod) => {
    if (mod.modResults.language !== "groovy") {
      throw new Error("withCmakeNinja: app/build.gradle 이 Groovy 가 아닙니다.");
    }

    const contents = mod.modResults.contents;
    if (contents.includes(PROPERTY)) return mod; // 이미 들어가 있다

    const anchor = "    defaultConfig {";
    if (!contents.includes(anchor)) {
      throw new Error("withCmakeNinja: app/build.gradle 에서 defaultConfig 블록을 찾지 못했습니다.");
    }
    mod.modResults.contents = contents.replace(anchor, anchor + SNIPPET);
    return mod;
  });
};
