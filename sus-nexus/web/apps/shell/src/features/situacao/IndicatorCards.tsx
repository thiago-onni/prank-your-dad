'use client';

import {
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
  EyeOff,
  LineChart,
  MinusCircle,
} from 'lucide-react';
import type { SituationIndicator, SituationIndicatorCode } from '@sus-nexus/api-client';
import { Badge, Button, Card, cn } from '@sus-nexus/design-system';
import { t } from '@/i18n';
import { formatIndicatorValue, formatTarget, trafficLight, type TrafficLevel } from './format';

const ICONS: Record<TrafficLevel, typeof CheckCircle2> = {
  atingida: CheckCircle2,
  atencao: AlertTriangle,
  critica: AlertOctagon,
  suprimido: EyeOff,
  sem_dado: MinusCircle,
};

export function TrafficBadge({ indicator }: { indicator: Parameters<typeof trafficLight>[0] }) {
  const light = trafficLight(indicator);
  const Icon = ICONS[light.level];
  return (
    <Badge tone={light.tone} data-level={light.level} title={light.description}>
      <Icon aria-hidden="true" className="h-3 w-3" />
      {light.label}
    </Badge>
  );
}

const BORDER: Record<TrafficLevel, string> = {
  atingida: 'border-l-success',
  atencao: 'border-l-warning',
  critica: 'border-l-danger',
  suprimido: 'border-l-border-strong',
  sem_dado: 'border-l-border-strong',
};

export function IndicatorCard({
  indicator,
  selected,
  onSelect,
}: {
  indicator: SituationIndicator;
  selected: boolean;
  onSelect: (code: SituationIndicatorCode) => void;
}) {
  const light = trafficLight(indicator);
  const headingId = `ind-${indicator.code}`;
  return (
    <Card
      as="article"
      aria-labelledby={headingId}
      data-indicator={indicator.code}
      data-level={light.level}
      className={cn('flex flex-col gap-2 border-l-4', BORDER[light.level])}
    >
      <h3 id={headingId} className="text-base font-semibold leading-snug text-fg">
        {indicator.name}
      </h3>
      <p className="text-2xl font-bold tabular-nums text-fg" data-testid="indicator-value">
        {formatIndicatorValue(indicator.value, indicator.unit, indicator.is_suppressed)}
      </p>
      <div>
        <TrafficBadge indicator={indicator} />
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-2 text-sm">
        <dt className="text-fg-muted">{t.situation.target}:</dt>
        <dd className="tabular-nums">
          {formatTarget(indicator.target, indicator.unit, indicator.direction)}
        </dd>
        <dt className="text-fg-muted">{t.situation.base}:</dt>
        <dd className="tabular-nums">
          {indicator.is_suppressed
            ? t.situation.suppressed
            : indicator.denominator === null
              ? t.situation.noData
              : indicator.denominator.toLocaleString('pt-BR')}
        </dd>
      </dl>
      <Button
        size="sm"
        variant={selected ? 'primary' : 'secondary'}
        aria-pressed={selected}
        onClick={() => onSelect(indicator.code)}
        className="mt-auto self-start"
      >
        <LineChart aria-hidden="true" className="h-4 w-4" />
        <span>
          Série histórica<span className="sr-only">: {indicator.name}</span>
        </span>
      </Button>
    </Card>
  );
}

export function LightsSummary({ items }: { items: SituationIndicator[] }) {
  const counts: Record<TrafficLevel, number> = {
    atingida: 0,
    atencao: 0,
    critica: 0,
    suprimido: 0,
    sem_dado: 0,
  };
  for (const i of items) counts[trafficLight(i).level] += 1;
  const order: [TrafficLevel, string, 'success' | 'warning' | 'danger' | 'neutral'][] = [
    ['atingida', t.situation.lightOk, 'success'],
    ['atencao', t.situation.lightWarning, 'warning'],
    ['critica', t.situation.lightCritical, 'danger'],
    ['suprimido', t.situation.lightSuppressed, 'neutral'],
    ['sem_dado', t.situation.lightNoData, 'neutral'],
  ];
  return (
    <ul aria-label={t.situation.lightsSummary} className="flex flex-wrap gap-2">
      {order.map(([level, label, tone]) => {
        const Icon = ICONS[level];
        return (
          <li key={level}>
            <Badge tone={tone} data-summary={level}>
              <Icon aria-hidden="true" className="h-3 w-3" />
              {label}: {counts[level]}
            </Badge>
          </li>
        );
      })}
    </ul>
  );
}
