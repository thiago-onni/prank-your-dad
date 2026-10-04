import { HttpResponse, http } from 'msw';
import type { CitizenDetail } from '@sus-nexus/api-client';
import { citizens, daysAgo, daysAhead, unitByCnes } from './data';

/**
 * FHIR Gateway sintético (MSW): `GET /fhir/r4/Patient/{id}/$everything` com o compartimento do
 * paciente projetado a partir dos cidadãos de `data.ts`. Como o gateway pode devolver CPF/CNS em
 * claro (quando o canônico os tem), o mock traz valores FICTÍCIOS em claro — a tela/BFF devem
 * mascará-los. Exige `X-Purpose-Of-Use` (o gateway grava `AuditEvent.purposeOfEvent`).
 */

const SYSTEM_CNS = 'http://rnds.saude.gov.br/fhir/r4/NamingSystem/cns';
const SYSTEM_CPF = 'http://rnds.saude.gov.br/fhir/r4/NamingSystem/cpf';
const SYSTEM_CNES = 'http://rnds.saude.gov.br/fhir/r4/NamingSystem/cnes';
const BASE = 'https://sus-nexus.gov.br/fhir';

const fhirId = (id: string) => id.replace(/^[a-z]{2,5}_/, '');

/** Dígitos fictícios estáveis derivados do id (nunca dados reais). */
function fakeDigits(seed: string, n: number, first = ''): string {
  let h = 0;
  for (const ch of seed) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  let s = first;
  while (s.length < n) {
    h = (h * 1103515245 + 12345) >>> 0;
    s += String(h % 10);
  }
  return s;
}

export const fakeCpfOf = (c: CitizenDetail) => fakeDigits(`cpf:${c.id}`, 11);
export const fakeCnsOf = (c: CitizenDetail) => fakeDigits(`cns:${c.id}`, 15, '7');

function outcome(status: number, code: string, diagnostics: string) {
  return HttpResponse.json(
    { resourceType: 'OperationOutcome', issue: [{ severity: 'error', code, diagnostics }] },
    { status, headers: { 'content-type': 'application/fhir+json' } },
  );
}

