import type { CareGap } from '@sus-nexus/api-client';
import { Badge, Button, Card, cn } from '@sus-nexus/design-system';
import { formatDate } from '../lib/format';
import { careGapKindLabels, careGapResolutionLabels, careLineLabel } from '../lib/labels';

export type { CareGap };

export interface CareGapCardProps {
  /** Lacuna do contrato (`CareGap` em `core-municipal.yaml`). */
  gap: CareGap;
  /** Nome legível do protocolo (o contrato traz apenas `protocol_id`). */
  protocolName?: string;
  /** Exibe o nome do cidadão no título (listas de busca ativa). */
  showCitizen?: boolean;
  suggestedAction?: string;
  onAct?: (gap: CareGap) => void;
  actionLabel?: string;
  /** Nível do título (padrão h3). */
  headingLevel?: 'h3' | 'h4';
  className?: string;
}

/** Lacuna de cuidado: linha, protocolo/versão, prazo, atraso, microárea e validade do contato. */
export function CareGapCard({
  gap,
  protocolName,
  showCitizen = false,
  suggestedAction,
  onAct,
  actionLabel = 'Registrar desfecho',
  headingLevel = 'h3',
  className,
}: CareGapCardProps) {
  const Heading = headingLevel;
  const kind = careGapKindLabels[gap.gap_kind];
  const overdue = (gap.days_overdue ?? 0) > 0;
  return (
    <Card
      as="article"
      className={cn('flex flex-col gap-2', className)}
      aria-labelledby={`gap-${gap.id}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Heading id={`gap-${gap.id}`} className="font-semibold text-fg">
          {kind}
          {showCitizen && gap.citizen_display_name ? ` — ${gap.citizen_display_name}` : ''}
        </Heading>
        {gap.status === 'resolved' ? (
          <Badge tone="success">Resolvida</Badge>
        ) : overdue ? (
          <Badge tone="danger">
            {gap.days_overdue} {gap.days_overdue === 1 ? 'dia' : 'dias'} em atraso
          </Badge>
        ) : (
          <Badge tone="warning">Pendente</Badge>
        )}
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">Linha de cuidado</dt>
        <dd>{careLineLabel(gap.care_line)}</dd>
        <dt className="text-fg-muted">Protocolo</dt>
        <dd>
          {protocolName ?? gap.protocol_id ?? '—'}{' '}
          <span className="text-fg-muted">v{gap.protocol_version}</span>
        </dd>
        <dt className="text-fg-muted">Prazo</dt>
        <dd>{formatDate(gap.expected_by)}</dd>
        {gap.microarea ? (
          <>
            <dt className="text-fg-muted">Microárea</dt>
            <dd>{gap.microarea}</dd>
          </>
        ) : null}
        {gap.contact_valid !== undefined ? (
          <>
            <dt className="text-fg-muted">Contato</dt>
            <dd>
              {gap.contact_valid ? (
                'Válido'
              ) : (
                <Badge tone="danger">Contato inválido — atualizar cadastro</Badge>
              )}
            </dd>
          </>
        ) : null}
        {gap.resolution ? (
          <>
            <dt className="text-fg-muted">Desfecho</dt>
            <dd>
              {careGapResolutionLabels[gap.resolution as keyof typeof careGapResolutionLabels] ??
                gap.resolution}
            </dd>
          </>
        ) : null}
        {suggestedAction ? (
          <>
            <dt className="text-fg-muted">Ação sugerida</dt>
            <dd>{suggestedAction}</dd>
          </>
        ) : null}
      </dl>
      {onAct && gap.status === 'open' ? (
        <div className="flex justify-end">
          <Button variant="secondary" size="sm" onClick={() => onAct(gap)}>
            {actionLabel}
          </Button>
        </div>
      ) : null}
    </Card>
  );
}
