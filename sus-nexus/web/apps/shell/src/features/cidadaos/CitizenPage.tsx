'use client';

import { useState } from 'react';
import { useHasRole } from '@sus-nexus/auth/client';
import { useCitizen, useHealthUnits, type MaskedIdentifier } from '@sus-nexus/api-client';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@sus-nexus/design-system';
import { CitizenHeader } from '@sus-nexus/domain-components';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { RevealIdentifierDialog } from './RevealIdentifierDialog';
import { CarePlansTab } from './CarePlansTab';
import { FhirTab } from './FhirTab';
import { FHIR_VIEWER_ROLES } from '@/lib/situacao/roles';
import { SummaryPanel } from './SummaryPanel';
import { TimelineTab } from './TimelineTab';

export const CITIZEN_TABS = ['resumo', 'timeline', 'plano', 'fhir'] as const;
export type CitizenTab = (typeof CITIZEN_TABS)[number];

export function isCitizenTab(value: unknown): value is CitizenTab {
  return typeof value === 'string' && (CITIZEN_TABS as readonly string[]).includes(value);
}

export function CitizenPage({
  citizenId,
  initialTab = 'resumo',
}: {
  citizenId: string;
  initialTab?: CitizenTab;
}) {
  const query = useCitizen(citizenId);
  // Aba FHIR só para papéis clínicos/gestão (usabilidade; o gateway aplica escopos/OPA).
  const canSeeFhir = useHasRole(...FHIR_VIEWER_ROLES);
  const tab = initialTab === 'fhir' && !canSeeFhir ? 'resumo' : initialTab;
  const units = useHealthUnits({ limit: 100 });
  const [revealTarget, setRevealTarget] = useState<MaskedIdentifier | null>(null);
  // Valores revelados vivem apenas em memória desta tela (somem ao recarregar).
  const [revealed, setRevealed] = useState<Record<string, string>>({});

  return (
    <PurposeRequired>
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(citizen) => (
          <div className="flex flex-col gap-6">
            <CitizenHeader
              citizen={citizen}
              healthUnitName={
                units.data?.items.find((u) => u.cnes === citizen.health_unit_cnes)?.name
              }
              onRevealIdentifier={setRevealTarget}
              revealed={revealed}
              consents={[
                { kind: 'data_sharing', label: 'Compartilhamento de dados', state: 'granted' },
                {
                  kind: 'sms',
                  label: 'SMS',
                  state: citizen.contacts?.some((c) => c.kind === 'mobile') ? 'granted' : 'unknown',
                },
              ]}
            />
            <Tabs defaultValue={tab}>
              <TabsList aria-label={t.citizen.title}>
                <TabsTrigger value="resumo">{t.citizen.summary}</TabsTrigger>
                <TabsTrigger value="timeline">{t.citizen.timeline}</TabsTrigger>
                <TabsTrigger value="plano">{t.carePlan.tab}</TabsTrigger>
                {canSeeFhir ? <TabsTrigger value="fhir">{t.fhir.tab}</TabsTrigger> : null}
              </TabsList>
              <TabsContent value="resumo">
                <SummaryPanel citizen={citizen} />
              </TabsContent>
              <TabsContent value="timeline">
                <TimelineTab citizenId={citizen.id} />
              </TabsContent>
              <TabsContent value="plano">
                <CarePlansTab citizen={citizen} />
              </TabsContent>
              {canSeeFhir ? (
                <TabsContent value="fhir">
                  <FhirTab citizenId={citizen.id} />
                </TabsContent>
              ) : null}
            </Tabs>
            <RevealIdentifierDialog
              citizenId={citizen.id}
              identifier={revealTarget}
              onClose={() => setRevealTarget(null)}
              onRevealed={(id, value) => setRevealed((prev) => ({ ...prev, [id]: value }))}
            />
          </div>
        )}
      </QueryState>
    </PurposeRequired>
  );
}
