import fs from 'node:fs';
export function collectFacts({globFiles, sourcePath: repoPath}) {
const fail = message => { throw new Error(message); };
const readIfExists = (rel) => (fs.existsSync(repoPath(rel)) ? fs.readFileSync(repoPath(rel), "utf8") : null);

// `key = value` 또는 `key = "value"` 형태의 대입을 모두 모읍니다.
// 같은 키가 여러 번 나오면 첫 번째 값을 씁니다. Gradle DSL 에서 defaultConfig 의
// 값이 먼저 나오므로 그것이 우리가 원하는 값입니다.
function collectAssignments(text) {
  const map = new Map();
  // `kotlinOptions { jvmTarget = "17" }` 처럼 한 줄 블록 안의 대입도 잡기 위해
  // 줄 시작뿐 아니라 `{` 나 `;` 뒤의 대입도 허용합니다.
  const re = /(?:^|[{;])\s*([A-Za-z][A-Za-z0-9_.]*)\s*=\s*("([^"\r\n]*)"|[^\s"{}][^\r\n{}]*?)\s*(?=\}|$)/gm;
  for (const m of text.matchAll(re)) {
    const key = m[1];
    const value = m[3] !== undefined ? m[3] : m[2];
    if (!map.has(key)) map.set(key, value);
  }
  return map;
}

function extractBuildFacts() {
  const gradle = readIfExists("app/build.gradle.kts");
  if (!gradle) fail("app/build.gradle.kts 를 찾지 못했습니다.");
  const assigned = collectAssignments(gradle);
  const pick = (key) => assigned.get(key) ?? null;

  const wrapper = readIfExists("gradle/wrapper/gradle-wrapper.properties") ?? "";
  const gradleVersion = wrapper.match(/gradle-([\d.]+)-(?:bin|all)\.zip/)?.[1] ?? null;
  const jvmTarget = gradle.match(/JvmTarget\.JVM_(\d+)/)?.[1] ?? pick("jvmTarget");

  return {
    applicationId: pick("applicationId"),
    namespace: pick("namespace"),
    versionName: pick("versionName"),
    versionCode: pick("versionCode"),
    minSdk: pick("minSdk"),
    targetSdk: pick("targetSdk"),
    compileSdk: pick("compileSdk"),
    gradleVersion,
    jvmTarget,
  };
}

function extractTelemetryFacts() {
  const files = globFiles(["app/src/main/java/**/*.kt"]);
  const tags = new Map();
  const add = (tag, rel) => {
    if (!tags.has(tag)) tags.set(tag, new Set());
    tags.get(tag).add(rel);
  };
  for (const rel of files) {
    const text = fs.readFileSync(repoPath(rel), "utf8");
    // 선언된 로그 태그 상수
    for (const m of text.matchAll(/const val TAG\s*=\s*"([^"]+)"/g)) add(m[1], rel);
    // 태그 문자열을 직접 넘기는 호출
    for (const m of text.matchAll(/\bLog\.[dviwe]\(\s*"([^"]+)"/g)) add(m[1], rel);
  }
  return {
    tags: [...tags.entries()]
      .map(([tag, sources]) => ({ tag, sources: [...sources].sort() }))
      .sort((a, b) => a.tag.localeCompare(b.tag)),
  };
}

return {"build-facts": extractBuildFacts(), "telemetry-facts": extractTelemetryFacts()};
}
