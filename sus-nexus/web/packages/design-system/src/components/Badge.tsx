import type { HTMLAttributes } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../lib/cn';

export const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap',
  {
    variants: {
      tone: {
        neutral: 'border-border bg-bg-muted text-fg',
        primary: 'border-transparent bg-primary-subtle text-primary-fg-subtle',
        success: 'border-transparent bg-success-subtle text-success-fg-subtle',
        warning: 'border-transparent bg-warning-subtle text-warning-fg-subtle',
        danger: 'border-transparent bg-danger-subtle text-danger-fg-subtle',
        info: 'border-transparent bg-info-subtle text-primary-fg-subtle',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
);

export type BadgeTone = NonNullable<VariantProps<typeof badgeVariants>['tone']>;

export interface BadgeProps
  extends HTMLAttributes<HTMLSpanElement>, VariantProps<typeof badgeVariants> {}

export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}
