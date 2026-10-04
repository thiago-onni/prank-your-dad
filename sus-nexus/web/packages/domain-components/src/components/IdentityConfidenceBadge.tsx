import type { IdentityConfidence } from '@sus-nexus/api-client';
import { Badge, Tooltip } from '@sus-nexus/design-system';
import { identityConfidenceLabels } from '../lib/labels';

export interface IdentityConfidenceBadgeProps {
  confidence: IdentityConfidence;
  /** Evidência adicional (ex.: "Score 0,93 — regra mpi-v3"). */
  evidence?: string;
  className?: string;
}

/** Confiança da identidade: confirmado / provável / pendente / divergente, com tooltip de evidência. */
export function IdentityConfidenceBadge({
  confidence,
  evidence,
  className,
}: IdentityConfidenceBadgeProps) {
  const meta = identityConfidenceLabels[confidence];
  const tooltip = evidence ? `${meta.description} ${evidence}` : meta.description;
  return (
    <Tooltip content={tooltip}>
      <button
        type="button"
        className="inline-flex rounded-full focus-visible:outline-4 focus-visible:outline-focus"
        aria-label={`Identidade: ${meta.label}. ${tooltip}`}
      >
        <Badge tone={meta.tone} className={className} data-confidence={confidence}>
          {meta.label}
        </Badge>
      </button>
    </Tooltip>
  );
}
