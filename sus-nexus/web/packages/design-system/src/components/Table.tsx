'use client';

import {
  useRef,
  type HTMLAttributes,
  type ReactNode,
  type TdHTMLAttributes,
  type ThHTMLAttributes,
} from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { cn } from '../lib/cn';

export function Table({ className, ...props }: HTMLAttributes<HTMLTableElement>) {
  return (
    <div className="w-full overflow-x-auto rounded-lg border border-border">
      <table
        className={cn('w-full border-collapse text-left text-sm text-fg', className)}
        {...props}
      />
    </div>
  );
}

export function TableHead({ className, ...props }: HTMLAttributes<HTMLTableSectionElement>) {
  return (
    <thead
      className={cn('bg-bg-muted text-xs uppercase tracking-wide text-fg-muted', className)}
      {...props}
    />
  );
}

export function TableBody(props: HTMLAttributes<HTMLTableSectionElement>) {
  return <tbody {...props} />;
}

export function TableRow({ className, ...props }: HTMLAttributes<HTMLTableRowElement>) {
  return (
    <tr
      className={cn(
        'border-b border-border last:border-0 hover:bg-bg-muted focus-within:bg-primary-subtle',
        className,
      )}
      {...props}
    />
  );
}

export function TableHeaderCell({
  className,
  scope = 'col',
  ...props
}: ThHTMLAttributes<HTMLTableCellElement>) {
  return <th scope={scope} className={cn('px-3 py-2 font-semibold', className)} {...props} />;
}

export function TableCell({ className, ...props }: TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn('px-3 py-2 align-top', className)} {...props} />;
}

export interface VirtualColumn<T> {
  id: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  /** Largura CSS (ex.: `minmax(160px, 1fr)`). */
  width?: string;
  className?: string;
}

export interface VirtualizedTableProps<T> {
  rows: T[];
  columns: VirtualColumn<T>[];
  getRowId: (row: T) => string;
  /** Rótulo acessível da tabela. */
  'aria-label': string;
  rowHeight?: number;
  /** Altura máxima do viewport (px). */
  height?: number;
  onRowActivate?: (row: T) => void;
  emptyMessage?: ReactNode;
  className?: string;
}

/**
 * Tabela virtualizada (ARIA grid) para listas grandes — filas, mensagens, timelines.
 * Mantém semântica de tabela via roles ARIA e navegação por teclado por linha.
 */
export function VirtualizedTable<T>({
  rows,
  columns,
  getRowId,
  rowHeight = 48,
  height = 480,
  onRowActivate,
  emptyMessage = 'Nenhum registro',
  className,
  ...aria
}: VirtualizedTableProps<T>) {
  const parentRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => rowHeight,
    overscan: 8,
  });
  const gridTemplateColumns = columns.map((c) => c.width ?? 'minmax(120px, 1fr)').join(' ');

  return (
    <div className={cn('rounded-lg border border-border', className)}>
      <div
        role="table"
        aria-label={aria['aria-label']}
        aria-rowcount={rows.length + 1}
        className="text-sm text-fg"
      >
        <div role="rowgroup">
          <div
            role="row"
            aria-rowindex={1}
            className="grid border-b border-border bg-bg-muted text-xs font-semibold uppercase tracking-wide text-fg-muted"
            style={{ gridTemplateColumns }}
          >
            {columns.map((col) => (
              <div
                key={col.id}
                role="columnheader"
                className={cn('truncate px-3 py-2', col.className)}
              >
                {col.header}
              </div>
            ))}
          </div>
        </div>
        <div
          ref={parentRef}
          role="rowgroup"
          className="overflow-auto"
          style={{ maxHeight: height }}
        >
          {rows.length === 0 ? (
            <div role="row" aria-rowindex={2}>
              <div
                role="cell"
                aria-colspan={columns.length}
                className="p-6 text-center text-fg-muted"
              >
                {emptyMessage}
              </div>
            </div>
          ) : (
            <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
              {virtualizer.getVirtualItems().map((item) => {
                const row = rows[item.index];
                if (row === undefined) return null;
                const interactive = Boolean(onRowActivate);
                return (
                  <div
                    key={getRowId(row)}
                    role="row"
                    aria-rowindex={item.index + 2}
                    tabIndex={interactive ? 0 : undefined}
                    onClick={interactive ? () => onRowActivate?.(row) : undefined}
                    onKeyDown={
                      interactive
                        ? (e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              onRowActivate?.(row);
                            }
                          }
                        : undefined
                    }
                    className={cn(
                      'grid items-center border-b border-border',
                      interactive &&
                        'cursor-pointer hover:bg-bg-muted focus-visible:bg-primary-subtle focus-visible:-outline-offset-4',
                    )}
                    style={{
                      gridTemplateColumns,
                      position: 'absolute',
                      top: 0,
                      left: 0,
                      width: '100%',
                      height: item.size,
                      transform: `translateY(${item.start}px)`,
                    }}
                  >
                    {columns.map((col) => (
                      <div
                        key={col.id}
                        role="cell"
                        className={cn('truncate px-3 py-2', col.className)}
                      >
                        {col.cell(row)}
                      </div>
                    ))}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
