// Choose once, before playback. Rotating or resizing must not restart the film.
(() => {
  const video = document.querySelector('video[data-mobile-src]');
  if (!video || !window.matchMedia('(max-width: 700px), (pointer: coarse) and (max-width: 1100px)').matches) return;
  video.dataset.layout = 'portrait';
  video.width = 1080;
  video.height = 1920;
  video.poster = video.dataset.mobilePoster;
  video.src = video.dataset.mobileSrc;
  // preload="none" and native controls remain in charge of fetching/playing.
})();
