#!/usr/bin/env node
// 파이프라인 자체 검사.
//
// 세 가지를 확인합니다.
//   1. 산문 보존: 생성기가 마커 바깥의 글을 한 글자도 바꾸지 않는가
//   2. 재현성:   같은 입력으로 다시 생성하면 결과가 같은가
//   3. 최신성:   코드만 바꾸고 .omm/ 과 원고를 그대로 두면 검사가 실패하는가
//
// 3번을 위해 소스 파일 하나를 잠시 수정했다가 바이트 단위로 되돌립니다.
// evidence.json 도 검사 전 상태로 되돌립니다. 검사가 끝나면 저장소는 시작할 때와 같습니다.
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { readBindings, repoPath, STATE_DIR } from "./lib.mjs";
import { pagePath } from "./model.mjs";

const here = import.meta.dirname;
const run = (script, ...args) => {
  const r = spawnSync(process.execPath, [path.join(here, script), ...args], { encoding: "utf8" });
  return { code: r.status, out: (r.stdout ?? "") + (r.stderr ?? "") };
};

const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok, detail });
  process.stdout.write(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  (${detail})` : ""}\n`);
};

const BLOCK = /<!-- omm:begin id=([a-z0-9-]+) -->[\s\S]*?<!-- omm:end id=\1 -->/g;
const stripBlocks = (text) => text.replace(/\r\n/g, "\n").replace(BLOCK, "<block>");

const bindings = readBindings();
const pages = Object.keys(bindings.pages).map((p) => pagePath(bindings, p));
const evidenceFile = path.join(STATE_DIR, "evidence.json");
const evidenceBackup = fs.existsSync(evidenceFile) ? fs.readFileSync(evidenceFile) : null;

// 최신성 검사에 쓸 소스 파일. data-flow 와 두 원고의 근거에 모두 포함되는 파일입니다.
const probeFile = repoPath("app", "src", "main", "java", "dev", "halcamera", "telemetry", "FlightRecorder.kt");
const probeOriginal = fs.readFileSync(probeFile);

try {
  run("extract.mjs");

  // 1. 산문 보존
  const before = pages.map((f) => fs.readFileSync(f, "utf8"));
  const gen1 = run("generate.mjs");
  check("생성기 실행", gen1.code === 0, gen1.code === 0 ? "" : gen1.out.trim().split("\n").pop());
  const after = pages.map((f) => fs.readFileSync(f, "utf8"));
  const prosePreserved = before.every((b, i) => stripBlocks(b) === stripBlocks(after[i]));
  check("산문 보존 (마커 바깥 불변)", prosePreserved);

  // 2. 재현성
  const gen2 = run("generate.mjs", "--check");
  check("재현성 (재생성 시 변경 없음)", gen2.code === 0, gen2.out.trim());

  // 3. 최신성: 검토 기록을 만든 뒤 코드만 바꿔 봅니다.
  run("verify.mjs", "--accept");
  const clean = run("verify.mjs", "--check");
  check("검토 직후 verify --check 통과", clean.code === 0);
  run("generate.mjs");
  const cleanGen = run("generate.mjs", "--check");
  check("검토 직후 generate --check 통과", cleanGen.code === 0);

  fs.writeFileSync(probeFile, Buffer.concat([probeOriginal, Buffer.from("\n// selftest probe\n")]));
  const stale = run("verify.mjs", "--check");
  const staleKeys = [...stale.out.matchAll(/^(\S+)\s+관련 소스 변경됨/gm)].map((m) => m[1]);
  check(
    "코드만 변경 시 verify --check 실패",
    stale.code === 1 && staleKeys.includes("omm:data-flow") && staleKeys.some((k) => k.startsWith("content:")),
    staleKeys.join(", "),
  );
  const staleGen = run("generate.mjs", "--check");
  check("코드만 변경 시 generate --check 실패 (상태 배지 불일치)", staleGen.code === 1);
} finally {
  // 되돌리기
  fs.writeFileSync(probeFile, probeOriginal);
  if (evidenceBackup) fs.writeFileSync(evidenceFile, evidenceBackup);
  else if (fs.existsSync(evidenceFile)) fs.unlinkSync(evidenceFile);
  run("verify.mjs");
  run("generate.mjs");
}

const failed = results.filter((r) => !r.ok);
process.stdout.write(`\n${results.length - failed.length}/${results.length} 통과. 저장소는 검사 전 상태로 되돌렸습니다.\n`);
process.exit(failed.length ? 1 : 0);
