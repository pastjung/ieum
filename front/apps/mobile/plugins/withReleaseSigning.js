const { withAppBuildGradle } = require("expo/config-plugins");

/**
 * 릴리스 APK를 우리 keystore로만 서명한다 (설계 2026-09-27-deploy-rebuild-design.md §5.2).
 *
 * expo prebuild가 만드는 app/build.gradle은 release도 디버그 키로 서명한다. 그 APK가 나가면
 * 나중에 진짜 키로 서명한 업데이트를 기존 설치본 위에 깔 수 없다. 그래서
 *   1) signingConfigs 에 release 를 넣어 ~/.gradle/gradle.properties 의 IEUM_UPLOAD_* 를 읽고
 *   2) release buildType 이 그것을 쓰게 하며
 *   3) 이름에 release 가 든 작업을 요청했는데 속성이 하나라도 없으면 설정 단계에서 멈춘다.
 * 디버그 빌드(expo run:android)는 속성 없이도 그대로 된다.
 *
 *   IEUM_UPLOAD_STORE_FILE=C:/Users/<me>/keys/ieum-upload.jks   (절대 경로 권장)
 *   IEUM_UPLOAD_KEY_ALIAS=ieum-upload
 *   IEUM_UPLOAD_STORE_PASSWORD=...
 *   IEUM_UPLOAD_KEY_PASSWORD=...
 *
 * keystore·비밀번호는 저장소에 두지 않는다. 생성물(android/)을 직접 고치면 prebuild 때 날아가므로 plugin 으로 둔다.
 */
const MARKER = "IEUM_UPLOAD_STORE_FILE";

const PRELUDE = `
// 릴리스 서명 — ~/.gradle/gradle.properties 의 IEUM_UPLOAD_* (plugins/withReleaseSigning.js)
def ieumSigningKeys = ['IEUM_UPLOAD_STORE_FILE', 'IEUM_UPLOAD_KEY_ALIAS', 'IEUM_UPLOAD_STORE_PASSWORD', 'IEUM_UPLOAD_KEY_PASSWORD']
def ieumSigningMissing = ieumSigningKeys.findAll { !findProperty(it) }
def ieumReleaseRequested = gradle.startParameter.taskNames.any { it.toLowerCase().contains('release') }
if (ieumReleaseRequested && !ieumSigningMissing.isEmpty()) {
    throw new GradleException("릴리스 서명 정보가 없습니다: \${ieumSigningMissing.join(', ')}. ~/.gradle/gradle.properties 에 넣어 주세요 (plugins/withReleaseSigning.js)")
}
`;

const RELEASE_SIGNING = `        release {
            if (ieumSigningMissing.isEmpty()) {
                storeFile file(findProperty('IEUM_UPLOAD_STORE_FILE'))
                storePassword findProperty('IEUM_UPLOAD_STORE_PASSWORD')
                keyAlias findProperty('IEUM_UPLOAD_KEY_ALIAS')
                keyPassword findProperty('IEUM_UPLOAD_KEY_PASSWORD')
            }
        }
`;

const ANDROID_ANCHOR = "\nandroid {";
const SIGNING_ANCHOR = "    signingConfigs {\n";
/** buildTypes 의 release 블록 안, 첫 '}' 전에 나오는 디버그 서명 줄 (주석에는 '}'가 없다) */
const RELEASE_DEBUG_SIGNING = /(\n {8}release \{[^}]*?)signingConfig signingConfigs\.debug/;

function fail(what) {
  throw new Error(`withReleaseSigning: app/build.gradle 에서 ${what} 을(를) 찾지 못했습니다. expo 템플릿이 바뀌었는지 확인하세요.`);
}

/** app/build.gradle 문자열 변환. 순수 함수라 jest 로 검증한다 (__tests__/withReleaseSigning.test.ts) */
function applyReleaseSigning(contents) {
  if (contents.includes(MARKER)) return contents; // 이미 들어가 있다

  if (!RELEASE_DEBUG_SIGNING.test(contents)) fail("release buildType 의 signingConfig");
  if (!contents.includes(SIGNING_ANCHOR)) fail("signingConfigs 블록");
  if (!contents.includes(ANDROID_ANCHOR)) fail("android 블록");

  return contents
    .replace(RELEASE_DEBUG_SIGNING, "$1signingConfig signingConfigs.release")
    .replace(SIGNING_ANCHOR, SIGNING_ANCHOR + RELEASE_SIGNING)
    .replace(ANDROID_ANCHOR, PRELUDE + ANDROID_ANCHOR);
}

function withReleaseSigning(config) {
  return withAppBuildGradle(config, (mod) => {
    if (mod.modResults.language !== "groovy") {
      throw new Error("withReleaseSigning: app/build.gradle 이 Groovy 가 아닙니다.");
    }
    mod.modResults.contents = applyReleaseSigning(mod.modResults.contents);
    return mod;
  });
}

module.exports = withReleaseSigning;
module.exports.applyReleaseSigning = applyReleaseSigning;
