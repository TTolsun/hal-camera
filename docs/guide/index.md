---
title: Design Documentation
home: true
---
<section class="hero" aria-labelledby="hero-title">
  <p class="eyebrow">Design Documentation / HAL CAMERA</p>
  <h1 id="hero-title" lang="en">Engineering<br>the invisible.</h1>
  <p class="intro" lang="en">Camera systems are complicated.<br>The documentation doesn't have to be.</p>
  <p class="intro">카메라의 구조를 이해하고, 측정의 의미를 확인하고,<br>코드에 근거해 다음 변경을 결정합니다.</p>
  <div class="hero-bottom">
    <a href="{{ '/getting-started.html' | relative_url }}">처음이라면, 앱 실행부터 <span aria-hidden="true">↗</span></a>
    <span class="signature" lang="en">by K.H. Kim</span>
  </div>
</section>

<nav class="contents" aria-label="문서 목차">
  <a href="{{ '/architecture.html' | relative_url }}"><span class="number">01</span><span class="name">Architecture</span><span class="description">How the camera system is structured</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="{{ '/benchmark.html' | relative_url }}"><span class="number">02</span><span class="name">Benchmark</span><span class="description">How performance is measured</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="{{ '/decisions.html' | relative_url }}"><span class="number">03</span><span class="name">Decisions</span><span class="description">Why we built it this way</span><span class="arrow" aria-hidden="true">↗</span></a>
  <a href="{{ '/evidence.html' | relative_url }}"><span class="number">04</span><span class="name">Evidence</span><span class="description">Where the conclusions come from</span><span class="arrow" aria-hidden="true">↗</span></a>
</nav>

<section class="feature" aria-labelledby="architecture-title">
  <div class="feature-heading"><h2 id="architecture-title">A system you can follow.</h2><span class="eyebrow">01 / Architecture</span></div>
  <figure class="hero-figure">
    <svg viewBox="0 0 1080 390" role="img" aria-labelledby="flow-title flow-desc" xmlns="http://www.w3.org/2000/svg">
      <title id="flow-title">HALCamera의 카메라 구동과 관측 구조</title>
      <desc id="flow-desc">HALCamera는 CameraEngine으로 Camera2 또는 CameraX 엔진을 구동합니다. 프레임워크 콜백은 Telemetry와 FlightRecorder에 기록되어 Benchmark의 입력이 됩니다. HAL 내부 처리를 직접 측정하는 구조는 아닙니다.</desc>
      <defs><marker id="arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0 0L7 3.5L0 7" fill="none" stroke="#858b94"/></marker></defs>
      <g fill="none" stroke="#c8ccd2" stroke-width="1.2" marker-end="url(#arrow)"><path d="M240 110H365"/><path d="M635 110H755"/><path d="M885 154V248H635"/><path d="M365 282H245"/></g>
      <g fill="#fff" stroke="#e2e4e8"><rect x="20" y="66" width="220" height="88" rx="3"/><rect x="755" y="66" width="300" height="88" rx="3"/><rect x="365" y="238" width="270" height="88" rx="3"/><rect x="20" y="238" width="225" height="88" rx="3"/></g>
      <rect x="365" y="66" width="270" height="88" rx="3" fill="#f5f9ff" stroke="#0066cc"/>
      <g font-family="system-ui,sans-serif" fill="#18191b" font-size="20" text-anchor="middle"><text x="130" y="105">HALCamera</text><text x="500" y="105">CameraEngine</text><text x="905" y="105">Camera2 / CameraX</text><text x="500" y="277">Telemetry</text><text x="132" y="277">Measure &amp; compare</text></g>
      <g font-family="system-ui,sans-serif" fill="#62666d" font-size="12" text-anchor="middle"><text x="130" y="130">App &amp; runners</text><text x="500" y="130">One active engine</text><text x="905" y="130">Android camera framework</text><text x="500" y="303">FlightRecorder · callback events</text><text x="132" y="303">Benchmark</text><text x="750" y="231">Framework callbacks</text></g>
      <path d="M20 366H1055" stroke="#e2e4e8"/><text x="20" y="387" font-family="system-ui,sans-serif" fill="#62666d" font-size="11" letter-spacing="1.5">OBSERVATION AT THE APP LAYER</text>
    </svg>
    <figcaption>앱의 책임을 요약한 개념도입니다. 콜백 간격은 HAL 내부 처리 시간이나 프리뷰 표시 간격과 같지 않습니다. 구체적인 호출 관계와 코드 근거는 아키텍처 문서에서 확인하세요.</figcaption>
  </figure>
  <a href="{{ '/architecture.html' | relative_url }}">Explore the architecture <span aria-hidden="true">→</span></a>
</section>

<p class="editorial" lang="en">Good measurements should explain<br>the system, not just produce numbers.</p>

<section class="closing" aria-label="문서 작성 원칙">
  <p class="statement" lang="en">Built from the codebase.<br>Designed to stay useful.</p>
  <div><p>구현, 설계 의도, 기기 검증을 구분합니다.<br>기록되지 않은 이유는 확인이 필요한 상태로 남깁니다.</p><a href="{{ '/evidence.html' | relative_url }}">문서의 근거를 확인하세요 <span aria-hidden="true">→</span></a></div>
</section>