export function compartmentOf(c: CitizenDetail): Record<string, unknown>[] {
  const pid = fhirId(c.id);
  const patientRef = { reference: `Patient/${pid}` };
  const unit = unitByCnes(c.health_unit_cnes);
  const org = {
    identifier: { system: SYSTEM_CNES, value: c.health_unit_cnes ?? '2126672' },
    display: unit?.name ?? 'UBS',
  };
  const meta = (profile: string) => ({
    versionId: '1',
    lastUpdated: daysAgo(2),
    profile: [`${BASE}/StructureDefinition/${profile}`],
  });
  const [given, ...rest] = (c.legal_name ?? c.display_name).split(' ');
  return [
    {
      resourceType: 'Patient',
      id: pid,
      meta: meta('SUSNexusPatient'),
      identifier: [
        { use: 'official', system: SYSTEM_CNS, value: fakeCnsOf(c) },
        { use: 'official', system: SYSTEM_CPF, value: fakeCpfOf(c) },
        { system: `${BASE}/NamingSystem/municipal-citizen-id`, value: c.id },
      ],
      name: [
        {
          use: 'official',
          text: c.legal_name ?? c.display_name,
          family: rest.join(' '),
          given: [given],
        },
        ...(c.social_name ? [{ use: 'usual', text: c.social_name }] : []),
      ],
      gender: c.sex === 'female' ? 'female' : c.sex === 'male' ? 'male' : 'unknown',
      birthDate: c.birthdate,
      managingOrganization: org,
    },
    {
      resourceType: 'Encounter',
      id: `enc-${pid.slice(-8)}`,
      meta: meta('BRCoreEncounter'),
      status: 'finished',
      class: {
        system: 'http://terminology.hl7.org/CodeSystem/v3-ActCode',
        code: 'AMB',
        display: 'ambulatorial',
      },
      type: [{ text: 'Consulta de enfermagem (APS)' }],
      subject: patientRef,
      period: { start: daysAgo(20), end: daysAgo(20) },
      serviceProvider: org,
      reasonCode: [
        {
          coding: [
            {
              system: 'http://www.saude.gov.br/fhir/r4/CodeSystem/BRCIAP2',
              code: 'K86',
              display: 'Hipertensão sem complicações',
            },
          ],
        },
      ],
    },
    {
      resourceType: 'Condition',
      id: `cond-${pid.slice(-8)}`,
      meta: meta('BRCoreCondition'),
      clinicalStatus: { coding: [{ code: 'active', display: 'Ativa' }] },
      code: {
        coding: [
          {
            system: 'http://www.saude.gov.br/fhir/r4/CodeSystem/BRCID10',
            code: 'I10',
            display: 'Hipertensão essencial',
          },
        ],
      },
      subject: patientRef,
      onsetDateTime: '2019-05-10',
    },
    {
      resourceType: 'ServiceRequest',
      id: `sr-${pid.slice(-8)}`,
      meta: meta('SUSNexusServiceRequest'),
      status: 'completed',
      intent: 'order',
      priority: 'routine',
      category: [
        {
          coding: [
            { system: 'http://snomed.info/sct', code: '108252007', display: 'Exame laboratorial' },
          ],
        },
      ],
      code: {
        coding: [
          {
            system: `${BASE}/CodeSystem/sigtap`,
            code: '0202010473',
            display: 'Dosagem de glicose',
          },
        ],
      },
      subject: patientRef,
      authoredOn: daysAgo(18),
      requester: org,
    },
    {
      resourceType: 'Observation',
      id: `obs-${pid.slice(-8)}`,
      meta: meta('SUSNexusObservation'),
      status: 'final',
      code: {
        coding: [
          { system: 'http://loinc.org', code: '2345-7', display: 'Glicose [massa/volume] no soro' },
        ],
      },
      subject: patientRef,
      effectiveDateTime: daysAgo(15),
      valueQuantity: {
        value: 126,
        unit: 'mg/dL',
        system: 'http://unitsofmeasure.org',
        code: 'mg/dL',
      },
      interpretation: [{ coding: [{ code: 'A', display: 'Alterado' }] }],
      // Texto livre com CPF digitado pelo profissional (o mascaramento por formato precisa pegar).
      note: [
        {
          text: `Paciente informou CPF ${fakeCpfOf(c).replace(/(\d{3})(\d{3})(\d{3})(\d{2})/, '$1.$2.$3-$4')} na coleta.`,
        },
      ],
    },
    {
      resourceType: 'DiagnosticReport',
      id: `dr-${pid.slice(-8)}`,
      meta: meta('SUSNexusDiagnosticReport'),
      status: 'final',
      category: [{ coding: [{ code: 'LAB', display: 'Laboratório' }] }],
      code: {
        coding: [
          {
            system: `${BASE}/CodeSystem/sigtap`,
            code: '0202010473',
            display: 'Dosagem de glicose',
          },
        ],
      },
      subject: patientRef,
      basedOn: [{ reference: `ServiceRequest/sr-${pid.slice(-8)}` }],
      effectiveDateTime: daysAgo(15),
      issued: daysAgo(14),
      performer: [
        { identifier: { system: SYSTEM_CNES, value: '2219522' }, display: 'Policlínica Municipal' },
      ],
      result: [{ reference: `Observation/obs-${pid.slice(-8)}` }],
      conclusion: 'Glicemia de jejum acima do valor de referência.',
    },
    {
      resourceType: 'DocumentReference',
      id: `doc-${pid.slice(-8)}`,
      meta: meta('SUSNexusDocumentReference'),
      status: 'current',
      type: {
        coding: [{ system: 'http://loinc.org', code: '11502-2', display: 'Laudo laboratorial' }],
      },
      subject: patientRef,
      date: daysAgo(14),
      author: [{ display: 'Policlínica Municipal' }],
      content: [
        {
          attachment: {
            contentType: 'application/pdf',
            url: `${BASE}/Binary/bin-${pid.slice(-8)}`,
            title: 'Laudo.pdf',
          },
        },
      ],
    },
    {
      resourceType: 'Appointment',
      id: `apt-${pid.slice(-8)}`,
      meta: meta('SUSNexusAppointment'),
      status: 'booked',
      serviceType: [{ text: 'Consulta médica — retorno' }],
      start: daysAhead(6),
      end: daysAhead(6),
      participant: [
        { actor: patientRef, status: 'accepted' },
        {
          actor: {
            reference: `Location/loc-${c.health_unit_cnes ?? 'x'}`,
            display: unit?.name ?? 'UBS',
          },
          status: 'accepted',
        },
      ],
    },
    {
      resourceType: 'CarePlan',
      id: `cp-${pid.slice(-8)}`,
      meta: meta('SUSNexusCarePlan'),
      status: 'active',
      intent: 'plan',
      title: 'Linha de cuidado — hipertensão',
      category: [{ coding: [{ code: 'hipertensao', display: 'Hipertensão' }] }],
      subject: patientRef,
      period: { start: daysAgo(120) },
      activity: [
        { detail: { status: 'completed', code: { text: 'Consulta trimestral' } } },
        { detail: { status: 'scheduled', code: { text: 'Glicemia de jejum' } } },
      ],
    },
    {
      resourceType: 'Task',
      id: `task-${pid.slice(-8)}`,
      meta: meta('SUSNexusTask'),
      status: 'requested',
      intent: 'order',
      priority: 'urgent',
      code: {
        coding: [
          {
            system: `${BASE}/CodeSystem/task-type`,
            code: 'exam_result_return',
            display: 'Retorno de resultado de exame',
          },
        ],
      },
      for: patientRef,
      authoredOn: daysAgo(14),
      owner: org,
    },
  ];
}

