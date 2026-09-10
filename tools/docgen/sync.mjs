#!/usr/bin/env node
// omm 동기화 workflow.
//
// 코드가 바뀌어 낡아진 .omm/ perspective 와 원고를 LLM 으로 다시 만들고, 결과를
// 검증한 뒤 문서에 반영합니다. 이 스크립트가 파이프라인에서 LLM 을 호출하는
// 유일한 곳입니다.
//
//   1. 추출기와 검증기를 돌려 무엇이 낡았는지 정합니다. 낡은 것이 없으면 끝냅니다.
//   2. .omm/ 을 state/omm-backup/<시각>/ 에 스냅샷합니다.
//   3. 낡은 perspective 마다 omm-scan 스킬로 재스캔합니다 (.omm/ 에만 쓰기 허용).
//   4. `omm validate` 로 확인합니다. 실패하면 스냅샷을 되돌리고 종료 코드 1 로 끝냅니다.
//   5. 낡은 원고마다 brief.mjs 로 프롬프트를 만들어 재집필합니다. 출력 형식이
//      맞지 않으면 기존 원고를 유지합니다.
//   6. 검증기와 생성기를 다시 돌립니다.
//
// 사람의 검토(`node verify.mjs --accept`)는 이 스크립트가 대신하지 않습니다.
//
// 사용법
//   node sync.mjs              낡은 항목만 동기화합니다.
//   node sync.mjs --dry-run    무엇을 어떻게 호출할지 출력만 합니다.
//   node sync.mjs --force      최신 항목까지 모두 다시 만듭니다.
//   node sync.mjs --scan-only  원고 재집필은 건너뜁니다.
//   node sync.mjs --write-only 재스캔은 건너뜁니다.
//
// 필요한 것: `claude` CLI (인증된 상태), `omm` CLI.
import fs from "node:fs";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { readBindings, readState, REPO_ROOT, STATE_DIR, OMM_DIR, globFiles, fail } from "./lib.mjs";
import { collectKeys, contentPath, splitFrontMatter, computeHashes, stateOf } from "./model.mjs";

const args = new Set(process.argv.slice(2));
const dryRun = args.has("--dry-run");
const force = args.has("--force");
const scanOnly = args.has("--scan-only");
const writeOnly = args.has("--write-only");
const here = import.meta.dirname;
const isWin = process.platform === "win32";

const log = (msg) => process.stdout.write(`${msg}\n`);
const runNode = (script, ...extra) => spawnSync(process.execPath, [path.join(here, script), ...extra], { encoding: "utf8" });

function git(...a) {
  try {
    return execFileSync("git", a, { cwd: REPO_ROOT, encoding: "utf8" }).trim();
  } catch {
    return "";
  }
}

// claude CLI 호출. 프롬프트는 인자 길이 제한을 피하기 위해 stdin 으로 넘깁니다.
function claude(prompt, { allowedTools, cwd = REPO_ROOT }) {
  const cli = "claude";
  const cliArgs = ["-p", "--output-format", "text", "--allowedTools", allowedTools, "--permission-mode", "acceptEdits"];
  if (dryRun) {
    log(`  [dry-run] ${cli} ${cliArgs.join(" ")}  (프롬프트 ${prompt.length}자)`);
    return { ok: true, out: "" };
  }
  const r = spawnSync(cli, cliArgs, { cwd, input: prompt, encoding: "utf8", shell: isWin, maxBuffer: 64 * 1024 * 1024 });
  if (r.status !== 0) log(`  claude 종료 코드 ${r.status}: ${(r.stderr ?? "").trim().split("\n").pop() ?? ""}`);
  return { ok: r.status === 0, out: r.stdout ?? "" };
}

// --- 1. 무엇이 낡았는가 ---------------------------------------------------------
log("1/6 추출기와 검증기 실행");
const ex = runNode("extract.mjs", ...(dryRun ? ["--dry-run"] : []));
if (ex.status !== 0) fail(`extract.mjs 실패\n${ex.stderr}`);
const vf = runNode("verify.mjs", ...(dryRun ? ["--dry-run"] : []));
if (vf.status !== 0) fail(`verify.mjs 실패\n${vf.stderr}`);

const bindings = readBindings();
const evidence = readState("evidence.json", { entries: {} }).entries;
const keys = collectKeys(bindings);
const needs = (key) => {
  const entry = keys.find((k) => k.key === key);
  const current = computeHashes(bindings, entry);
  return force || !current.exists || current.missingCited?.length || stateOf(current, evidence[key]?.accepted) !== "fresh";
};

const stalePerspectives = keys.filter((k) => k.kind === "omm" && needs(k.key));
let staleContents = keys.filter((k) => k.kind === "content" && needs(k.key));

if (!stalePerspectives.length && !staleContents.length) {
  log("동기화 불필요: 모든 원본이 최신입니다.");
  process.exit(0);
}
log(`  재스캔 대상 perspective: ${stalePerspectives.map((k) => k.source).join(", ") || "(없음)"}`);
log(`  재집필 대상 원고: ${staleContents.map((k) => `${k.page}/${k.block.id}`).join(", ") || "(없음)"}`);

// --- 2. 스냅샷 -----------------------------------------------------------------
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const backupRoot = path.join(STATE_DIR, "omm-backup");
const backupDir = path.join(backupRoot, stamp);
log(`2/6 .omm/ 스냅샷 -> ${path.relative(REPO_ROOT, backupDir)}`);
if (!dryRun) {
  fs.mkdirSync(backupRoot, { recursive: true });
  fs.cpSync(OMM_DIR, backupDir, { recursive: true });
  // 오래된 스냅샷은 3개만 남깁니다.
  const old = fs.readdirSync(backupRoot).sort().slice(0, -3);
  for (const d of old) fs.rmSync(path.join(backupRoot, d), { recursive: true, force: true });
}

