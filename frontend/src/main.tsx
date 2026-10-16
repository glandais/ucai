import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { setWorkerUrl } from 'maplibre-gl';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import 'maplibre-gl/dist/maplibre-gl.css';
import './styles.css';
import { App } from './App';

// MapLibre 6 loads its (standalone, module) worker from a file next to its own module, which neither Vite's
// dependency pre-bundling (dev) nor the production bundle keeps: point it at the file Vite serves/emits.
setWorkerUrl(maplibreWorkerUrl);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
