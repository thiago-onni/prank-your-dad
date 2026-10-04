'use client';

import { Select as RadixSelect } from 'radix-ui';
import { Check, ChevronDown } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '../lib/cn';
import { Label } from './Label';
import { useFieldIds } from '../lib/id';

export interface SelectOption {
  value: string;
  label: ReactNode;
  disabled?: boolean;
}

export interface SelectProps {
  label: ReactNode;
  options: SelectOption[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  placeholder?: string;
  name?: string;
  id?: string;
  disabled?: boolean;
  required?: boolean;
  error?: ReactNode;
  description?: ReactNode;
  hideLabel?: boolean;
  className?: string;
}

/** Select acessível (Radix) com rótulo visível e mensagens de erro associadas. */
export function Select({
  label,
  options,
  value,
  defaultValue,
  onValueChange,
  placeholder = 'Selecione…',
  name,
  id,
  disabled,
  required,
  error,
  description,
  hideLabel,
  className,
}: SelectProps) {
  const ids = useFieldIds('select');
  const triggerId = id ?? ids.inputId;
  const describedBy =
    [description ? ids.descriptionId : null, error ? ids.errorId : null]
      .filter(Boolean)
      .join(' ') || undefined;

  return (
    <div className={cn('flex flex-col gap-1', className)}>
      <Label htmlFor={triggerId} required={required} className={cn(hideLabel && 'sr-only')}>
        {label}
      </Label>
      {description ? (
        <p id={ids.descriptionId} className="text-sm text-fg-muted">
          {description}
        </p>
      ) : null}
      <RadixSelect.Root
        value={value}
        defaultValue={defaultValue}
        onValueChange={onValueChange}
        name={name}
        disabled={disabled}
        required={required}
      >
        <RadixSelect.Trigger
          id={triggerId}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={cn(
            'flex h-10 w-full items-center justify-between gap-2 rounded-md border border-border-strong bg-surface px-3 text-base text-fg',
            'focus-visible:outline-4 focus-visible:outline-focus focus-visible:outline-offset-0',
            'data-[placeholder]:text-fg-subtle disabled:cursor-not-allowed disabled:bg-bg-muted',
            'aria-[invalid=true]:border-danger',
          )}
        >
          <RadixSelect.Value placeholder={placeholder} />
          <RadixSelect.Icon>
            <ChevronDown aria-hidden="true" className="h-4 w-4" />
          </RadixSelect.Icon>
        </RadixSelect.Trigger>
        <RadixSelect.Portal>
          <RadixSelect.Content
            position="popper"
            sideOffset={4}
            className="z-50 min-w-[var(--radix-select-trigger-width)] overflow-hidden rounded-md border border-border bg-surface-raised shadow-level-2"
          >
            <RadixSelect.Viewport className="p-1">
              {options.map((opt) => (
                <RadixSelect.Item
                  key={opt.value}
                  value={opt.value}
                  disabled={opt.disabled}
                  className={cn(
                    'relative flex cursor-pointer select-none items-center rounded-sm py-2 pl-8 pr-3 text-base text-fg outline-none',
                    'data-[highlighted]:bg-primary-subtle data-[highlighted]:text-primary-fg-subtle',
                    'data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
                  )}
                >
                  <RadixSelect.ItemIndicator className="absolute left-2 inline-flex items-center">
                    <Check aria-hidden="true" className="h-4 w-4" />
                  </RadixSelect.ItemIndicator>
                  <RadixSelect.ItemText>{opt.label}</RadixSelect.ItemText>
                </RadixSelect.Item>
              ))}
            </RadixSelect.Viewport>
          </RadixSelect.Content>
        </RadixSelect.Portal>
      </RadixSelect.Root>
      {error ? (
        <p id={ids.errorId} role="alert" className="text-sm font-medium text-danger-fg-subtle">
          {error}
        </p>
      ) : null}
    </div>
  );
}
