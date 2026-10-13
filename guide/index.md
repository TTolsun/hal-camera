---
title: HAL Validation Guide
home: true
---
<section class="hero" aria-labelledby="hero-title">
  <p class="eyebrow">HAL CAMERA / User Guide</p>
  <h1 id="hero-title" lang="en">Capture. Measure. Verify.</h1>
  <p class="intro">사진 한 장에서 시작해 카메라가 일하는 순서를 확인하세요.<br>처음이라면 Quickstart, 이미 실행했다면 아래에서 궁금한 작업을 고르세요.</p>
  <div class="hero-bottom">
    <a href="getting-started.html">처음이라면, 앱 실행부터 <span aria-hidden="true">↗</span></a>
    <span class="signature" lang="en">by K.H. Kim</span>
  </div>
</section>

<nav class="contents task-contents" aria-label="검증 작업별 문서">
  <a href="getting-started.html"><span class="name">Quickstart</span><span class="description">APK를 설치하고 첫 화면을 확인합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="live.html"><span class="name">Live</span><span class="description">사진·녹화와 촬영 조건을 확인합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="callback.html"><span class="name">Callback</span><span class="description">프레임별 콜백과 시각을 확인합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="troubleshooting.html"><span class="name">Troubleshooting</span><span class="description">증상을 좁히고 재현 자료를 모읍니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
</nav>

<details>
<summary>사양·테스트·자동화를 확인하려면</summary>
<nav class="contents task-contents" aria-label="사양·반복 검증 문서">
  <a href="probe.html"><span class="name">Probe</span><span class="description">카메라가 보고한 지원 사양을 확인합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="cts.html"><span class="name">CTS</span><span class="description">테스트를 실행하고 판정을 읽습니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="benchmark.html"><span class="name">Benchmark</span><span class="description">반복 측정하고 결과를 비교합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="automation.html"><span class="name">Automation</span><span class="description">PC 명령이나 에이전트로 반복 실행합니다.</span><span class="arrow" aria-hidden="true">↗</span></a>
</nav>
</details>

<figure class="brand-film" aria-labelledby="brand-film-title">
  <video controls autoplay muted playsinline preload="metadata" width="1920" height="1080" poster="assets/media/camera-hal-tactile.jpg" aria-labelledby="brand-film-title" aria-describedby="brand-film-description">
    <source src="assets/media/camera-hal-tactile.mp4" type="video/mp4">
    <a href="assets/media/camera-hal-tactile.mp4">Camera HAL 소개 영상 보기</a>
  </video>
  <figcaption>
    <h2 id="brand-film-title">Camera HAL.</h2>
    <p>카메라를 이해하는 또 하나의 시선.</p>
    <p id="brand-film-description">메트로놈의 움직임에서 물결과 바다로 이어지는 15초 소개 영상입니다. 마지막에는 “Camera HAL. Evolving with AI.”라는 문구가 나타납니다.</p>
    <p class="brand-film-hint">음소거로 한 번 재생된 뒤 마지막 장면에 멈춥니다. 영상에서 소리를 켜거나 다시 재생할 수 있습니다.</p>
  </figcaption>
</figure>

<section class="feature" aria-labelledby="try-title">
  <div class="feature-heading"><h2 id="try-title">직접 확인하면 더 잘 보입니다.</h2></div>
  <ul>
    <li><a href="live.html#동영상을-찍으세요">녹화하며 가까이, 다시 멀리.</a> 1배 → 2배 → 1배로 바꿔도 녹화가 이어지는지 확인합니다.</li>
    <li><a href="callback.html#촬영-프레임을-고정하세요">사진과 메타데이터의 도착 순서를 보세요.</a> 그래프를 3초 고정하면 두 점을 천천히 비교할 수 있습니다.</li>
    <li><a href="live.html#여러-카메라를-함께-촬영하세요">화면은 나눠 보고, 파일은 각각.</a> Multi에서 앞과 뒤를 찍고 배치를 바꿔 보세요. 파일에는 각 카메라의 전체 영상이 남습니다.</li>
  </ul>
</section>

<section class="feature" aria-labelledby="scope-title">
  <div class="feature-heading"><h2 id="scope-title">측정값은 어디에서 왔나요?</h2></div>
  <p>HALCamera는 앱이 받은 콜백과 이미지, 결과 메타데이터를 관찰합니다. 콜백 간격만으로 HAL 내부 처리 시간이나 프레임 드롭을 확정할 수는 없습니다.</p>
  <p>결과를 비교할 때에는 엔진·카메라·출력 조건과 시각 기준을 먼저 맞추세요. 차이가 남으면 앱 기록과 시스템 트레이스를 함께 확인하세요.</p>
  <a href="engine.html">엔진별 관측 차이 확인 <span aria-hidden="true">→</span></a>
</section>

<p>기기에서 확인한 조건과 한계는 <a href="evidence.html">Validation</a>에서 확인하세요.</p>
