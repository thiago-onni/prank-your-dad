'use client';

import { useId, useState } from 'react';
import {
  useCorrectProductionRecord,
  type ProductionCorrectionChanges,
  type ProductionRecord,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Input,
  Textarea,
  useToast,
} from '@sus-nexus/design-system';
import { describeError } from '@/lib/problem';
import { format, t } from '@/i18n';

type EditableField =
  'procedure_code' | 'professional_cbo' | 'quantity' | 'cid_code' | 'attendance_date' | 'cnes';

const FIELDS: {
  key: EditableField;
  label: string;
  pattern?: RegExp;
  type?: string;
  inputMode?: 'numeric';
  maxLength?: number;
}[] = [
  {
    key: 'procedure_code',
    label: 'Procedimento SIGTAP',
    pattern: /^\d{10}$/,
    inputMode: 'numeric',
    maxLength: 10,
  },
  {
    key: 'professional_cbo',
    label: 'CBO do profissional',
    pattern: /^\d{6}$/,
    inputMode: 'numeric',
    maxLength: 6,
  },
  {
    key: 'quantity',
    label: 'Quantidade',
    pattern: /^[1-9]\d{0,5}$/,
    inputMode: 'numeric',
    maxLength: 6,
  },
  { key: 'cid_code', label: 'CID-10', pattern: /^[A-Z]\d{2}(\.?\d)?$/i, maxLength: 5 },
  { key: 'attendance_date', label: 'Data do atendimento', type: 'date' },
  { key: 'cnes', label: 'CNES', pattern: /^\d{7}$/, inputMode: 'numeric', maxLength: 7 },
];

/** Situações em que o core aceita correção (PRO-006). */
export const CORRECTABLE_STATUSES: ProductionRecord['status'][] = [
  'pending',
  'validated',
  'rejected',
];

/**
 * Formulário de correção com justificativa (≥ 10 caracteres). Avisos abertos podem ser
 * dispensados com a mesma justificativa; erros não (só a correção do campo os resolve).
 */
export function CorrectionForm({ record }: { record: ProductionRecord }) {
  const [values, setValues] = useState<Partial<Record<EditableField, string>>>({});
  const [waive, setWaive] = useState<string[]>([]);
  const [justification, setJustification] = useState('');
  const [errors, setErrors] = useState<{
    justification?: string;
    form?: string;
    fields?: Partial<Record<EditableField, string>>;
  }>({});
  const mutation = useCorrectProductionRecord();
  const { toast } = useToast();
  const headingId = useId();
  const open = (record.issues ?? []).filter((i) => i.status === 'open');
  const openWarnings = open.filter((i) => i.severity === 'warning');
  const openErrors = open.filter((i) => i.severity === 'error');

  if (!CORRECTABLE_STATUSES.includes(record.status)) {
    return (
      <Card as="section" aria-labelledby={headingId}>
        <CardHeader
          headingLevel={2}
          title={<span id={headingId}>{t.production.correctionTitle}</span>}
        />
        <p className="text-sm text-fg-muted">{t.production.correctionNotAllowed}</p>
      </Card>
    );
  }

  const current = (k: EditableField) => {
    const v: string | number | undefined = record[k];
    return v === undefined || v === '' ? '—' : String(v);
  };

  const submit = () => {
    const fieldErrors: Partial<Record<EditableField, string>> = {};
    const changes: ProductionCorrectionChanges = {};
    for (const f of FIELDS) {
      const raw = values[f.key]?.trim();
      if (!raw || raw === current(f.key)) continue;
      if (f.pattern && !f.pattern.test(raw)) {
        fieldErrors[f.key] = format(t.production.invalidField, { field: f.label });
        continue;
      }
      if (f.key === 'quantity') changes.quantity = Number(raw);
      else if (f.key === 'cid_code') changes.cid_code = raw.toUpperCase();
      else changes[f.key] = raw;
    }
    const just = justification.trim();
    const next: typeof errors = {};
    if (Object.keys(fieldErrors).length > 0) next.fields = fieldErrors;
    if (just.length < 10 || just.length > 1000)
      next.justification = t.production.justificationInvalid;
    if (Object.keys(changes).length === 0 && waive.length === 0 && !next.fields)
      next.form = t.production.nothingToCorrect;
    setErrors(next);
    if (Object.keys(next).length > 0) return;

    mutation.mutate(
      {
        recordId: record.id,
        justification: just,
        changes,
        // Somente avisos — erros nunca são enviados para dispensa.
        waive_issue_ids: waive.filter((id) => openWarnings.some((w) => w.id === id)),
      },
      {
        onSuccess: () => {
          toast({ title: t.production.corrected, tone: 'success' });
          setValues({});
          setWaive([]);
          setJustification('');
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.production.correctionFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Card as="section" aria-labelledby={headingId}>
      <CardHeader
        headingLevel={2}
        title={<span id={headingId}>{t.production.correctionTitle}</span>}
        description={t.production.correctionDescription}
      />
      <form
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
        className="flex flex-col gap-4"
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {FIELDS.map((f) => (
            <Input
              key={f.key}
              label={format(t.production.newValue, { field: f.label })}
              description={format(t.production.currentValue, { value: current(f.key) })}
              type={f.type ?? 'text'}
              inputMode={f.inputMode}
              maxLength={f.maxLength}
              value={values[f.key] ?? ''}
              error={errors.fields?.[f.key]}
              onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
            />
          ))}
        </div>

        {openWarnings.length > 0 ? (
          <fieldset className="flex flex-col gap-2 rounded-md border border-border p-3">
            <legend className="px-1 text-base font-semibold">{t.production.waiveTitle}</legend>
            <p className="text-sm text-fg-muted">{t.production.waiveHelp}</p>
            {openWarnings.map((w) => (
              <label key={w.id} className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  className="mt-1 h-4 w-4"
                  checked={waive.includes(w.id)}
                  onChange={(e) =>
                    setWaive((cur) =>
                      e.target.checked ? [...cur, w.id] : cur.filter((x) => x !== w.id),
                    )
                  }
                />
                <span>{format(t.production.waiveIssue, { message: w.message })}</span>
              </label>
            ))}
          </fieldset>
        ) : null}

        {openErrors.length > 0 ? (
          <div className="rounded-md border border-danger bg-danger-subtle p-3 text-sm">
            <p className="font-medium text-danger-fg-subtle">{t.production.errorsNotWaivable}</p>
            <ul className="mt-1 list-disc pl-5">
              {openErrors.map((e) => (
                <li key={e.id}>
                  <Badge tone="danger">Erro</Badge> {e.message}
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        <Textarea
          label={t.production.justification}
          required
          description={t.production.justificationHelp}
          value={justification}
          maxLength={1000}
          rows={3}
          error={errors.justification}
          onChange={(e) => setJustification(e.target.value)}
        />
        {errors.form ? (
          <p role="alert" className="text-sm font-medium text-danger-fg-subtle">
            {errors.form}
          </p>
        ) : null}
        <div>
          <Button type="submit" loading={mutation.isPending}>
            {t.production.submitCorrection}
          </Button>
        </div>
      </form>
    </Card>
  );
}
