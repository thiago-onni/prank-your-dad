import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '../lib/cn';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  as?: 'div' | 'section' | 'article';
}

export function Card({ className, as: Comp = 'div', ...props }: CardProps) {
  return (
    <Comp
      className={cn('rounded-lg border border-border bg-surface p-4 shadow-level-1', className)}
      {...props}
    />
  );
}

export interface CardHeaderProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  headingLevel?: 2 | 3 | 4;
}

export function CardHeader({
  title,
  description,
  actions,
  headingLevel = 3,
  className,
  ...props
}: CardHeaderProps) {
  const Heading = `h${headingLevel}` as const;
  return (
    <div className={cn('mb-3 flex items-start justify-between gap-3', className)} {...props}>
      <div className="min-w-0">
        <Heading className="text-lg font-semibold leading-tight text-fg">{title}</Heading>
        {description ? <p className="mt-1 text-sm text-fg-muted">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function CardContent({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('text-base text-fg', className)} {...props} />;
}

export function CardFooter({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'mt-4 flex items-center justify-end gap-2 border-t border-border pt-3',
        className,
      )}
      {...props}
    />
  );
}
