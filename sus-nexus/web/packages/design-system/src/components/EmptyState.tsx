import type { ReactNode } from 'react';
import { Inbox } from 'lucide-react';
import { cn } from '../lib/cn';

export interface EmptyStateProps {
  title: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({ title, description, icon, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-border p-8 text-center',
        className,
      )}
    >
      <div className="text-fg-subtle" aria-hidden="true">
        {icon ?? <Inbox className="h-10 w-10" />}
      </div>
      <h3 className="text-lg font-semibold text-fg">{title}</h3>
      {description ? <p className="max-w-prose text-sm text-fg-muted">{description}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  );
}
