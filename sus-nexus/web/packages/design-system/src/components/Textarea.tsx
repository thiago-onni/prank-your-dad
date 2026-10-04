'use client';

import { forwardRef, type ReactNode, type TextareaHTMLAttributes } from 'react';
import { cn } from '../lib/cn';
import { Label } from './Label';
import { useFieldIds } from '../lib/id';

export interface TextareaProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'> {
  label: ReactNode;
  id?: string;
  description?: ReactNode;
  error?: ReactNode;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { label, id, description, error, required, className, ...props },
  ref,
) {
  const ids = useFieldIds('textarea');
  const inputId = id ?? ids.inputId;
  const describedBy =
    [description ? ids.descriptionId : null, error ? ids.errorId : null]
      .filter(Boolean)
      .join(' ') || undefined;
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={inputId} required={required}>
        {label}
      </Label>
      {description ? (
        <p id={ids.descriptionId} className="text-sm text-fg-muted">
          {description}
        </p>
      ) : null}
      <textarea
        ref={ref}
        id={inputId}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'min-h-24 w-full rounded-md border border-border-strong bg-surface px-3 py-2 text-base text-fg',
          'focus-visible:outline-4 focus-visible:outline-focus focus-visible:outline-offset-0',
          'aria-[invalid=true]:border-danger disabled:bg-bg-muted',
          className,
        )}
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
