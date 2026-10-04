'use client';

import { VisuallyHidden as RadixVisuallyHidden } from 'radix-ui';
import type { ComponentPropsWithoutRef } from 'react';

export type VisuallyHiddenProps = ComponentPropsWithoutRef<typeof RadixVisuallyHidden.Root>;

/** Conteúdo apenas para leitores de tela. */
export function VisuallyHidden(props: VisuallyHiddenProps) {
  return <RadixVisuallyHidden.Root {...props} />;
}
