// 담당 요소 검사 대상. 공용 엔진의 `docflow coverage` 가 project.json 의 coverageAdapter 로 읽습니다.
//
// 검사 대상
//   - app/src/main/java/dev/halcamera/ 아래의 모든 *Activity.kt
//   - AndroidManifest.xml 에 선언된 activity 중 파일이 존재하는 것
//   - app/src/main/java/dev/halcamera/ 바로 아래의 패키지 디렉터리 (하나의 대상으로 봅니다)
//
// 담당 여부 판정과 coverage.ignore 처리는 엔진이 맡습니다. 여기서는 대상 경로 → { kind, files } 맵만 만듭니다.
import fs from "node:fs";

const APP_ROOT = "app/src/main/java/dev/halcamera";
const MANIFEST = "app/src/main/AndroidManifest.xml";

function manifestActivities(sourcePath) {
  const file = sourcePath(MANIFEST);
  if (!fs.existsSync(file)) return [];
  const text = fs.readFileSync(file, "utf8");
  const found = [];
  for (const m of text.matchAll(/<activity\b[^>]*android:name="([^"]+)"/g)) {
    const name = m[1];
    // ".benchmark.Foo" 또는 "dev.halcamera.benchmark.Foo" 를 파일 경로로 바꿉니다.
    const relative = name.startsWith(".") ? name.slice(1) : name.startsWith("dev.halcamera.") ? name.slice("dev.halcamera.".length) : null;
    if (!relative) continue;
    const candidate = `${APP_ROOT}/${relative.replace(/\./g, "/")}.kt`;
    if (fs.existsSync(sourcePath(candidate))) found.push(candidate);
  }
  return found;
}

export function coverageTargets({ globFiles, sourcePath }) {
  const targets = new Map(); // key -> { kind, files }
  for (const file of new Set([...globFiles([`${APP_ROOT}/**/*Activity.kt`]), ...manifestActivities(sourcePath)])) {
    targets.set(file, { kind: "activity", files: [file] });
  }
  const packages = new Set(globFiles([`${APP_ROOT}/*/**/*.kt`]).map((f) => f.slice(APP_ROOT.length + 1).split("/")[0]));
  for (const pkg of [...packages].sort()) {
    const prefix = `${APP_ROOT}/${pkg}/`;
    targets.set(prefix, { kind: "package", files: globFiles([`${prefix}**/*.kt`]) });
  }
  return targets;
}
