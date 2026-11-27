import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const { INCIDENT_LINK_KEY } = loadEnv(mode, '.', 'INCIDENT_');

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api/incidents': {
          target: 'http://127.0.0.1:8080',
          headers: INCIDENT_LINK_KEY ? { 'X-Incident-Edge': INCIDENT_LINK_KEY } : {},
        },
      },
    },
  };
});
