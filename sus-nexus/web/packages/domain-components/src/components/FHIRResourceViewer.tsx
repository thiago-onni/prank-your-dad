'use client';

import { useId, useMemo, useState } from 'react';
import { Badge, Button, Card, CardHeader, cn } from '@sus-nexus/design-system';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { SENSITIVE_KEYS } from '../lib/format';
import { maskFhirIdentifiers, summarizeFhirResource, type JsonValue } from '../lib/fhir';

export type { JsonValue } from '../lib/fhir';

export interface FHIRValidationIssue {
  severity: 'error' | 'warning' | 'information';
  message: string;
  path?: string;
}

export interface FHIRResourceViewerProps {
  resource: Record<string, JsonValue>;
  profile?: string;
  validation?: FHIRValidationIssue[];
  /** Chaves adicionais a mascarar (além do conjunto padrão). */
  sensitiveKeys?: string[];
  /** Permite alternar a visualização (árvore/JSON). */
  initialView?: 'tree' | 'json';
  /**
   * `strict` (padrão): mascara todas as chaves sensíveis (nome, contato, endereço, valores…).
   * `identifiers`: mascara CPF/CNS (por `system` e por formato, em qualquer campo) e preserva o
   * conteúdo clínico — para telas clínicas com finalidade registrada.
   * Em ambos os modos CPF/CNS nunca aparecem em claro (resumo, árvore e JSON).
   */
  masking?: 'strict' | 'identifiers';
  /** Exibe o resumo legível por tipo (padrão: sim). */
  showSummary?: boolean;
  /** Esconde a árvore/JSON atrás de um botão "Mostrar JSON" (padrão: não). */
  collapsibleRaw?: boolean;
  headingLevel?: 2 | 3 | 4;
  className?: string;
}

function maskString(s: string): string {
  if (s.length <= 2) return '••';
  return `${s.slice(0, 1)}${'•'.repeat(Math.min(s.length - 2, 8))}${s.slice(-1)}`;
}

/** Aplica mascaramento recursivo por chave; nunca expõe valores sensíveis em claro. */
export function maskResource(
  value: JsonValue,
  sensitive: Set<string>,
  parentSensitive = false,
): JsonValue {
  if (Array.isArray(value)) return value.map((v) => maskResource(v, sensitive, parentSensitive));
  if (value && typeof value === 'object') {
    const out: Record<string, JsonValue> = {};
    for (const [k, v] of Object.entries(value)) {
      const isSensitive = parentSensitive || sensitive.has(k);
      if (
        k === 'resourceType' ||
        k === 'id' ||
        k === 'system' ||
        k === 'code' ||
        k === 'status' ||
        k === 'use'
      ) {
        out[k] = v;
        continue;
      }
      out[k] = maskResource(v, sensitive, isSensitive);
    }
    return out;
  }
  if (parentSensitive && typeof value === 'string') return maskString(value);
  if (parentSensitive && typeof value === 'number') return '•••';
  return value;
}

function TreeNode({ name, value, depth }: { name: string; value: JsonValue; depth: number }) {
  const [open, setOpen] = useState(depth < 2);
  const isObject = value !== null && typeof value === 'object';
  if (!isObject) {
    return (
      <li className="flex gap-2 py-0.5" style={{ paddingLeft: depth * 16 }}>
        <span className="font-mono text-primary-fg-subtle">{name}:</span>
        <span
          className={cn(
            'font-mono',
            typeof value === 'string' ? 'text-success-fg-subtle' : 'text-fg',
          )}
        >
          {value === null ? 'null' : typeof value === 'string' ? `"${value}"` : String(value)}
        </span>
      </li>
    );
  }
  const entries = Array.isArray(value)
    ? value.map((v, i) => [String(i), v] as const)
    : Object.entries(value);
  return (
    <li className="py-0.5">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="inline-flex items-center gap-1 rounded-sm font-mono text-fg hover:bg-bg-muted"
        style={{ marginLeft: depth * 16 }}
      >
        {open ? (
          <ChevronDown aria-hidden="true" className="h-3 w-3" />
        ) : (
          <ChevronRight aria-hidden="true" className="h-3 w-3" />
        )}
        <span className="text-primary-fg-subtle">{name}</span>
        <span className="text-fg-subtle">
          {Array.isArray(value) ? `[${entries.length}]` : `{${entries.length}}`}
        </span>
      </button>
      {open ? (
        <ul>
          {entries.map(([k, v]) => (
            <TreeNode key={k} name={k} value={v} depth={depth + 1} />
          ))}
        </ul>
      ) : null}
    </li>
  );
}

