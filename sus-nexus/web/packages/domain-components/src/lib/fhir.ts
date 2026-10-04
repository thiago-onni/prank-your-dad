/**
 * Utilitários FHIR sem React (podem rodar no BFF): mascaramento de CPF/CNS e resumo legível por tipo.
 */
import { formatDate, formatDateTime, maskCns, maskCpf } from './format';

export type JsonValue =
  string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = Record<string, JsonValue>;

const CPF_SYSTEM_RE =
  /(^|[/:.])cpf$|2\.16\.840\.1\.113883\.13\.237\.?cpf|urn:oid:2\.16\.840\.1\.113883\.13\.237$/i;
const CNS_SYSTEM_RE = /(^|[/:.])cns$|urn:oid:2\.16\.840\.1\.113883\.13\.236$/i;

/** Sequências com cara de CPF (11 dígitos, com ou sem pontuação) ou CNS (15 dígitos). */
const CPF_TEXT_RE = /(?<![0-9])[0-9]{3}\.?[0-9]{3}\.?[0-9]{3}-?[0-9]{2}(?![0-9])/g;
const CNS_TEXT_RE = /(?<![0-9])[1-9][0-9]{2} ?[0-9]{4} ?[0-9]{4} ?[0-9]{4}(?![0-9])/g;
const ALREADY_MASKED_RE = /^[*•]/;
/** Chaves técnicas (ids/URLs/códigos) que não passam pela varredura de texto. */
const STRUCTURAL_KEYS = new Set([
  'id',
  'reference',
  'fullUrl',
  'url',
  'system',
  'code',
  'version',
  'versionId',
  'profile',
  'lastUpdated',
  'resourceType',
]);

/** Mascara CPF/CNS embutidos em texto livre (defesa em profundidade). */
export function scrubIdentifiersInText(text: string): string {
  return text.replace(CNS_TEXT_RE, (m) => maskCns(m)).replace(CPF_TEXT_RE, (m) => maskCpf(m));
}

function identifierKind(system: unknown): 'cpf' | 'cns' | undefined {
  if (typeof system !== 'string') return undefined;
  if (CPF_SYSTEM_RE.test(system)) return 'cpf';
  if (CNS_SYSTEM_RE.test(system)) return 'cns';
  return undefined;
}

function maskIdentifierObject(obj: JsonObject): JsonObject {
  const kind = identifierKind(obj.system);
  const out: JsonObject = {};
  for (const [k, v] of Object.entries(obj)) {
    if (k === 'value' && typeof v === 'string' && kind && !ALREADY_MASKED_RE.test(v)) {
      out[k] = kind === 'cpf' ? maskCpf(v) : maskCns(v);
    } else if (STRUCTURAL_KEYS.has(k) && typeof v === 'string') {
      out[k] = v;
    } else {
      out[k] = maskFhirIdentifiers(v);
    }
  }
  return out;
}

/**
 * Mascara CPF/CNS em qualquer lugar do recurso/Bundle: `identifier.value` pelo `system`
 * (NamingSystem RNDS `…/cpf`, `…/cns` ou OIDs) e qualquer string com formato de CPF/CNS.
 * Ids, códigos e referências técnicas não são alterados (não casam o padrão).
 */
export function maskFhirIdentifiers(value: JsonValue): JsonValue {
  if (Array.isArray(value)) return value.map(maskFhirIdentifiers);
  if (value && typeof value === 'object') {
    if ('system' in value && 'value' in value) return maskIdentifierObject(value);
    const out: JsonObject = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = STRUCTURAL_KEYS.has(k) && typeof v === 'string' ? v : maskFhirIdentifiers(v);
    }
    return out;
  }
  if (typeof value === 'string') return scrubIdentifiersInText(value);
  return value;
}

// ---------------------------------------------------------------------------------------------
// Resumo legível por tipo
// ---------------------------------------------------------------------------------------------

