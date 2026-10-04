import type { HTMLAttributes } from 'react';
import { cn } from '../lib/cn';

export interface SkeletonProps extends HTMLAttributes<HTMLDivElement> {
  /** Texto para leitores de tela (padrão: "Carregando"). */
  label?: string;
}

export function Skeleton({ className, label = 'Carregando', ...props }: SkeletonProps) {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-busy="true"
      className={cn('animate-pulse rounded-md bg-gray-10 dark:bg-blue-warm-vivid-80', className)}
      {...props}
    >
      <span className="sr-only">{label}</span>
    </div>
  );
}

export function SkeletonList({ rows = 5, className }: { rows?: number; className?: string }) {
  return (
    <div
      className={cn('flex flex-col gap-2', className)}
      role="status"
      aria-live="polite"
      aria-busy="true"
    >
      <span className="sr-only">Carregando lista</span>
      {Array.from({ length: rows }, (_, i) => (
        <div
          key={i}
          aria-hidden="true"
          className="h-12 animate-pulse rounded-md bg-gray-10 dark:bg-blue-warm-vivid-80"
        />
      ))}
    </div>
  );
}
