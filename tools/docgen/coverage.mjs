#!/usr/bin/env node
// 담당 요소 검사.
//
// 검증기(verify.mjs)는 "기존 절이 낡았는가"만 봅니다. 이 스크립트는 반대 질문에 답합니다.
//   새 화면이나 패키지가 생겼는데, 그것을 담당하는 요소나 원고가 없는가?
//
// 검사 대상
//   - app/src/main/java/dev/halcamera/ 아래의 모든 *Activity.kt
//   - AndroidManifest.xml 에 선언된 activity 중 파일이 존재하는 것
//   - app/src/main/java/dev/halcamera/ 바로 아래의 패키지 디렉터리 (하나의 대상으로 봅니다)
//
// "담당이 있다"의 정의
//   - _bindings.yaml 의 sources.*.elements.*.evidence 에 명시된 글롭에 매치되거나
//   - _bindings.yaml 의 coverage.ignore 에 사유와 함께 적혀 있으면 담당이 있는 것으로 봅니다.
//   관점 전체 evidence(**/*.kt 같은 넓은 글롭)는 세지 않습니다. 그것까지 세면 무엇이든 담당이 있게 됩니다.
//   원고의 sources: 인용도 세지 않습니다. 인용은 문단 하나의 근거이지 요소의 소유가 아니며, #75·#76 이
//   바로 그렇게 문단 하나로 통과한 사례입니다.
//
// 사용법
//   node coverage.mjs           표를 출력합니다.
//   node coverage.mjs --check   누락이 하나라도 있으면 종료 코드 1 (CI 용)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { readBindings, globFiles, repoPath, fail } from "./lib.mjs";

const APP_ROOT = "app/src/main/java/dev/halcamera";
const MANIFEST = "app/src/main/AndroidManifest.xml";

const args = process.argv.slice(2);
const check = args.includes("--check");

// --- 담당 목록 -------------------------------------------------------------------
export function coveredFiles(bindings) {
  const globs = [];
  for (const source of Object.values(bindings.sources ?? {})) {
    if (source.kind !== "omm") continue;
    for (const element of Object.values(source.elements ?? {})) {
      if (Array.isArray(element?.evidence)) globs.push(...element.evidence);
    }
  }
  return new Set(globFiles(globs));
}

export function ignoredTargets(bindings) {
  const list = bindings.coverage?.ignore ?? [];
  if (!Array.isArray(list)) throw new Error("coverage.ignore 는 목록이어야 합니다.");
  const map = new Map();
  for (const entry of list) {
    if (!entry?.path || !entry?.reason) throw new Error(`coverage.ignore 항목에는 path 와 reason 이 모두 필요합니다: ${JSON.stringify(entry)}`);
    map.set(String(entry.path), String(entry.reason));
  }
  return map;
}

// --- 검사 대상 -------------------------------------------------------------------
function manifestActivities() {
  const file = repoPath(...MANIFEST.split("/"));
  if (!fs.existsSync(file)) return [];
  const text = fs.readFileSync(file, "utf8");
  const found = [];
  for (const m of text.matchAll(/<activity\b[^>]*android:name="([^"]+)"/g)) {
    const name = m[1];
    // ".benchmark.Foo" 또는 "dev.halcamera.benchmark.Foo" 를 파일 경로로 바꿉니다.
    const relative = name.startsWith(".") ? name.slice(1) : name.startsWith("dev.halcamera.") ? name.slice("dev.halcamera.".length) : null;
    if (!relative) continue;
    const candidate = `${APP_ROOT}/${relative.replace(/\./g, "/")}.kt`;
    if (fs.existsSync(repoPath(...candidate.split("/")))) found.push(candidate);
  }
  return found;
}

export function coverageTargets() {
  const targets = new Map(); // key -> { kind, files }
  for (const file of new Set([...globFiles([`${APP_ROOT}/**/*Activity.kt`]), ...manifestActivities()])) {
    targets.set(file, { kind: "activity", files: [file] });
  }
  const packages = new Set(globFiles([`${APP_ROOT}/*/**/*.kt`]).map((f) => f.slice(APP_ROOT.length + 1).split("/")[0]));
  for (const pkg of [...packages].sort()) {
    const prefix = `${APP_ROOT}/${pkg}/`;
    targets.set(prefix, { kind: "package", files: globFiles([`${prefix}**/*.kt`]) });
  }
  return targets;
}

// 패키지는 파일 하나라도 담당이 있으면 담당이 있는 것으로 봅니다. 화면은 그 파일 자체가 있어야 합니다.
export function coverageReport(bindings) {
  const covered = coveredFiles(bindings);
  const ignored = ignoredTargets(bindings);
  const rows = [];
  const targets = coverageTargets();
  for (const [key, target] of targets) {
    if (ignored.has(key)) { rows.push({ key, kind: target.kind, state: "ignored", note: ignored.get(key) }); continue; }
    const hit = target.files.filter((f) => covered.has(f));
    if (hit.length) rows.push({ key, kind: target.kind, state: "covered", note: target.kind === "package" ? `${hit.length}/${target.files.length} 파일` : "" });
    else rows.push({ key, kind: target.kind, state: "missing", note: "담당 요소 없음" });
  }
  for (const key of ignored.keys()) {
    if (!targets.has(key)) rows.push({ key, kind: "ignore", state: "unused", note: "coverage.ignore 에 있지만 검사 대상이 아닙니다" });
  }
  return rows;
}

const LABEL = { covered: "담당 있음", ignored: "제외", missing: "누락", unused: "불필요한 제외" };

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  let bindings;
  try { bindings = readBindings(); } catch (error) { fail(error.message); }
  let rows;
  try { rows = coverageReport(bindings); } catch (error) { fail(error.message); }
  const width = Math.max(...rows.map((r) => r.key.length));
  for (const r of rows) process.stdout.write(`${r.key.padEnd(width)}  ${LABEL[r.state]}${r.note ? `  (${r.note})` : ""}\n`);
  const problems = rows.filter((r) => r.state === "missing" || r.state === "unused");
  if (check && problems.length) {
    process.stderr.write(`\n담당 요소 검사 실패: ${problems.length}개 항목. 새 화면·패키지는 guide/_bindings.yaml 의 elements evidence 에 넣고, 문서화하지 않을 것은 coverage.ignore 에 사유와 함께 적으세요.\n`);
    process.exit(1);
  }
}
