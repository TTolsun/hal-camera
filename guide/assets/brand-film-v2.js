// Leave the poster and native controls available when motion is reduced.
const video = document.querySelector('.brand-film video');
if (video) {
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let cancelled = motion.matches;
  motion.addEventListener('change', () => {
    if (motion.matches) {
      cancelled = true;
      video.pause();
    }
  });
  if (!cancelled) {
    try {
      await video.play();
    } catch (error) {
      if (error.name === 'NotAllowedError' && !cancelled) {
        video.muted = true;
        try {
          await video.play();
        } catch {
          // Native controls remain available if all autoplay is blocked.
        }
      }
    }
  }
}
