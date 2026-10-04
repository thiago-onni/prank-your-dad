'use client';

import { useMemo, useState } from 'react';
import { Badge, Button, Card, CardHeader, cn } from '@sus-nexus/design-system';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { SENSITIVE_KEYS } from '../lib/format';

export type JsonValue =
  string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

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

/** Visualizador de recurso FHIR (árvore/JSON) com mascaramento aplicado, perfil e validação. */
export function FHIRResourceViewer({
  resource,
  profile,
  validation = [],
  sensitiveKeys = [],
  initialView = 'tree',
  className,
}: FHIRResourceViewerProps) {
  const [view, setView] = useState<'tree' | 'json'>(initialView);
  const masked = useMemo(
    () =>
      maskResource(resource, new Set([...SENSITIVE_KEYS, ...sensitiveKeys])) as Record<
        string,
        JsonValue
      >,
    [resource, sensitiveKeys],
  );
  const resourceType =
    typeof resource.resourceType === 'string' ? resource.resourceType : 'Resource';
  const errors = validation.filter((v) => v.severity === 'error').length;

  return (
    <Card as="section" className={className} aria-labelledby="fhir-title">
      <CardHeader
        title={
          <span id="fhir-title">
            {resourceType}
            {typeof resource.id === 'string' ? (
              <span className="font-mono text-sm text-fg-muted"> /{resource.id}</span>
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
            Valores sensíveis mascarados.
          </>
        }
        actions={
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
        }
      />
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
      {view === 'tree' ? (
        <ul className="overflow-x-auto text-sm" aria-label="Árvore do recurso">
          {Object.entries(masked).map(([k, v]) => (
            <TreeNode key={k} name={k} value={v} depth={0} />
          ))}
        </ul>
      ) : (
        <pre
          className="overflow-x-auto rounded-md bg-bg-muted p-3 font-mono text-xs"
          aria-label="JSON do recurso"
        >
          {JSON.stringify(masked, null, 2)}
        </pre>
      )}
    </Card>
  );
}