export interface FhirSummaryField {
  label: string;
  value: string;
}

export interface FhirSummary {
  resourceType: string;
  /** Rótulo do tipo em pt-BR. */
  typeLabel: string;
  title: string;
  status?: string;
  date?: string;
  fields: FhirSummaryField[];
}

export const FHIR_TYPE_LABELS: Record<string, string> = {
  Patient: 'Paciente',
  Encounter: 'Atendimento',
  Observation: 'Observação',
  DiagnosticReport: 'Laudo',
  ServiceRequest: 'Solicitação',
  Appointment: 'Agendamento',
  CarePlan: 'Plano de cuidado',
  DocumentReference: 'Documento',
  Task: 'Tarefa',
  Condition: 'Condição',
  Organization: 'Estabelecimento',
  Practitioner: 'Profissional',
  Location: 'Local',
};

const STATUS_LABELS: Record<string, string> = {
  active: 'ativo',
  completed: 'concluído',
  finished: 'finalizado',
  'in-progress': 'em andamento',
  planned: 'planejado',
  cancelled: 'cancelado',
  revoked: 'revogado',
  'on-hold': 'suspenso',
  draft: 'rascunho',
  final: 'final',
  preliminary: 'preliminar',
  amended: 'retificado',
  partial: 'parcial',
  booked: 'marcado',
  fulfilled: 'realizado',
  noshow: 'falta',
  arrived: 'chegou',
  proposed: 'proposto',
  requested: 'solicitado',
  accepted: 'aceito',
  current: 'vigente',
  superseded: 'substituído',
  'entered-in-error': 'registrado por engano',
  registered: 'registrado',
  unknown: 'desconhecido',
};

const GENDER_LABELS: Record<string, string> = {
  male: 'masculino',
  female: 'feminino',
  other: 'outro',
  unknown: 'desconhecido',
};

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  !!v && typeof v === 'object' && !Array.isArray(v);
const asArray = (v: JsonValue | undefined): JsonValue[] =>
  Array.isArray(v) ? v : v === undefined || v === null ? [] : [v];
const asStr = (v: JsonValue | undefined): string | undefined =>
  typeof v === 'string' ? v : typeof v === 'number' ? String(v) : undefined;

/** Texto de um CodeableConcept/Coding: `text` > `display` > `code`. */
export function codeableText(v: JsonValue | undefined): string | undefined {
  if (!isObj(v)) return asStr(v);
  const text = asStr(v.text);
  if (text) return text;
  for (const c of asArray(v.coding)) {
    if (!isObj(c)) continue;
    const display = asStr(c.display) ?? asStr(c.code);
    if (display) return display;
  }
  return asStr(v.display) ?? asStr(v.code);
}

function referenceText(v: JsonValue | undefined): string | undefined {
  if (!isObj(v)) return undefined;
  const display = asStr(v.display);
  if (display) return display;
  const ref = asStr(v.reference);
  if (ref) return ref;
  if (isObj(v.identifier)) return asStr(v.identifier.value);
  return undefined;
}

function periodText(v: JsonValue | undefined): string | undefined {
  if (!isObj(v)) return undefined;
  const start = asStr(v.start);
  const end = asStr(v.end);
  if (!start && !end) return undefined;
  return `${start ? formatDateTime(start) : '…'} – ${end ? formatDateTime(end) : 'em aberto'}`;
}

function quantityText(v: JsonValue | undefined): string | undefined {
  if (!isObj(v)) return undefined;
  const value = asStr(v.value);
  if (value === undefined) return undefined;
  const unit = asStr(v.unit) ?? asStr(v.code);
  return unit ? `${value.replace('.', ',')} ${unit}` : value.replace('.', ',');
}

