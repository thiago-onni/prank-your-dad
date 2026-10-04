'use client';

import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { Slot } from 'radix-ui';
import { cva, type VariantProps } from 'class-variance-authority';
import { Loader2 } from 'lucide-react';
import { cn } from '../lib/cn';

export const buttonVariants = cva(
  [
    'inline-flex items-center justify-center gap-2 rounded-full font-semibold whitespace-nowrap',
    'transition-colors select-none cursor-pointer',
    'disabled:cursor-not-allowed disabled:opacity-50',
    'focus-visible:outline-4 focus-visible:outline-focus focus-visible:outline-offset-2',
  ],
  {
    variants: {
      variant: {
        primary: 'bg-primary text-fg-on-primary hover:bg-primary-hover',
        secondary:
          'border border-primary bg-transparent text-primary hover:bg-primary-subtle dark:text-primary',
        tertiary: 'bg-transparent text-primary hover:bg-primary-subtle',
        danger: 'bg-danger text-white hover:bg-danger-hover dark:text-gray-90',
        ghost: 'bg-transparent text-fg hover:bg-bg-muted',
      },
      size: {
        sm: 'h-8 px-4 text-sm',
        md: 'h-10 px-6 text-base',
        lg: 'h-12 px-8 text-lg',
        icon: 'h-10 w-10 p-0',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
);

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> {
  /** Renderiza o filho como elemento raiz (ex.: `<Link>`). */
  asChild?: boolean;
  /** Mostra indicador de carregamento e desabilita o botão. */
  loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    className,
    variant,
    size,
    asChild = false,
    loading = false,
    disabled,
    children,
    type,
    ...props
  },
  ref,
) {
  const classes = cn(buttonVariants({ variant, size }), className);
  if (asChild) {
    // Slot exige exatamente um filho React; o indicador de loading não se aplica aqui.
    return (
      <Slot.Root ref={ref} className={classes} aria-disabled={disabled || undefined} {...props}>
        {children}
      </Slot.Root>
    );
  }
  return (
    <button
      ref={ref}
      type={type ?? 'button'}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...props}
    >
      {loading ? <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" /> : null}
      {children}
    </button>
  );
});
