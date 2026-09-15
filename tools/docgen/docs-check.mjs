#!/usr/bin/env node
// 문서 검사 진입점. CI(.github/workflows/docs-check.yml)와 개발자 PC 가 같은 순서로 같은 검사를 실행합니다.
//
//   node tools/docgen/docs-check.mjs            로컬: 검사만 실행합니다. git 작업본 비교는 건너뜁니다.
//   node tools/docgen/docs-check.mjs --ci       CI:   마지막에 `git diff --exit-code` 로 상태 파일·원고·산출물이 커밋과 같은지도 봅니다.
//   node tools/docgen/docs-check.mjs --build    로컬: 검사 뒤 docs/ 사이트를 실제로 빌드합니다 (배포 준비).
//
// 요구 사항: Node.js 24 이상. 의존성은 이 스크립트가 `npm ci --prefix tools/docgen --ignore-scripts` 로 설치합니다.
//
// 종료 코드
//   0  모든 단계 통과
//   1  검사 실패. 실패한 단계 이름을 마지막 줄에 표시하며 뒤 단계는 실행하지 않습니다.
//   2  실행 환경 문제 (Node 버전, 알 수 없는 인자)
//
// 단계 (실행 순서대로)
//   1. 파이프라인 회귀 검사     node --test regression.test.mjs design.test.mjs   (의존성 없음)
//   2. 의존성 설치              npm ci --prefix tools/docgen --ignore-scripts
//   3. 동기화 복구·사이트 빌더 검사  node --test sync.test.mjs workflow.test.mjs site.test.mjs   (모델 호출 없음, marked 필요)
//   4. 디자인 생성 결과 일치      common.mjs design --check
//   5. 사실 추출                extract.mjs   (state/facts.json 을 다시 씁니다)
//   6. 원본 최신성 검사          verify.mjs --check
//   7. 생성 결과 일치 검사        generate.mjs --check
//   8. 사이트 산출물 일치 검사     site.mjs check   (docs/ 가 guide/ 에서 빌드한 결과와 같은지, 내부 링크가 유효한지)
//   9. (--ci) 상태 파일 일치 검사  git diff --exit-code -- tools/docgen/state guide docs
//   9. (--build) 사이트 빌드      site.mjs build
import path from "node:path";
import { spawnSync } from "node:child_process";

const REQUIRED_NODE_MAJOR = 24;
const root = path.resolve(import.meta.dirname, "..", "..");
const args = process.argv.slice(2);
const known = new Set(["--ci", "--build"]);
for (const arg of args) {
  if (!known.has(arg)) { console.error(`알 수 없는 인자: ${arg} (--ci | --build)`); process.exit(2); }
}
const ci = args.includes("--ci");
const buildSite = args.includes("--build");
if (ci && buildSite) { console.error("--ci 와 --build 는 함께 쓸 수 없습니다. CI 는 커밋된 산출물을 검사만 합니다."); process.exit(2); }

const major = Number(process.versions.node.split(".")[0]);
if (major < REQUIRED_NODE_MAJOR) {
  console.error(`Node.js ${REQUIRED_NODE_MAJOR} 이상이 필요합니다. 현재: ${process.version}`);
  process.exit(2);
}

const node = process.execPath;
const npm = process.platform === "win32" ? "npm.cmd" : "npm";
const docgen = (file) => path.join("tools", "docgen", file);
const steps = [
  { name: "파이프라인 회귀 검사", cmd: node, args: ["--test", docgen("regression.test.mjs"), docgen("design.test.mjs")] },
  { name: "의존성 설치", cmd: npm, args: ["ci", "--prefix", "tools/docgen", "--ignore-scripts"], shell: process.platform === "win32" },
  { name: "동기화 실패 복구·사이트 빌더 검사 (모델 호출 없음)", cmd: node, args: ["--test", docgen("sync.test.mjs"), docgen("workflow.test.mjs"), docgen("site.test.mjs")] },
  { name: "디자인 생성 결과 일치 검사", cmd: node, args: [docgen("common.mjs"), "design", "--check"] },
  { name: "사실 추출", cmd: node, args: [docgen("extract.mjs")] },
  { name: "원본 최신성 검사", cmd: node, args: [docgen("verify.mjs"), "--check"] },
  { name: "생성 결과 일치 검사", cmd: node, args: [docgen("generate.mjs"), "--check"] },
  { name: "사이트 산출물 일치 검사", cmd: node, args: [docgen("site.mjs"), "check"] },
];
if (ci) steps.push({ name: "상태 파일 일치 검사", cmd: "git", args: ["diff", "--exit-code", "--", "tools/docgen/state", "guide", "docs"] });
if (buildSite) {
  // 로컬 배포 준비: 검사가 모두 통과한 뒤에만 산출물을 씁니다.
  steps.splice(steps.length - 1, 1, { name: "사이트 빌드", cmd: node, args: [docgen("site.mjs"), "build"] });
}

for (const [index, step] of steps.entries()) {
  console.log(`\n[${index + 1}/${steps.length}] ${step.name}`);
  const result = spawnSync(step.cmd, step.args, { cwd: root, stdio: "inherit", shell: step.shell ?? false, windowsHide: true });
  if (result.error) { console.error(result.error.message); }
  if (result.status !== 0) {
    console.error(`\n실패: ${step.name} (종료 코드 ${result.status ?? "없음"}). 뒤 단계는 실행하지 않았습니다.`);
    process.exit(1);
  }
}
console.log(`\n문서 검사 통과 (${steps.length}단계).${buildSite ? " docs/ 산출물을 커밋하세요." : ""}`);
