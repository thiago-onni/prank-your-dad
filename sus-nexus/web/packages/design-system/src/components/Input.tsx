'use client';

import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react';
import { cn } from '../lib/cn';
import { Label } from './Label';
import { useFieldIds } from '../lib/id';

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  /** Rótulo visível (obrigatório para acessibilidade). */
  label: ReactNode;
  id?: string;
  description?: ReactNode;
  error?: ReactNode;
  /** Esconde o rótulo visualmente mantendo-o para leitores de tela. */
  hideLabel?: boolean;
  containerClassName?: string;
}

export const inputClassName = cn(
  'h-10 w-full rounded-md border border-border-strong bg-surface px-3 text-base text-fg',
  'placeholder:text-fg-subtle',
  'focus-visible:outline-4 focus-visible:outline-focus focus-visible:outline-offset-0',
  'disabled:cursor-not-allowed disabled:bg-bg-muted disabled:opacity-70',
  'aria-[invalid=true]:border-danger',
);

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, id, description, error, hideLabel, required, className, containerClassName, ...props },
  ref,
) {
  const ids = useFieldIds('input');
  const inputId = id ?? ids.inputId;
  const describedBy =
    [description ? ids.descriptionId : null, error ? ids.errorId : null]
      .filter(Boolean)
      .join(' ') || undefined;

  return (
    <div className={cn('flex flex-col gap-1', containerClassName)}>
      <Label htmlFor={inputId} required={required} className={cn(hideLabel && 'sr-only')}>
        {label}
      </Label>
      {description ? (
        <p id={ids.descriptionId} className="text-sm text-fg-muted">
          {description}
        </p>
      ) : null}
      <input
        ref={ref}
        id={inputId}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(inputClassName, className)}
        {...props}
      />
      {error ? (
        <p id={ids.errorId} role="alert" className="text-sm font-medium text-danger-fg-subtle">
          {error}
        </p>
      ) : null}
    </div>
  );
});
