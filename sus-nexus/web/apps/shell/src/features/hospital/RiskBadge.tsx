'use client';

import type { HospitalRiskLevel } from '@sus-nexus/api-client';
import { Badge, Tooltip } from '@sus-nexus/design-system';
import { hospitalRiskLabels } from '@sus-nexus/domain-components';
import { format, t } from '@/i18n';

/**
 * Risco calculado pelo core (regra versionada). A versão da regra fica no tooltip e no nome
 * acessível do gatilho — a UI nunca recalcula o risco.
 */
export function RiskBadge({
  level,
  ruleVersion,
}: {
  level: HospitalRiskLevel | undefined;
  ruleVersion: string | undefined;
}) {
  if (!level) return <span className="text-sm text-fg-muted">{t.hospital.riskNotComputed}</span>;
  const meta = hospitalRiskLabels[level];
  if (!ruleVersion) return <Badge tone={meta.tone}>{meta.label}</Badge>;
  const tip = format(t.hospital.riskRule, { version: ruleVersion });
  return (
    <Tooltip content={tip}>
      <button
        type="button"
        aria-label={`${meta.label}. ${tip}`}
        data-risk={level}
        className="rounded-full focus-visible:outline-4 focus-visible:outline-focus focus-visible:outline-offset-2"
      >
        <Badge tone={meta.tone}>{meta.label}</Badge>
      </button>
    </Tooltip>
  );
}