function humanName(v: JsonValue | undefined): string | undefined {
  const names = asArray(v).filter(isObj);
  const preferred =
    names.find((n) => n.use === 'usual') ?? names.find((n) => n.use === 'official') ?? names[0];
  if (!preferred) return undefined;
  const text = asStr(preferred.text);
  if (text) return text;
  const given = asArray(preferred.given).map(asStr).filter(Boolean).join(' ');
  const family = asStr(preferred.family);
  return [given, family].filter(Boolean).join(' ') || undefined;
}

function statusLabel(v: JsonValue | undefined): string | undefined {
  const s = asStr(v);
  return s ? (STATUS_LABELS[s] ?? s) : undefined;
}

function push(fields: FhirSummaryField[], label: string, value: string | undefined) {
  if (value) fields.push({ label, value });
}

function identifierLabel(system: string | undefined): string {
  if (!system) return 'Identificador';
  if (CPF_SYSTEM_RE.test(system)) return 'CPF';
  if (CNS_SYSTEM_RE.test(system)) return 'CNS';
  if (/cnes$/i.test(system)) return 'CNES';
  return 'Identificador';
}

/**
 * Resumo legível por tipo. Recebe o recurso (idealmente já mascarado) e aplica de novo a máscara
 * de CPF/CNS em cada valor exibido.
 */