function restoreSnapshot() {
  if (dryRun) return;
  fs.rmSync(OMM_DIR, { recursive: true, force: true });
  fs.cpSync(backupDir, OMM_DIR, { recursive: true });
  log("  스냅샷을 되돌렸습니다.");
}

// --- 3. 재스캔 -----------------------------------------------------------------
if (!writeOnly && stalePerspectives.length) {
  log("3/6 omm-scan 재스캔");
  for (const k of stalePerspectives) {
    const since = evidence[k.key]?.accepted?.commit;
    const changed = since ? git("diff", "--name-only", `${since}..HEAD`).split("\n").filter(Boolean) : [];
    const relevant = changed.length ? changed.filter((f) => globFiles(k.evidence).includes(f)) : [];
    const scope = relevant.length
      ? `마지막 검토 커밋 ${since} 이후 바뀐 근거 파일:\n${relevant.map((f) => `- ${f}`).join("\n")}`
      : "검토 기록이 없거나 변경 파일을 특정할 수 없으므로 이 perspective 의 근거 파일 전체를 다시 읽습니다.";
    const prompt = `oh-my-mermaid 의 omm-scan 스킬을 사용해 \`.omm/${k.source}\` perspective 하나만 갱신하세요.

규칙
- \`.omm/${k.source}/\` 와 그 하위 요소만 수정합니다. 다른 perspective 와 \`.omm/\` 바깥은 절대 쓰지 않습니다.
- 필드 언어는 \`omm config language\` 설정(Korean)을 따릅니다. 요소 ID 와 다이어그램 노드 ID 는 영어 kebab-case 를 유지합니다.
- 기존 설명 중 코드와 여전히 일치하는 문장은 유지하고, 코드와 다른 문장만 고칩니다.
- 코드로 확인되지 않은 내용은 description 이 아니라 concern 에 적습니다.
- 설계 의도나 결정 이유는 추정하지 않습니다.
- 패키지 경로는 현재 코드 기준입니다 (\`app/src/main/java/dev/halcamera/\`).

근거 파일 글롭: ${k.evidence.join(", ")}

${scope}

끝나면 \`omm validate ${k.source}\` 를 실행해 통과하는지 확인하세요.`;
    log(`  - ${k.source}`);
    const r = claude(prompt, { allowedTools: "Read,Glob,Grep,Bash(omm:*)" });
    if (!r.ok) {
      restoreSnapshot();
      fail(`재스캔 실패: ${k.source}`);
    }
  }

  // --- 4. 검증 ------------------------------------------------------------------
  log("4/6 omm validate");
  if (!dryRun) {
    const v = spawnSync(isWin ? "omm.cmd" : "omm", ["validate"], { cwd: REPO_ROOT, encoding: "utf8", shell: isWin });
    if (v.status !== 0) {
      log((v.stdout + v.stderr).trim());
      restoreSnapshot();
      fail("omm validate 실패. 스냅샷으로 되돌렸습니다.");
    }
    log("  통과");
  }
} else {
  log("3/6, 4/6 재스캔 건너뜀");
}

// Recompute after scanning: updated structures can invalidate previously fresh manuscripts.
if (!dryRun) staleContents = keys.filter((k) => k.kind === "content" && needs(k.key));

// --- 5. 재집필 -----------------------------------------------------------------
const stripFence = (text) => {
  const t = text.trim();
  const m = t.match(/^```(?:markdown|md)?\s*\n([\s\S]*?)\n```\s*$/);
  return m ? m[1] : t;
};

if (!scanOnly && staleContents.length) {
  log("5/6 원고 재집필");
  for (const k of staleContents) {
    const b = runNode("brief.mjs", k.page, k.block.id);
    if (b.status !== 0) fail(`brief.mjs 실패: ${k.page}/${k.block.id}\n${b.stderr}`);
    log(`  - ${k.page}/${k.block.id}`);
    const r = claude(b.stdout, { allowedTools: "Read,Glob,Grep" });
    if (dryRun) continue;
    if (!r.ok || !r.out.trim()) {
      log("    실패: 기존 원고를 유지합니다.");
      continue;
    }
    const text = stripFence(r.out);
    let meta;
    try {
      ({ meta } = splitFrontMatter(text));
    } catch (err) {
      log(`    출력 형식 오류(${err.message}): 기존 원고를 유지합니다.`);
      continue;
    }
    if (!Array.isArray(meta.based_on) || !meta.confidence || !text.startsWith("---")) {
      log("    front matter 에 based_on 또는 confidence 가 없습니다: 기존 원고를 유지합니다.");
      continue;
    }
    const file = contentPath(bindings, k.page, k.block.id);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text.endsWith("\n") ? text : text + "\n", "utf8");
    log("    갱신");
  }
} else {
  log("5/6 재집필 건너뜀");
}

// --- 6. 검증과 생성 ---------------------------------------------------------------
log("6/6 검증기와 생성기 재실행");
if (!dryRun) {
  const v2 = runNode("verify.mjs");
  process.stdout.write(v2.stdout);
  if (v2.status !== 0) fail("검증기 실패");
  const g = runNode("generate.mjs");
  process.stdout.write(g.stdout + g.stderr);
  if (g.status !== 0) fail("생성기 실패");
  log("\n동기화 완료. 변경 내용을 검토한 뒤 'node verify.mjs --accept' 로 검토를 기록하세요.");
} else {
  log("[dry-run] 실제 변경 없음");
}