export const fhirHandlers = [
  http.get(/\/fhir\/r4\/Patient\/([^/]+)\/\$everything$/, ({ request }) => {
    if (!request.headers.get('X-Purpose-Of-Use')) {
      return outcome(400, 'required', 'Cabeçalho X-Purpose-Of-Use é obrigatório.');
    }
    const url = new URL(request.url);
    const id = decodeURIComponent(
      /\/Patient\/([^/]+)\/\$everything$/.exec(url.pathname)?.[1] ?? '',
    );
    const citizen = citizens.find((c) => fhirId(c.id) === id);
    if (!citizen) return outcome(404, 'not-found', `Patient/${id} não encontrado.`);
    let resources = compartmentOf(citizen);
    const types = url.searchParams.get('_type');
    if (types) {
      const set = new Set(types.split(','));
      resources = resources.filter((r) => set.has(String(r.resourceType)));
    }
    const count = Math.min(Number(url.searchParams.get('_count') ?? 50) || 50, 200);
    const cursor = url.searchParams.get('_cursor');
    const start = cursor ? Number(atob(cursor)) || 0 : 0;
    const page = resources.slice(start, start + count);
    const link = [{ relation: 'self', url: url.toString() }];
    if (start + count < resources.length) {
      const next = new URL(url);
      next.searchParams.set('_cursor', btoa(String(start + count)));
      link.push({ relation: 'next', url: next.toString() });
    }
    return HttpResponse.json(
      {
        resourceType: 'Bundle',
        type: 'searchset',
        total: resources.length,
        link,
        entry: page.map((resource) => ({
          fullUrl: `${url.origin}/fhir/r4/${String(resource.resourceType)}/${String(resource.id)}`,
          resource,
          search: { mode: 'match' },
        })),
      },
      { headers: { 'content-type': 'application/fhir+json' } },
    );
  }),
];
