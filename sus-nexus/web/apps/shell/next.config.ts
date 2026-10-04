import type { NextConfig } from 'next';

const securityHeaders = [
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  {
    key: 'Permissions-Policy',
    value: 'camera=(), microphone=(), geolocation=(), browsing-topics=()',
  },
  { key: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
  { key: 'Cross-Origin-Resource-Policy', value: 'same-origin' },
  { key: 'X-DNS-Prefetch-Control', value: 'off' },
  { key: 'Strict-Transport-Security', value: 'max-age=63072000; includeSubDomains; preload' },
];

const nextConfig: NextConfig = {
  output: 'standalone',
  // O lint roda no monorepo (`pnpm lint`, ESLint 9 flat config); evita execução duplicada no build.
  eslint: { ignoreDuringBuilds: true },
  reactStrictMode: true,
  poweredByHeader: false,
  transpilePackages: [
    '@sus-nexus/design-system',
    '@sus-nexus/domain-components',
    '@sus-nexus/api-client',
    '@sus-nexus/auth',
  ],
  // MSW (modo mock) usa interceptadores Node que não devem ser empacotados.
  serverExternalPackages: ['msw', '@mswjs/interceptors'],
  outputFileTracingRoot: new URL('../../', import.meta.url).pathname,
  headers() {
    // A CSP com nonce é definida no middleware; aqui ficam os cabeçalhos estáticos.
    return Promise.resolve([{ source: '/:path*', headers: securityHeaders }]);
  },
};

export default nextConfig;
