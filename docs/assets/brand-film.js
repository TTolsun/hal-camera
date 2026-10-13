// Prefer sound; a browser may allow only muted autoplay on the first visit.
const video = document.querySelector('.brand-film video');
if (video) {
  try {
    await video.play();
  } catch (error) {
    if (error.name === 'NotAllowedError') {
      video.muted = true;
      try {
        await video.play();
      } catch {
        // Native controls remain available if all autoplay is blocked.
      }
    }
  }
}