export function summarizeFhirResource(resource: JsonObject): FhirSummary {
  const type = asStr(resource.resourceType) ?? 'Resource';
  const fields: FhirSummaryField[] = [];
  let title: string | undefined;
  let date: string | undefined;

  switch (type) {
    case 'Patient': {
      title = humanName(resource.name) ?? 'Paciente';
      push(fields, 'Sexo', GENDER_LABELS[asStr(resource.gender) ?? ''] ?? asStr(resource.gender));
      push(
        fields,
        'Nascimento',
        asStr(resource.birthDate) ? formatDate(asStr(resource.birthDate)) : undefined,
      );
      for (const id of asArray(resource.identifier).filter(isObj)) {
        const system = asStr(id.system);
        const label = identifierLabel(system);
        if (label === 'Identificador') continue;
        push(fields, label, asStr(id.value));
      }
      push(fields, 'Unidade de referência', referenceText(resource.managingOrganization));
      break;
    }
    case 'Encounter': {
      title =
        codeableText(asArray(resource.type)[0]) ?? codeableText(resource.class) ?? 'Atendimento';
      push(fields, 'Classe', codeableText(resource.class));
      push(fields, 'Período', periodText(resource.period));
      push(fields, 'Estabelecimento', referenceText(resource.serviceProvider));
      push(
        fields,
        'Motivo',
        asArray(resource.reasonCode).map(codeableText).filter(Boolean).join('; ') || undefined,
      );
      date = asStr(isObj(resource.period) ? resource.period.start : undefined);
      break;
    }
    case 'Observation': {
      title = codeableText(resource.code) ?? 'Observação';
      push(
        fields,
        'Resultado',
        quantityText(resource.valueQuantity) ??
          asStr(resource.valueString) ??
          codeableText(resource.valueCodeableConcept) ??
          (typeof resource.valueBoolean === 'boolean'
            ? resource.valueBoolean
              ? 'sim'
              : 'não'
            : undefined),
      );
      push(
        fields,
        'Interpretação',
        asArray(resource.interpretation).map(codeableText).filter(Boolean).join('; ') || undefined,
      );
      date = asStr(resource.effectiveDateTime) ?? asStr(resource.issued);
      push(fields, 'Data', date ? formatDateTime(date) : undefined);
      break;
    }
    case 'DiagnosticReport': {
      title = codeableText(resource.code) ?? 'Laudo';
      push(
        fields,
        'Categoria',
        asArray(resource.category).map(codeableText).filter(Boolean).join('; ') || undefined,
      );
      push(fields, 'Conclusão', asStr(resource.conclusion));
      push(
        fields,
        'Resultados',
        asArray(resource.result).length ? String(asArray(resource.result).length) : undefined,
      );
      push(
        fields,
        'Executante',
        asArray(resource.performer).map(referenceText).filter(Boolean).join('; ') || undefined,
      );
      date = asStr(resource.effectiveDateTime) ?? asStr(resource.issued);
      push(fields, 'Data', date ? formatDateTime(date) : undefined);
      break;
    }
    case 'ServiceRequest': {
      title = codeableText(resource.code) ?? 'Solicitação';
      push(
        fields,
        'Categoria',
        asArray(resource.category).map(codeableText).filter(Boolean).join('; ') || undefined,
      );
      push(fields, 'Prioridade', asStr(resource.priority));
      push(fields, 'Solicitante', referenceText(resource.requester));
      push(
        fields,
        'Executante',
        asArray(resource.performer).map(referenceText).filter(Boolean).join('; ') || undefined,
      );
      date = asStr(resource.authoredOn);
      push(fields, 'Solicitado em', date ? formatDateTime(date) : undefined);
      break;
    }
    case 'Appointment': {
      title =
        asArray(resource.serviceType).map(codeableText).find(Boolean) ??
        asStr(resource.description) ??
        'Agendamento';
      date = asStr(resource.start);
      push(fields, 'Início', date ? formatDateTime(date) : undefined);
      push(
        fields,
        'Local',
        asArray(resource.participant)
          .filter(isObj)
          .map((p) => referenceText(p.actor))
          .filter((r) => r && !r.startsWith('Patient/'))
          .join('; ') || undefined,
      );
      break;
    }
    case 'CarePlan': {
      title =
        asStr(resource.title) ??
        asArray(resource.category).map(codeableText).find(Boolean) ??
        'Plano de cuidado';
      push(
        fields,
        'Linha de cuidado',
        asArray(resource.category).map(codeableText).filter(Boolean).join('; ') || undefined,
      );
      push(fields, 'Período', periodText(resource.period));
      push(
        fields,
        'Atividades',
        asArray(resource.activity).length ? String(asArray(resource.activity).length) : undefined,
      );
      date =
        asStr(isObj(resource.period) ? resource.period.start : undefined) ??
        asStr(resource.created);
      break;
    }
    case 'DocumentReference': {
      title = codeableText(resource.type) ?? asStr(resource.description) ?? 'Documento';
      date = asStr(resource.date);
      push(fields, 'Data', date ? formatDateTime(date) : undefined);
      const attachment = asArray(resource.content)
        .filter(isObj)
        .map((c) => c.attachment)[0];
      push(fields, 'Formato', isObj(attachment) ? asStr(attachment.contentType) : undefined);
      push(
        fields,
        'Autor',
        asArray(resource.author).map(referenceText).filter(Boolean).join('; ') || undefined,
      );
      break;
    }
    case 'Task': {
      title = codeableText(resource.code) ?? asStr(resource.description) ?? 'Tarefa';
      push(fields, 'Situação de negócio', codeableText(resource.businessStatus));
      push(fields, 'Prioridade', asStr(resource.priority));
      push(fields, 'Responsável', referenceText(resource.owner));
      date = asStr(resource.authoredOn);
      push(fields, 'Criada em', date ? formatDateTime(date) : undefined);
      break;
    }
    case 'Condition': {
      title = codeableText(resource.code) ?? 'Condição';
      push(fields, 'Situação clínica', codeableText(resource.clinicalStatus));
      date = asStr(resource.onsetDateTime) ?? asStr(resource.recordedDate);
      push(fields, 'Início', date ? formatDate(date) : undefined);
      break;
    }
    default:
      title = asStr(resource.id) ?? type;
  }

  const status = statusLabel(resource.status);
  return {
    resourceType: type,
    typeLabel: FHIR_TYPE_LABELS[type] ?? type,
    title: scrubIdentifiersInText(title ?? type),
    status,
    date,
    fields: fields.map((f) => ({ label: f.label, value: scrubIdentifiersInText(f.value) })),
  };
}
