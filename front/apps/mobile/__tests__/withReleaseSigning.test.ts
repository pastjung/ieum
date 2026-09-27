// 플러그인은 prebuild(Node)에서 도는 CommonJS다. 순수 변환만 불러 검증한다
const { applyReleaseSigning } = require("../plugins/withReleaseSigning") as {
  applyReleaseSigning: (contents: string) => string;
};

/** expo prebuild(SDK 57)가 만드는 android/app/build.gradle의 해당 부분 그대로 */
const TEMPLATE = `apply plugin: "com.android.application"

def jscFlavor = 'io.github.react-native-community:jsc-android:2026004.+'

android {
    namespace 'com.ieum.mobile'
    defaultConfig {
        applicationId 'com.ieum.mobile'
        versionCode 1
        versionName "0.1.0"
    }
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }
    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
        release {
            // Caution! In production, you need to generate your own keystore file.
            // see https://reactnative.dev/docs/signed-apk-android.
            signingConfig signingConfigs.debug
            def enableShrinkResources = findProperty('android.enableShrinkResourcesInReleaseBuilds') ?: 'false'
            shrinkResources enableShrinkResources.toBoolean()
        }
    }
}
`;

function buildTypeBlock(contents: string, name: "debug" | "release"): string {
  const buildTypes = contents.slice(contents.indexOf("    buildTypes {"));
  const start = buildTypes.indexOf(`        ${name} {`);
  return buildTypes.slice(start, buildTypes.indexOf("\n        }", start));
}

describe("applyReleaseSigning", () => {
  it("release buildType만 release 서명으로 바꾸고 debug는 그대로 둔다", () => {
    const result = applyReleaseSigning(TEMPLATE);

    expect(buildTypeBlock(result, "release")).toContain("signingConfig signingConfigs.release");
    expect(buildTypeBlock(result, "release")).not.toContain("signingConfigs.debug");
    expect(buildTypeBlock(result, "debug")).toContain("signingConfig signingConfigs.debug");
  });

  it("signingConfigs에 gradle 속성 네 개를 읽는 release 설정을 넣는다", () => {
    const result = applyReleaseSigning(TEMPLATE);
    const signing = result.slice(result.indexOf("    signingConfigs {"), result.indexOf("    buildTypes {"));

    expect(signing).toContain("        release {");
    expect(signing).toContain("storeFile file(findProperty('IEUM_UPLOAD_STORE_FILE'))");
    expect(signing).toContain("storePassword findProperty('IEUM_UPLOAD_STORE_PASSWORD')");
    expect(signing).toContain("keyAlias findProperty('IEUM_UPLOAD_KEY_ALIAS')");
    expect(signing).toContain("keyPassword findProperty('IEUM_UPLOAD_KEY_PASSWORD')");
    // 디버그 키 설정은 남아 있어야 개발 빌드가 된다
    expect(signing).toContain("storeFile file('debug.keystore')");
  });

  it("android 블록 앞에서 release 작업을 요청했는데 속성이 없으면 빌드를 멈춘다", () => {
    const result = applyReleaseSigning(TEMPLATE);
    const prelude = result.slice(0, result.indexOf("\nandroid {"));

    expect(prelude).toContain("gradle.startParameter.taskNames.any { it.toLowerCase().contains('release') }");
    expect(prelude).toContain("throw new GradleException(");
    for (const key of ["IEUM_UPLOAD_STORE_FILE", "IEUM_UPLOAD_KEY_ALIAS", "IEUM_UPLOAD_STORE_PASSWORD", "IEUM_UPLOAD_KEY_PASSWORD"]) {
      expect(prelude).toContain(`'${key}'`);
    }
  });

  it("두 번 적용해도 한 번 적용한 것과 같다 (prebuild 반복)", () => {
    const once = applyReleaseSigning(TEMPLATE);
    expect(applyReleaseSigning(once)).toBe(once);
  });

  it("템플릿이 바뀌어 기준 위치를 못 찾으면 조용히 넘어가지 않고 실패한다", () => {
    expect(() => applyReleaseSigning(TEMPLATE.replace("    signingConfigs {", "    signing {"))).toThrow(
      "withReleaseSigning",
    );
    expect(() =>
      applyReleaseSigning(TEMPLATE.replace(/(\n {8}release \{[^}]*?)signingConfig signingConfigs\.debug/, "$1")),
    ).toThrow("withReleaseSigning");
    expect(() => applyReleaseSigning(TEMPLATE.replace("\nandroid {", "\nandroidx {"))).toThrow("withReleaseSigning");
  });
});
