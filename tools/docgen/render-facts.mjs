export function renderFact(block, {facts, code, evidenceLine, CONFIDENCE_LABEL}) {
  const data = facts[block.source];
  if (!data) throw new Error(`facts.json 에 '${block.source}' 가 없습니다. extract.mjs 를 먼저 실행하세요.`);
  const lines = [];
  if (block.source === "build-facts") {
    lines.push("| 항목 | 값 |", "| --- | --- |");
    for (const [k, v] of Object.entries(data)) lines.push(`| ${code(k)} | ${v === null ? "확인 필요" : code(v)} |`);
    lines.push("", evidenceLine([`근거: ${code("app/build.gradle.kts")}, ${code("gradle/wrapper/gradle-wrapper.properties")}`, `근거 수준: ${CONFIDENCE_LABEL[block.confidence]}`]));
  } else if (block.source === "telemetry-facts") {
    lines.push("| 로그 태그 | 선언 위치 |", "| --- | --- |");
    for (const t of data.tags) lines.push(`| ${code(t.tag)} | ${t.sources.map(code).join("<br>")} |`);
    lines.push("", evidenceLine([`근거: ${code("app/src/main/java/**/*.kt")} 의 ${code("TAG")} 상수와 ${code("Log.*")} 호출`, `근거 수준: ${CONFIDENCE_LABEL[block.confidence]}`]));
  } else {
    throw new Error(`fact 원본 '${block.source}' 의 렌더링 방법이 정의되지 않았습니다.`);
  }
  return lines.join("\n");
}

// 최신성 표 앞에 붙는 프로젝트별 설명. 검토가 어느 앱 버전을 기준으로 이루어졌는지 남깁니다.
export function renderStatus({facts}) {
  const build = facts["build-facts"] ?? {};
  const version = build.versionName ? `${build.versionName} (versionCode ${build.versionCode ?? "?"})` : "확인 필요";
  return `- 검증 기준 앱 버전: ${version}`;
}
