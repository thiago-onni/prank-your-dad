import { Badge, Button, Card, cn } from '@sus-nexus/design-system';
import { formatDate } from '../lib/format';

export interface CareGap {
  id: string;
  citizen_id: string;
  /** Linha de cuidado (ex.: hipertensão, pré-natal). */
  care_line: string;
  /** Descrição da lacuna (ex.: "HbA1c vencida há 90 dias"). */
  description: string;
  protocol: string;
  protocol_version: string;
  due_at?: string;
  overdue?: boolean;
  suggested_action?: string;
}

export interface CareGapCardProps {
  gap: CareGap;
  onAct?: (gap: CareGap) => void;
  actionLabel?: string;
  className?: string;
}

/** Lacuna de cuidado: protocolo/versão, prazo e ação sugerida. */
export function CareGapCard({
  gap,
  onAct,
  actionLabel = 'Criar tarefa',
  className,
}: CareGapCardProps) {
  return (
    <Card
      as="article"
      className={cn('flex flex-col gap-2', className)}
      aria-labelledby={`gap-${gap.id}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={`gap-${gap.id}`} className="font-semibold text-fg">
          {gap.description}
        </h3>
        {gap.overdue ? (
          <Badge tone="danger">Em atraso</Badge>
        ) : (
          <Badge tone="warning">Pendente</Badge>
        )}
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">Linha de cuidado</dt>
        <dd>{gap.care_line}</dd>
        <dt className="text-fg-muted">Protocolo</dt>
        <dd>
          {gap.protocol} <span className="text-fg-muted">v{gap.protocol_version}</span>
        </dd>
        <dt className="text-fg-muted">Prazo</dt>
        <dd>{formatDate(gap.due_at)}</dd>
        {gap.suggested_action ? (
          <>
            <dt className="text-fg-muted">Ação sugerida</dt>
            <dd>{gap.suggested_action}</dd>
          </>
        ) : null}
      </dl>
      {onAct ? (
        <div className="flex justify-end">
          <Button variant="secondary" size="sm" onClick={() => onAct(gap)}>
            {actionLabel}
          </Button>
        </div>
      ) : null}
    </Card>
  );
}
