import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '../lib/cn';
import { Button } from './Button';

export interface CursorPaginationProps {
  /** Cursor da próxima página (null/undefined = última página). */
  nextCursor?: string | null;
  hasPrevious?: boolean;
  onNext: (cursor: string) => void;
  onPrevious?: () => void;
  isLoading?: boolean;
  /** Descrição opcional (ex.: "Mostrando 50 itens"). */
  summary?: string;
  className?: string;
}

/** Paginação por cursor opaco (`?cursor=&limit=`), conforme convenção da API. */
export function CursorPagination({
  nextCursor,
  hasPrevious = false,
  onNext,
  onPrevious,
  isLoading,
  summary,
  className,
}: CursorPaginationProps) {
  return (
    <nav
      aria-label="Paginação"
      className={cn('flex items-center justify-between gap-4', className)}
    >
      <p className="text-sm text-fg-muted" aria-live="polite">
        {summary ?? ''}
      </p>
      <div className="flex items-center gap-2">
        <Button
          variant="secondary"
          size="sm"
          onClick={onPrevious}
          disabled={!hasPrevious || isLoading}
          aria-label="Página anterior"
        >
          <ChevronLeft aria-hidden="true" className="h-4 w-4" />
          Anterior
        </Button>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => nextCursor && onNext(nextCursor)}
          disabled={!nextCursor || isLoading}
          aria-label="Próxima página"
        >
          Próxima
          <ChevronRight aria-hidden="true" className="h-4 w-4" />
        </Button>
      </div>
    </nav>
  );
}
