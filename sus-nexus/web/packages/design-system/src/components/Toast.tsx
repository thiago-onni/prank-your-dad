'use client';

import { Toast as RadixToast } from 'radix-ui';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { CheckCircle2, Info, TriangleAlert, X, XCircle } from 'lucide-react';
import { cn } from '../lib/cn';

export type ToastTone = 'info' | 'success' | 'warning' | 'danger';

export interface ToastMessage {
  id?: string;
  title: ReactNode;
  description?: ReactNode;
  tone?: ToastTone;
  durationMs?: number;
}

interface ToastContextValue {
  toast: (message: ToastMessage) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

const toneStyles: Record<ToastTone, string> = {
  info: 'border-info bg-info-subtle text-primary-fg-subtle',
  success: 'border-success bg-success-subtle text-success-fg-subtle',
  warning: 'border-warning-strong bg-warning-subtle text-warning-fg-subtle',
  danger: 'border-danger bg-danger-subtle text-danger-fg-subtle',
};

const toneIcons: Record<ToastTone, typeof Info> = {
  info: Info,
  success: CheckCircle2,
  warning: TriangleAlert,
  danger: XCircle,
};

let counter = 0;

/** Provedor de notificações (Radix Toast). Envolva a aplicação uma única vez. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<(ToastMessage & { id: string })[]>([]);

  const toast = useCallback((message: ToastMessage) => {
    counter += 1;
    const id = message.id ?? `toast-${counter}`;
    setItems((prev) => [...prev.filter((t) => t.id !== id), { ...message, id }]);
  }, []);

  const remove = useCallback((id: string) => {
    setItems((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const value = useMemo(() => ({ toast }), [toast]);

  return (
    <ToastContext.Provider value={value}>
      <RadixToast.Provider swipeDirection="right" label="Notificações">
        {children}
        {items.map((item) => {
          const tone = item.tone ?? 'info';
          const Icon = toneIcons[tone];
          return (
            <RadixToast.Root
              key={item.id}
              duration={item.durationMs ?? (tone === 'danger' ? 10000 : 5000)}
              type={tone === 'danger' || tone === 'warning' ? 'foreground' : 'background'}
              onOpenChange={(open) => {
                if (!open) remove(item.id);
              }}
              className={cn(
                'flex items-start gap-3 rounded-md border-l-4 bg-surface-raised p-4 shadow-level-3',
                toneStyles[tone],
              )}
            >
              <Icon aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0" />
              <div className="flex-1">
                <RadixToast.Title className="font-semibold">{item.title}</RadixToast.Title>
                {item.description ? (
                  <RadixToast.Description className="mt-1 text-sm text-fg">
                    {item.description}
                  </RadixToast.Description>
                ) : null}
              </div>
              <RadixToast.Close
                aria-label="Fechar notificação"
                className="rounded-sm p-1 hover:bg-black/10"
              >
                <X aria-hidden="true" className="h-4 w-4" />
              </RadixToast.Close>
            </RadixToast.Root>
          );
        })}
        <RadixToast.Viewport className="fixed bottom-4 right-4 z-[100] flex w-[calc(100vw-2rem)] max-w-sm flex-col gap-2 outline-none" />
      </RadixToast.Provider>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error('useToast deve ser usado dentro de <ToastProvider>');
  }
  return ctx;
}
