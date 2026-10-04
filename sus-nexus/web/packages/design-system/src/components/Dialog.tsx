'use client';

import { Dialog as RadixDialog } from 'radix-ui';
import { X } from 'lucide-react';
import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import { cn } from '../lib/cn';
import { Button } from './Button';

export const Dialog = RadixDialog.Root;
export const DialogTrigger = RadixDialog.Trigger;
export const DialogClose = RadixDialog.Close;

export interface DialogContentProps extends Omit<
  ComponentPropsWithoutRef<typeof RadixDialog.Content>,
  'title'
> {
  title: ReactNode;
  description?: ReactNode;
  footer?: ReactNode;
  size?: 'sm' | 'md' | 'lg';
}

/** Diálogo modal acessível: foco preso, Esc fecha, título/descrição associados. */
export function DialogContent({
  title,
  description,
  footer,
  size = 'md',
  className,
  children,
  ...props
}: DialogContentProps) {
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/50 data-[state=open]:animate-in" />
      <RadixDialog.Content
        className={cn(
          'fixed left-1/2 top-1/2 z-50 flex max-h-[90dvh] w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2 flex-col rounded-lg border border-border bg-surface-raised shadow-level-3 focus:outline-none',
          size === 'sm' && 'max-w-md',
          size === 'md' && 'max-w-xl',
          size === 'lg' && 'max-w-3xl',
          className,
        )}
        {...props}
      >
        <div className="flex items-start justify-between gap-4 border-b border-border p-4">
          <div>
            <RadixDialog.Title className="text-xl font-semibold text-fg">{title}</RadixDialog.Title>
            {description ? (
              <RadixDialog.Description className="mt-1 text-sm text-fg-muted">
                {description}
              </RadixDialog.Description>
            ) : null}
          </div>
          <RadixDialog.Close asChild>
            <Button variant="ghost" size="icon" aria-label="Fechar">
              <X aria-hidden="true" className="h-5 w-5" />
            </Button>
          </RadixDialog.Close>
        </div>
        <div className="overflow-y-auto p-4">{children}</div>
        {footer ? (
          <div className="flex justify-end gap-2 border-t border-border p-4">{footer}</div>
        ) : null}
      </RadixDialog.Content>
    </RadixDialog.Portal>
  );
}
