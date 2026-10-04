'use client';

import { Label as RadixLabel } from 'radix-ui';
import type { ComponentPropsWithoutRef } from 'react';
import { cn } from '../lib/cn';

export interface LabelProps extends ComponentPropsWithoutRef<typeof RadixLabel.Root> {
  required?: boolean;
}

export function Label({ className, required, children, ...props }: LabelProps) {
  return (
    <RadixLabel.Root className={cn('block text-sm font-semibold text-fg', className)} {...props}>
      {children}
      {required ? (
        <span className="ml-1 text-danger" aria-hidden="true">
          *
        </span>
      ) : null}
      {required ? <span className="sr-only"> (obrigatório)</span> : null}
    </RadixLabel.Root>
  );
}
