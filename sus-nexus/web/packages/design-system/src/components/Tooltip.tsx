'use client';

import { Tooltip as RadixTooltip } from 'radix-ui';
import type { ReactNode } from 'react';
import { cn } from '../lib/cn';

export const TooltipProvider = RadixTooltip.Provider;

export interface TooltipProps {
  content: ReactNode;
  children: ReactNode;
  side?: 'top' | 'right' | 'bottom' | 'left';
  className?: string;
}

/**
 * Tooltip acessível. O gatilho deve ser um elemento focável (botão). Para texto crítico,
 * prefira também expor o conteúdo via `aria-label`/`aria-describedby` no gatilho.
 */
export function Tooltip({ content, children, side = 'top', className }: TooltipProps) {
  return (
    <RadixTooltip.Root delayDuration={200}>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          side={side}
          sideOffset={6}
          className={cn(
            'z-50 max-w-xs rounded-md bg-gray-90 px-3 py-2 text-sm text-white shadow-level-2 dark:bg-gray-5 dark:text-gray-90',
            className,
          )}
        >
          {content}
          <RadixTooltip.Arrow className="fill-gray-90 dark:fill-gray-5" />
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  );
}
