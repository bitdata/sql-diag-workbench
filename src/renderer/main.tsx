import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './styles/globals.css'
import { initApiAuth } from './utils/api'

// A remote stylesheet in <head> blocks painting and deferred modules on
// refresh. Start after the local document loads: CDN delays must block neither
// workspace mounting nor the document's load event.
const fonts = document.createElement('link')
fonts.rel = 'stylesheet'
fonts.href = 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap'
fonts.dataset.status = 'loading'
fonts.onload = () => { fonts.dataset.status = 'ready' }
fonts.onerror = () => { fonts.dataset.status = 'unavailable' }
const loadFonts = () => { document.head.appendChild(fonts) }
if (document.readyState === 'complete') loadFonts()
else window.addEventListener('load', loadFonts, { once: true })

// Fetch the per-launch auth token before any component can hit the backend.
initApiAuth().finally(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  )
})
