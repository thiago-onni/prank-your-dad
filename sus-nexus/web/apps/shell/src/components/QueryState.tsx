'use client';

import type { ReactNode } from 'react';
import { ErrorState, SkeletonList } from '@sus-nexus/design-system';
import { describeError } from '@/lib/problem';

export interface QueryStateProps<T> {
  isLoading: boolean;
  error: unknown;
  data: T | undefined;
  onRetry?: () => void;
  skeletonRows?: number;
  children: (data: T) => ReactNode;
}

/** Estados explícitos: carregando / erro (com código de suporte) / dados. */
export function QueryState<T>({
  isLoading,
  error,
  data,
  onRetry,
  skeletonRows = 5,
  children,
}: QueryStateProps<T>) {
  if (isLoading && data === undefined) return <SkeletonList rows={skeletonRows} />;
  if (error) {
    const { message, correlationId } = describeError(error);
    return <ErrorState description={message} correlationId={correlationId} onRetry={onRetry} />;
  }
  if (data === undefined) return null;
  return <>{children(data)}</>;
}
