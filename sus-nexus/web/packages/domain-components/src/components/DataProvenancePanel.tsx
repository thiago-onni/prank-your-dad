import type { Provenance } from '@sus-nexus/api-client';
import {
  Card,
  CardHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import { formatDateTime } from '../lib/format';
import { SourceSystemBadge } from './SourceSystemBadge';

export interface ProvenanceHistoryEntry {
  attribute: string;
  source_system: string;
  received_at: string;
  previous_value_masked?: string;
  new_value_masked?: string;
}

export interface DataProvenancePanelProps {
  /** Origem por atributo (`attribute_provenance` do CitizenDetail). */
  provenance: Record<string, Provenance>;
  history?: ProvenanceHistoryEntry[];
  attributeLabels?: Record<string, string>;
  className?: string;
}

const defaultAttributeLabels: Record<string, string> = {
  legal_name: 'Nome civil',
  social_name: 'Nome social',
  mother_name: 'Nome da mãe',
  birthdate: 'Data de nascimento',
  sex: 'Sexo',
  address: 'Endereço',
  contacts: 'Contatos',
  health_unit_cnes: 'UBS de referência',
  team_ine: 'Equipe',
  microarea: 'Microárea',
  identifiers: 'Identificadores',
};

function confidenceLabel(c: number | undefined): string {
  if (c === undefined) return '—';
  return `${Math.round(c * 100)}%`;
}

/** Origem por atributo, histórico e confiança. */
export function DataProvenancePanel({
  provenance,
  history,
  attributeLabels,
  className,
}: DataProvenancePanelProps) {
  const labels = { ...defaultAttributeLabels, ...attributeLabels };
  const entries = Object.entries(provenance);
  return (
    <Card as="section" className={className} aria-labelledby="provenance-title">
      <CardHeader
        title={<span id="provenance-title">Procedência dos dados</span>}
        description="Sistema de origem, data de recebimento e confiança de cada atributo."
      />
      {entries.length === 0 ? (
        <p className="text-sm text-fg-muted">Sem informações de procedência.</p>
      ) : (
        <Table aria-label="Procedência por atributo">
          <TableHead>
            <TableRow>
              <TableHeaderCell>Atributo</TableHeaderCell>
              <TableHeaderCell>Origem</TableHeaderCell>
              <TableHeaderCell>Registro de origem</TableHeaderCell>
              <TableHeaderCell>Confiança</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {entries.map(([attr, p]) => (
              <TableRow key={attr}>
                <TableHeaderCell scope="row" className="font-medium">
                  {labels[attr] ?? attr}
                </TableHeaderCell>
                <TableCell>
                  <SourceSystemBadge sourceSystem={p.source_system} syncedAt={p.received_at} />
                </TableCell>
                <TableCell className="font-mono text-xs">{p.source_record_id ?? '—'}</TableCell>
                <TableCell>
                  <meter
                    min={0}
                    max={1}
                    value={p.confidence ?? 0}
                    aria-label={`Confiança ${confidenceLabel(p.confidence)}`}
                    className="mr-2 h-3 w-16 align-middle"
                  />
                  {confidenceLabel(p.confidence)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {history && history.length > 0 ? (
        <details className="mt-4">
          <summary className="cursor-pointer text-sm font-semibold text-primary-fg-subtle">
            Histórico de alterações ({history.length})
          </summary>
          <ul className="mt-2 flex flex-col gap-1 text-sm">
            {history.map((h, i) => (
              <li
                key={i}
                className="flex flex-wrap gap-2 border-b border-border py-1 last:border-0"
              >
                <span className="text-fg-muted">{formatDateTime(h.received_at)}</span>
                <span className="font-medium">{labels[h.attribute] ?? h.attribute}</span>
                <span>
                  {h.previous_value_masked ?? '—'} → {h.new_value_masked ?? '—'}
                </span>
                <span className="text-fg-subtle">via {h.source_system}</span>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </Card>
  );
}
