import type { CitizenDetail, CitizenSummary, MaskedIdentifier } from '@sus-nexus/api-client';
import { Badge, Button, cn } from '@sus-nexus/design-system';
import { Eye } from 'lucide-react';
import { formatAge, formatDate } from '../lib/format';
import { identifierSystemLabels, registrationStateLabels, sexLabels } from '../lib/labels';
import { IdentityConfidenceBadge } from './IdentityConfidenceBadge';
import { ConsentStatusIndicator, type ConsentStatus } from './ConsentStatusIndicator';

export interface CitizenHeaderProps {
  citizen: CitizenSummary | CitizenDetail;
  /** Nome da UBS de referência (resolvido a partir do CNES). */
  healthUnitName?: string;
  consents?: ConsentStatus[];
  /** Ação explícita de revelar identificador (abre fluxo com finalidade + justificativa). */
  onRevealIdentifier?: (identifier: MaskedIdentifier) => void;
  /** Valores revelados nesta sessão (id do identificador → valor em claro formatado). */
  revealed?: Record<string, string>;
  className?: string;
}

/**
 * Cabeçalho do cidadão: nome social prioritário, idade, identificadores mascarados,
 * equipe/UBS de referência, situação cadastral e alertas de consentimento.
 */
export function CitizenHeader({
  citizen,
  healthUnitName,
  consents,
  onRevealIdentifier,
  revealed,
  className,
}: CitizenHeaderProps) {
  const detail = citizen as Partial<CitizenDetail>;
  const socialName = detail.social_name;
  const legalName = detail.legal_name;
  const displayName = socialName || citizen.display_name;
  const state = registrationStateLabels[citizen.registration_state];

  return (
    <header
      className={cn('rounded-lg border border-border bg-surface p-4 shadow-level-1', className)}
    >
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold leading-tight text-fg">
            {displayName}
            {socialName ? (
              <span className="ml-2 text-sm font-normal text-fg-muted">(nome social)</span>
            ) : null}
          </h1>
          {socialName && legalName && legalName !== socialName ? (
            <p className="text-sm text-fg-muted">
              Nome civil: <span className="font-medium">{legalName}</span>
            </p>
          ) : null}
          <dl className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-sm">
            <div>
              <dt className="inline text-fg-muted">Idade: </dt>
              <dd className="inline font-medium">
                {formatAge(citizen.birthdate)}
                {citizen.birthdate ? (
                  <span className="text-fg-muted"> ({formatDate(citizen.birthdate)})</span>
                ) : null}
              </dd>
            </div>
            {citizen.sex ? (
              <div>
                <dt className="inline text-fg-muted">Sexo: </dt>
                <dd className="inline font-medium">{sexLabels[citizen.sex] ?? citizen.sex}</dd>
              </div>
            ) : null}
            {citizen.mother_name_masked ? (
              <div>
                <dt className="inline text-fg-muted">Mãe: </dt>
                <dd className="inline font-medium">{citizen.mother_name_masked}</dd>
              </div>
            ) : null}
          </dl>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={state.tone}>{state.label}</Badge>
          {citizen.identity_confidence ? (
            <IdentityConfidenceBadge confidence={citizen.identity_confidence} />
          ) : null}
        </div>
      </div>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <section aria-labelledby="citizen-identifiers">
          <h2
            id="citizen-identifiers"
            className="text-xs font-semibold uppercase tracking-wide text-fg-muted"
          >
            Identificadores
          </h2>
          <ul className="mt-1 flex flex-col gap-1">
            {citizen.identifiers.length === 0 ? (
              <li className="text-sm text-fg-muted">Nenhum identificador</li>
            ) : null}
            {citizen.identifiers.map((id) => {
              const value = revealed?.[id.id];
              return (
                <li key={id.id} className="flex flex-wrap items-center gap-2 text-sm">
                  <span className="w-28 shrink-0 font-semibold">
                    {identifierSystemLabels[id.system] ?? id.system}
                  </span>
                  <span
                    className="font-mono"
                    aria-label={value ? `${id.system} revelado` : `${id.system} mascarado`}
                  >
                    {value ?? id.value_masked}
                  </span>
                  {id.status !== 'active' ? (
                    <Badge tone={id.status === 'invalid' ? 'danger' : 'neutral'}>
                      {id.status === 'invalid' ? 'Inválido' : 'Desativado'}
                    </Badge>
                  ) : null}
                  {onRevealIdentifier && !value && (id.system === 'CPF' || id.system === 'CNS') ? (
                    <Button
                      variant="tertiary"
                      size="sm"
                      onClick={() => onRevealIdentifier(id)}
                      aria-label={`Revelar ${id.system} (exige finalidade e justificativa)`}
                    >
                      <Eye aria-hidden="true" className="h-4 w-4" />
                      Revelar
                    </Button>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </section>

        <section aria-labelledby="citizen-territory">
          <h2
            id="citizen-territory"
            className="text-xs font-semibold uppercase tracking-wide text-fg-muted"
          >
            Referência territorial
          </h2>
          <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt className="text-fg-muted">UBS</dt>
            <dd className="font-medium">
              {healthUnitName ??
                (citizen.health_unit_cnes ? `CNES ${citizen.health_unit_cnes}` : '—')}
              {healthUnitName && citizen.health_unit_cnes ? (
                <span className="text-fg-muted"> · CNES {citizen.health_unit_cnes}</span>
              ) : null}
            </dd>
            <dt className="text-fg-muted">Equipe (INE)</dt>
            <dd className="font-medium">{citizen.team_ine ?? '—'}</dd>
            <dt className="text-fg-muted">Microárea</dt>
            <dd className="font-medium">{citizen.microarea ?? '—'}</dd>
          </dl>
          {consents && consents.length > 0 ? (
            <div className="mt-2">
              <ConsentStatusIndicator consents={consents} compact />
            </div>
          ) : null}
        </section>
      </div>
    </header>
  );
}
