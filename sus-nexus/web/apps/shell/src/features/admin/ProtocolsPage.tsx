'use client';

import { useMemo, useState } from 'react';
import { Plus } from 'lucide-react';
import { useProtocols, type Protocol } from '@sus-nexus/api-client';
import { ROLES } from '@sus-nexus/auth';
import { useHasRole } from '@sus-nexus/auth/client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import {
  PROTOCOL_NEXT_ACTION,
  careLineLabel,
  formatDate,
  protocolStatusLabels,
  protocolTransitionLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { ProtocolEditorDialog } from './ProtocolEditorDialog';
import { ProtocolTransitionDialog, type PendingTransition } from './ProtocolTransitionDialog';

/** Compara versões semânticas simples (`1.10.0` > `1.9.0`). */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

function CareLineCard({
  line,
  versions,
  onNewVersion,
  onTransition,
}: {
  line: string;
  versions: Protocol[];
  onNewVersion: (base: Protocol) => void;
  onTransition: (p: PendingTransition) => void;
}) {
  const sorted = [...versions].sort((a, b) => compareVersions(b.version, a.version));
  const current = sorted.find((p) => p.status === 'active');
  const latest = sorted[0];
  const headingId = `protocol-${line}`;
  return (
    <Card as="section" aria-labelledby={headingId}>
      <CardHeader
        headingLevel={2}
        title={
          <span id={headingId}>
            {careLineLabel(line)}{' '}
            <span className="text-base font-normal text-fg-muted">
              — {(current ?? latest)?.name}
            </span>
          </span>
        }
        description={
          current
            ? `${t.protocols.current}: v${current.version} · ${t.protocols.effectiveFrom} ${formatDate(current.effective_from)}`
            : t.protocols.noCurrent
        }
        actions={
          latest ? (
            <Button size="sm" variant="secondary" onClick={() => onNewVersion(current ?? latest)}>
              {t.protocols.newVersion}
            </Button>
          ) : null
        }
      />
      <Table aria-label={`${t.protocols.versions}: ${careLineLabel(line)}`}>
        <TableHead>
          <TableRow>
            <TableHeaderCell>{t.protocols.version}</TableHeaderCell>
            <TableHeaderCell>{t.protocols.status}</TableHeaderCell>
            <TableHeaderCell>{t.protocols.items}</TableHeaderCell>
            <TableHeaderCell>{t.protocols.testCases}</TableHeaderCell>
            <TableHeaderCell>{t.protocols.approvedBy}</TableHeaderCell>
            <TableHeaderCell>
              <span className="sr-only">{t.app.actions}</span>
            </TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {sorted.map((p) => {
            const st = protocolStatusLabels[p.status];
            const next = PROTOCOL_NEXT_ACTION[p.status];
            return (
              <TableRow key={`${p.id}-${p.version}`}>
                <TableCell>
                  <span className="font-mono">v{p.version}</span>
                  {p.description ? (
                    <span className="block text-xs text-fg-muted">{p.description}</span>
                  ) : null}
                </TableCell>
                <TableCell>
                  <Badge tone={st.tone}>{st.label}</Badge>
                </TableCell>
                <TableCell className="tabular-nums">{p.items.length}</TableCell>
                <TableCell>
                  <Badge tone={p.test_cases_count ? 'success' : 'danger'}>
                    {p.test_cases_count ?? 0}
                  </Badge>
                </TableCell>
                <TableCell>{p.approved_by ?? '—'}</TableCell>
                <TableCell>
                  {next ? (
                    <Button
                      size="sm"
                      variant={next === 'revoke' ? 'ghost' : 'secondary'}
                      onClick={() => onTransition({ protocol: p, action: next })}
                      aria-label={`${protocolTransitionLabels[next]}: v${p.version}`}
                    >
                      {protocolTransitionLabels[next]}
                    </Button>
                  ) : null}
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </Card>
  );
}

/** Administração de protocolos de linha de cuidado (gestor/admin — CUI-009). */
export function ProtocolsPage() {
  const allowed = useHasRole(ROLES.GESTOR, ROLES.ADMIN_MUNICIPAL);
  const query = useProtocols({}, { enabled: allowed });
  const [editor, setEditor] = useState<{ base?: Protocol; key: number } | null>(null);
  const [pending, setPending] = useState<PendingTransition | null>(null);
  const lines = useMemo(() => {
    const by = new Map<string, Protocol[]>();
    for (const p of query.data ?? []) by.set(p.care_line, [...(by.get(p.care_line) ?? []), p]);
    return [...by.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [query.data]);

  return (
    <>
      <PageHeader
        title={t.protocols.title}
        description={t.protocols.description}
        actions={
          allowed ? (
            <Button size="sm" onClick={() => setEditor({ key: Date.now() })}>
              <Plus aria-hidden="true" className="h-4 w-4" />
              {t.protocols.newProtocol}
            </Button>
          ) : null
        }
      />
      {!allowed ? (
        <EmptyState title={t.auth.forbidden} description={t.protocols.forbidden} />
      ) : (
        <QueryState
          isLoading={query.isLoading}
          error={query.error}
          data={query.data}
          onRetry={() => void query.refetch()}
        >
          {() =>
            lines.length === 0 ? (
              <EmptyState title={t.protocols.none} />
            ) : (
              <div className="flex flex-col gap-4">
                {lines.map(([line, versions]) => (
                  <CareLineCard
                    key={line}
                    line={line}
                    versions={versions}
                    onNewVersion={(base) => setEditor({ base, key: Date.now() })}
                    onTransition={setPending}
                  />
                ))}
              </div>
            )
          }
        </QueryState>
      )}
      {editor ? (
        <ProtocolEditorDialog
          key={editor.key}
          base={editor.base}
          open
          onOpenChange={(o) => (!o ? setEditor(null) : undefined)}
        />
      ) : null}
      <ProtocolTransitionDialog pending={pending} onClose={() => setPending(null)} />
    </>
  );
}
