---
title: HAL Validation Guide
home: true
---
<section class="hero" aria-labelledby="hero-title">
  <p class="eyebrow">HAL CAMERA / User Guide</p>
  <h1 id="hero-title" lang="en">Engineering<br>the invisible.</h1>
  <p class="intro" lang="en">Camera systems are complicated.<br>The documentation doesn't have to be.</p>
  <p class="intro">촬영 조건을 맞추고, HAL 동작을 관찰하고,<br>측정 결과와 재현 자료를 확인합니다.</p>
  <div class="hero-bottom">
    <a href="getting-started.html">처음이라면, 앱 실행부터 <span aria-hidden="true">↗</span></a>
    <span class="signature" lang="en">by K.H. Kim</span>
  </div>
</section>

<nav class="contents task-contents" aria-label="검증 작업별 문서">
  <a href="getting-started.html"><span class="name">Quickstart</span><span class="description">APK를 설치하고 첫 화면을 확인합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="live.html"><span class="name">Live</span><span class="description">사진·녹화와 촬영 조건을 확인합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="probe.html"><span class="name">Probe</span><span class="description">카메라가 보고한 지원 사양을 확인합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="cts.html"><span class="name">CTS</span><span class="description">테스트를 실행하고 판정을 읽습니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="benchmark.html"><span class="name">Benchmark</span><span class="description">반복 측정하고 결과를 비교합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="callback.html"><span class="name">Callback</span><span class="description">프레임별 콜백과 시각을 확인합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="automation.html"><span class="name">Automation</span><span class="description">PC 명령이나 에이전트로 반복 실행합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="troubleshooting.html"><span class="name">Troubleshooting</span><span class="description">증상을 좁히고 재현 자료를 모읍니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
</nav>

<section class="feature" aria-labelledby="scope-title">
  <div class="feature-heading"><h2 id="scope-title">측정값은 어디에서 왔나요?</h2></div>
  <p>HALCamera는 앱이 받은 콜백과 이미지, 결과 메타데이터를 관찰합니다. 콜백 간격만으로 HAL 내부 처리 시간이나 프레임 드롭을 확정할 수는 없습니다.</p>
  <p>결과를 비교할 때에는 엔진·카메라·출력 조건과 시각 기준을 먼저 맞추세요. 차이가 남으면 앱 기록과 시스템 트레이스를 함께 확인하세요.</p>
  <a href="engine.html">엔진별 관측 차이 확인 <span aria-hidden="true">→</span></a>
</section>

<p class="editorial" lang="en">Good measurements should explain<br>the system, not just produce numbers.</p>

<section class="closing" aria-label="문서 작성 원칙">
  <p class="statement" lang="en">Built from the codebase.<br>Designed to stay useful.</p>
  <div><p>구현, 설계 의도, 기기 검증을 구분합니다.<br>기록되지 않은 이유는 확인이 필요한 상태로 남깁니다.</p><a href="evidence.html">문서의 근거를 확인하세요 <span aria-hidden="true">→</span></a></div>
</section>
