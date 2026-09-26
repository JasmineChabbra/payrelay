import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In dev, the console talks to both backends through this proxy, the same way nginx does in Docker.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: { chunkSizeWarningLimit: 800 }, // recharts is large; fine for an internal console
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/sim': {
        target: 'http://localhost:8081',
        rewrite: (path) => path.replace(/^\/sim/, ''),
      },
    },
  },
})