/** Visualizador de recurso FHIR (resumo, árvore/JSON) com mascaramento aplicado, perfil e validação. */
export function FHIRResourceViewer({
  resource,
  profile,
  validation = [],
  sensitiveKeys = [],
  initialView = 'tree',
  masking = 'strict',
  showSummary = true,
  collapsibleRaw = false,
  headingLevel = 3,
  className,
}: FHIRResourceViewerProps) {
  const uid = useId();
  const titleId = `${uid}-fhir-title`;
  const rawId = `${uid}-fhir-raw`;
  const [view, setView] = useState<'tree' | 'json'>(initialView);
  const [rawOpen, setRawOpen] = useState(!collapsibleRaw);
  const masked = useMemo(() => {
    const ids = maskFhirIdentifiers(resource) as Record<string, JsonValue>;
    if (masking === 'identifiers') return ids;
    return maskResource(ids, new Set([...SENSITIVE_KEYS, ...sensitiveKeys])) as Record<
      string,
      JsonValue
    >;
  }, [resource, sensitiveKeys, masking]);
  const summary = useMemo(() => summarizeFhirResource(masked), [masked]);
  const resourceType =
    typeof resource.resourceType === 'string' ? resource.resourceType : 'Resource';
  const errors = validation.filter((v) => v.severity === 'error').length;

  return (
    <Card as="section" className={className} aria-labelledby={titleId}>
      <CardHeader
        headingLevel={headingLevel}
        title={
          <span id={titleId}>
            {showSummary ? (
              <>
                <span className="text-fg-muted">{summary.typeLabel}: </span>
                {summary.title}
              </>
            ) : (
              resourceType
            )}
            {typeof resource.id === 'string' ? (
              <span className="font-mono text-sm text-fg-muted">
                {' '}
                ({resourceType}/{resource.id})
              </span>
            ) : null}
          </span>
        }
        description={
          <>
            {profile ? (
              <>
                Perfil: <code className="font-mono text-xs">{profile}</code> ·{' '}
              </>
            ) : null}
            {masking === 'strict' ? 'Valores sensíveis mascarados.' : 'CPF/CNS mascarados.'}
          </>
        }
        actions={summary.status ? <Badge tone="neutral">{summary.status}</Badge> : null}
      />
      {showSummary && summary.fields.length > 0 ? (
        <dl className="mb-3 grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
          {summary.fields.map((f, i) => (
            <div key={`${f.label}-${i}`} className="flex gap-2">
              <dt className="text-fg-muted">{f.label}:</dt>
              <dd className="font-medium text-fg">{f.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {validation.length > 0 ? (
        <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
          <Badge tone={errors > 0 ? 'danger' : 'success'}>
            {errors > 0 ? `${errors} erro(s) de validação` : 'Válido'}
          </Badge>
          <ul className="basis-full">
            {validation.map((v, i) => (
              <li
                key={i}
                className={cn(v.severity === 'error' ? 'text-danger-fg-subtle' : 'text-fg-muted')}
              >
                [{v.severity}] {v.path ? <code className="font-mono">{v.path}</code> : null}{' '}
                {v.message}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <div className="flex flex-wrap items-center gap-2">
        {collapsibleRaw ? (
          <Button
            size="sm"
            variant="secondary"
            aria-expanded={rawOpen}
            aria-controls={rawId}
            onClick={() => setRawOpen((o) => !o)}
          >
            {rawOpen ? 'Ocultar JSON' : 'Mostrar JSON'}
          </Button>
        ) : null}
        {rawOpen ? (
          <div role="group" aria-label="Modo de visualização" className="flex gap-1">
            <Button
              size="sm"
              variant={view === 'tree' ? 'primary' : 'secondary'}
              aria-pressed={view === 'tree'}
              onClick={() => setView('tree')}
            >
              Árvore
            </Button>
            <Button
              size="sm"
              variant={view === 'json' ? 'primary' : 'secondary'}
              aria-pressed={view === 'json'}
              onClick={() => setView('json')}
            >
              JSON
            </Button>
          </div>
        ) : null}
      </div>
      <div id={rawId} hidden={!rawOpen} className="mt-3">
        {!rawOpen ? null : view === 'tree' ? (
          <ul className="overflow-x-auto text-sm" aria-label="Árvore do recurso">
            {Object.entries(masked).map(([k, v]) => (
              <TreeNode key={k} name={k} value={v} depth={0} />
            ))}
          </ul>
        ) : (
          <pre
            // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- bloco rolável precisa de foco por teclado
            tabIndex={0}
            className="overflow-x-auto rounded-md bg-bg-muted p-3 font-mono text-xs"
            aria-label="JSON do recurso"
          >
            {JSON.stringify(masked, null, 2)}
          </pre>
        )}
      </div>
    </Card>
  );
}
