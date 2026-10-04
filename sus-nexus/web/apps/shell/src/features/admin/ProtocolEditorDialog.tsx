'use client';

import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import {
  useCreateProtocolVersion,
  type CarePlanItemKind,
  type Protocol,
  type ProtocolItemPriority,
  type ProtocolItemRule,
} from '@sus-nexus/api-client';
import {
  Button,
  Dialog,
  DialogContent,
  Input,
  Select,
  Textarea,
  useToast,
} from '@sus-nexus/design-system';
import { carePlanItemKindLabels, protocolItemPriorityLabels } from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { format, t } from '@/i18n';

const KINDS = Object.keys(carePlanItemKindLabels) as CarePlanItemKind[];
const PRIORITIES = Object.keys(protocolItemPriorityLabels) as ProtocolItemPriority[];

interface ItemDraft {
  key: number;
  kind: CarePlanItemKind;
  title: string;
  code: string;
  due_in_days: string;
  periodicity_days: string;
  gap_after_days: string;
  priority: ProtocolItemPriority;
}

let nextKey = 1;
const toDraft = (i?: ProtocolItemRule): ItemDraft => ({
  key: nextKey++,
  kind: i?.kind ?? 'consultation',
  title: i?.title ?? '',
  code: i?.code ?? '',
  due_in_days: i ? String(i.due_in_days) : '',
  periodicity_days: i?.periodicity_days !== undefined ? String(i.periodicity_days) : '',
  gap_after_days: i?.gap_after_days !== undefined ? String(i.gap_after_days) : '',
  priority: i?.priority ?? 'medium',
});

const optionalInt = (v: string) => {
  if (v.trim() === '') return undefined;
  const n = Number(v);
  return Number.isInteger(n) && n >= 0 ? n : Number.NaN;
};

export type TestCases = Record<string, unknown>[];

/** Valida o JSON dos casos de teste: lista de objetos (vazio = nenhum caso). */
export function parseTestCases(text: string): TestCases | null {
  if (text.trim() === '') return [];
  try {
    const parsed: unknown = JSON.parse(text);
    if (
      Array.isArray(parsed) &&
      parsed.every((x) => typeof x === 'object' && x !== null && !Array.isArray(x))
    )
      return parsed as TestCases;
    return null;
  } catch {
    return null;
  }
}

