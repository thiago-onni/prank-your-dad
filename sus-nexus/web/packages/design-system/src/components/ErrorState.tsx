import type { ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';
import { cn } from '../lib/cn';
import { Button } from './Button';

export interface ErrorStateProps {
  title?: ReactNode;
  description?: ReactNode;
  /** Identificador de correlação para suporte (exibido ao usuário). */
  correlationId?: string;
  onRetry?: () => void;
  retryLabel?: string;
  className?: string;
}

export function ErrorState({
  title = 'Não foi possível carregar os dados',
  description,
  correlationId,
  onRetry,
  retryLabel = 'Tentar novamente',
  className,
}: ErrorStateProps) {
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-center justify-center gap-2 rounded-lg border border-danger bg-danger-subtle p-8 text-center',
        className,
      )}
    >
      <AlertTriangle aria-hidden="true" className="h-10 w-10 text-danger-fg-subtle" />
      <h3 className="text-lg font-semibold text-danger-fg-subtle">{title}</h3>
      {description ? <p className="max-w-prose text-sm text-fg">{description}</p> : null}
      {correlationId ? (
        <p className="font-mono text-xs text-fg-muted">
          Código de suporte: <span className="select-all">{correlationId}</span>
        </p>
      ) : null}
      {onRetry ? (
        <Button variant="secondary" size="sm" onClick={onRetry} className="mt-2">
          {retryLabel}
        </Button>
      ) : null}
    </div>
  );
}
