'use client';

import { useEffect } from 'react';
import { ErrorState } from '@sus-nexus/design-system';
import { t } from '@/i18n';

export default function ErrorPage({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Sem PII: apenas o digest para correlação com logs do servidor.
    console.error('[shell] erro de renderização', error.digest);
  }, [error]);
  return (
    <ErrorState
      title={t.app.unexpectedError}
      correlationId={error.digest}
      onRetry={reset}
      retryLabel={t.app.retry}
    />
  );
}
