import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';

createRoot(document.getElementById('root')!).render(<App />);

// Artwork that fails to load (flaky CDN, network blip): retry once, then
// fade to the placeholder instead of showing a broken-image icon.
document.addEventListener('error', (e) => {
  const img = e.target as HTMLImageElement;
  if (!(img instanceof HTMLImageElement)) return;
  if (!img.dataset.retried && img.src) {
    img.dataset.retried = '1';
    const src = img.src;
    setTimeout(() => { img.src = ''; img.src = src; }, 1200);
  } else {
    img.classList.add('broken');
  }
}, true);
document.addEventListener('load', (e) => {
  const img = e.target as HTMLImageElement;
  if (img instanceof HTMLImageElement) img.classList.remove('broken');
}, true);