/** Editor de nova versão de protocolo (nasce como rascunho). */
export function ProtocolEditorDialog({
  base,
  open,
  onOpenChange,
}: {
  /** Versão base (nova versão de protocolo existente) ou `undefined` (novo protocolo). */
  base: Protocol | undefined;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [careLine, setCareLine] = useState(base?.care_line ?? '');
  const [name, setName] = useState(base?.name ?? '');
  const [description, setDescription] = useState(base?.description ?? '');
  const [lostDays, setLostDays] = useState(
    base?.lost_to_followup_days !== undefined ? String(base.lost_to_followup_days) : '',
  );
  const [items, setItems] = useState<ItemDraft[]>(() =>
    base?.items.length ? base.items.map(toDraft) : [toDraft()],
  );
  const [testCases, setTestCases] = useState('');
  const [errors, setErrors] = useState<{ form?: string; items?: string; tests?: string }>({});
  const mutation = useCreateProtocolVersion();
  const { toast } = useToast();

  const update = (key: number, patch: Partial<ItemDraft>) =>
    setItems((prev) => prev.map((i) => (i.key === key ? { ...i, ...patch } : i)));

  const submit = () => {
    const next: typeof errors = {};
    if (!careLine.trim() || !name.trim() || items.length === 0)
      next.form = t.protocols.requiredFields;
    const rules: ProtocolItemRule[] = items.map((i) => ({
      kind: i.kind,
      title: i.title.trim(),
      code: i.code.trim() || undefined,
      due_in_days: optionalInt(i.due_in_days) ?? Number.NaN,
      periodicity_days: optionalInt(i.periodicity_days),
      gap_after_days: optionalInt(i.gap_after_days),
      priority: i.priority,
    }));
    if (
      rules.some(
        (r) =>
          !r.title ||
          Number.isNaN(r.due_in_days) ||
          Number.isNaN(r.periodicity_days ?? 0) ||
          Number.isNaN(r.gap_after_days ?? 0),
      )
    )
      next.items = t.protocols.itemsInvalid;
    const cases = parseTestCases(testCases);
    if (!cases) next.tests = t.protocols.testCasesInvalid;
    setErrors(next);
    if (next.form || next.items || next.tests || !cases) return;
    const lost = optionalInt(lostDays);
    mutation.mutate(
      {
        care_line: careLine.trim(),
        name: name.trim(),
        description: description.trim() || undefined,
        base_version: base?.version,
        eligibility: base?.eligibility,
        items: rules,
        lost_to_followup_days: lost !== undefined && !Number.isNaN(lost) ? lost : undefined,
        test_cases: cases,
      },
      {
        onSuccess: () => {
          toast({ title: t.protocols.created, tone: 'success' });
          onOpenChange(false);
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.protocols.createFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        size="lg"
        title={base ? `${t.protocols.editorTitle}: ${base.name}` : t.protocols.newProtocol}
        description={t.protocols.editorDescription}
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)}>
              {t.app.cancel}
            </Button>
            <Button onClick={submit} loading={mutation.isPending}>
              {t.protocols.create}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          {errors.form ? (
            <p role="alert" className="text-sm font-medium text-danger-fg-subtle">
              {errors.form}
            </p>
          ) : null}
          <div className="grid gap-3 sm:grid-cols-2">
            <Input
              label={t.protocols.careLine}
              description={t.protocols.careLineHelp}
              required
              value={careLine}
              readOnly={Boolean(base)}
              onChange={(e) => setCareLine(e.target.value.toLowerCase().replace(/\s+/g, '_'))}
            />
            <Input
              label={t.protocols.name}
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={120}
            />
            {base ? <Input label={t.protocols.baseVersion} value={base.version} readOnly /> : null}
            <Input
              label={t.protocols.lostToFollowup}
              type="number"
              min={0}
              value={lostDays}
              onChange={(e) => setLostDays(e.target.value)}
            />
          </div>
          <Textarea
            label={t.protocols.descriptionField}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={500}
            rows={2}
          />

          <div className="flex flex-col gap-3">
            <h3 className="text-lg font-semibold">{t.protocols.items}</h3>
            {errors.items ? (
              <p role="alert" className="text-sm font-medium text-danger-fg-subtle">
                {errors.items}
              </p>
            ) : null}
            {items.map((item, idx) => {
              const n = idx + 1;
              return (
                <fieldset
                  key={item.key}
                  className="grid gap-3 rounded-md border border-border p-3 sm:grid-cols-2 lg:grid-cols-4"
                >
                  <legend className="px-1 text-sm font-semibold">
                    {format(t.protocols.itemN, { n })}
                  </legend>
                  <Select
                    label={t.protocols.itemKind}
                    value={item.kind}
                    onValueChange={(v) => update(item.key, { kind: v as CarePlanItemKind })}
                    options={KINDS.map((k) => ({ value: k, label: carePlanItemKindLabels[k] }))}
                  />
                  <Input
                    label={t.protocols.itemTitle}
                    required
                    value={item.title}
                    onChange={(e) => update(item.key, { title: e.target.value })}
                    containerClassName="lg:col-span-2"
                  />
                  <Input
                    label={t.protocols.itemCode}
                    value={item.code}
                    onChange={(e) => update(item.key, { code: e.target.value })}
                  />
                  <Input
                    label={t.protocols.dueInDays}
                    required
                    type="number"
                    min={0}
                    value={item.due_in_days}
                    onChange={(e) => update(item.key, { due_in_days: e.target.value })}
                  />
                  <Input
                    label={t.protocols.periodicity}
                    type="number"
                    min={0}
                    value={item.periodicity_days}
                    onChange={(e) => update(item.key, { periodicity_days: e.target.value })}
                  />
                  <Input
                    label={t.protocols.gapAfter}
                    type="number"
                    min={0}
                    value={item.gap_after_days}
                    onChange={(e) => update(item.key, { gap_after_days: e.target.value })}
                  />
                  <Select
                    label={t.protocols.priority}
                    value={item.priority}
                    onValueChange={(v) => update(item.key, { priority: v as ProtocolItemPriority })}
                    options={PRIORITIES.map((p) => ({
                      value: p,
                      label: protocolItemPriorityLabels[p].label,
                    }))}
                  />
                  <div className="flex items-end justify-end sm:col-span-2 lg:col-span-4">
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={items.length === 1}
                      onClick={() => setItems((prev) => prev.filter((i) => i.key !== item.key))}
                      aria-label={format(t.protocols.removeItem, { n })}
                    >
                      <Trash2 aria-hidden="true" className="h-4 w-4" />
                      {format(t.protocols.removeItem, { n })}
                    </Button>
                  </div>
                </fieldset>
              );
            })}
            <div>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setItems((prev) => [...prev, toDraft()])}
              >
                <Plus aria-hidden="true" className="h-4 w-4" />
                {t.protocols.addItem}
              </Button>
            </div>
          </div>

          <Textarea
            label={t.protocols.testCasesJson}
            description={t.protocols.testCasesHelp}
            value={testCases}
            onChange={(e) => {
              setTestCases(e.target.value);
              if (errors.tests) setErrors((prev) => ({ ...prev, tests: undefined }));
            }}
            error={errors.tests}
            rows={5}
            className="font-mono text-sm"
            spellCheck={false}
          />
        </div>
      </DialogContent>
    </Dialog>
  );
}
