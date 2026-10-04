'use client';

import { Tabs as RadixTabs } from 'radix-ui';
import type { ComponentPropsWithoutRef } from 'react';
import { cn } from '../lib/cn';

export const Tabs = RadixTabs.Root;

export function TabsList({ className, ...props }: ComponentPropsWithoutRef<typeof RadixTabs.List>) {
  return (
    <RadixTabs.List
      className={cn('flex flex-wrap gap-1 border-b border-border', className)}
      {...props}
    />
  );
}

export function TabsTrigger({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof RadixTabs.Trigger>) {
  return (
    <RadixTabs.Trigger
      className={cn(
        '-mb-px border-b-4 border-transparent px-4 py-2 text-base font-semibold text-fg-muted',
        'hover:bg-bg-muted hover:text-fg',
        'data-[state=active]:border-primary data-[state=active]:text-primary-fg-subtle',
        'focus-visible:outline-4 focus-visible:outline-focus focus-visible:-outline-offset-4',
        className,
      )}
      {...props}
    />
  );
}

export function TabsContent({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof RadixTabs.Content>) {
  return (
    <RadixTabs.Content className={cn('pt-4 focus-visible:outline-none', className)} {...props} />
  );
}
