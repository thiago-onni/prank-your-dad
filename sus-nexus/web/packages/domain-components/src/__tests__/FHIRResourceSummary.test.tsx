import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { FHIRResourceViewer } from '../components/FHIRResourceViewer';
import { maskFhirIdentifiers, summarizeFhirResource, type JsonObject } from '../lib/fhir';

const CPF = '52998224725';
const CNS = '898001234567890';
const patient: JsonObject = {
  resourceType: 'Patient',
  id: '01HZX4Y5K6M7N8P9Q0R1S2T3U4',
  identifier: [
    { system: 'http://rnds.saude.gov.br/fhir/r4/NamingSystem/cns', value: CNS },
    { system: 'http://rnds.saude.gov.br/fhir/r4/NamingSystem/cpf', value: CPF },
    { system: 'urn:oid:2.16.840.1.113883.13.237', value: '12345678909' },
    {
      system: 'https://sus-nexus.gov.br/fhir/NamingSystem/municipal-citizen-id',
      value: 'cit_01HZX',
    },
  ],
  name: [{ use: 'official', text: 'Maria das Dores Silva' }],
  gender: 'female',
  birthDate: '1985-03-15',
};

const observation: JsonObject = {
  resourceType: 'Observation',
  id: 'obs-1',
  status: 'final',
  code: { coding: [{ system: 'http://loinc.org', code: '2345-7', display: 'Glicose no soro' }] },
  valueQuantity: { value: 126, unit: 'mg/dL' },
  effectiveDateTime: '2026-09-19T10:00:00-03:00',
  note: [{ text: 'CPF 529.982.247-25 e CNS 898 0012 3456 7890 citados na coleta.' }],
};

describe('maskFhirIdentifiers', () => {
  it('mascara CPF/CNS por system (RNDS e OID) e por formato em texto livre; preserva o resto', () => {
    const masked = maskFhirIdentifiers({
      resourceType: 'Bundle',
      entry: [{ resource: patient }, { resource: observation }],
    });
    const json = JSON.stringify(masked);
    for (const clear of [CPF, CNS, '12345678909', '529.982.247-25', '898 0012 3456 7890']) {
      expect(json).not.toContain(clear);
    }
    expect(json).toContain('***.***.***-25');
    expect(json).toContain('*** **** **** 7890');
    expect(json).toContain('***.***.***-09');
    expect(json).toContain('cit_01HZX');
    expect(json).toContain('01HZX4Y5K6M7N8P9Q0R1S2T3U4');
    expect(json).toContain('"value":126');
    expect(json).toContain('Maria das Dores Silva');
  });
});

describe('summarizeFhirResource — resumo legível por tipo', () => {
  it.each([
    [patient, 'Paciente', 'Maria das Dores Silva', ['Sexo', 'Nascimento', 'CNS', 'CPF']],
    [observation, 'Observação', 'Glicose no soro', ['Resultado', 'Data']],
    [
      {
        resourceType: 'Encounter',
        status: 'finished',
        class: { code: 'AMB', display: 'ambulatorial' },
        type: [{ text: 'Consulta de enfermagem' }],
        period: { start: '2026-09-14T09:00:00-03:00' },
      },
      'Atendimento',
      'Consulta de enfermagem',
      ['Classe', 'Período'],
    ],
    [
      {
        resourceType: 'DiagnosticReport',
        status: 'final',
        code: { text: 'Dosagem de glicose' },
        conclusion: 'Acima da referência',
        result: [{ reference: 'Observation/obs-1' }],
      },
      'Laudo',
      'Dosagem de glicose',
      ['Conclusão', 'Resultados'],
    ],
    [
      {
        resourceType: 'ServiceRequest',
        status: 'active',
        code: { text: 'Ultrassonografia' },
        priority: 'routine',
        authoredOn: '2026-09-01T10:00:00-03:00',
      },
      'Solicitação',
      'Ultrassonografia',
      ['Prioridade', 'Solicitado em'],
    ],
    [
      {
        resourceType: 'Appointment',
        status: 'booked',
        serviceType: [{ text: 'Consulta médica' }],
        start: '2026-10-10T08:00:00-03:00',
        participant: [
          { actor: { reference: 'Patient/x' } },
          { actor: { display: 'UBS Vila Oliveira' } },
        ],
      },
      'Agendamento',
      'Consulta médica',
      ['Início', 'Local'],
    ],
    [
      {
        resourceType: 'CarePlan',
        status: 'active',
        title: 'Linha de cuidado — hipertensão',
        category: [{ text: 'Hipertensão' }],
        activity: [{}, {}],
      },
      'Plano de cuidado',
      'Linha de cuidado — hipertensão',
      ['Linha de cuidado', 'Atividades'],
    ],
    [
      {
        resourceType: 'DocumentReference',
        status: 'current',
        type: { text: 'Laudo laboratorial' },
        date: '2026-09-20T10:00:00-03:00',
        content: [{ attachment: { contentType: 'application/pdf' } }],
      },
      'Documento',
      'Laudo laboratorial',
      ['Data', 'Formato'],
    ],
  ] as [JsonObject, string, string, string[]][])('%#: %s', (resource, typeLabel, title, labels) => {
    const s = summarizeFhirResource(resource);
    expect(s.typeLabel).toBe(typeLabel);
    expect(s.title).toBe(title);
    expect(s.fields.map((f) => f.label)).toEqual(expect.arrayContaining(labels));
  });

  it('nunca expõe CPF/CNS no resumo, mesmo sem máscara prévia', () => {
    const s = summarizeFhirResource(patient);
    const text = JSON.stringify(s);
    expect(text).not.toContain(CPF);
    expect(text).not.toContain(CNS);
    expect(s.fields.find((f) => f.label === 'CPF')?.value).toBe('***.***.***-25');
    expect(
      summarizeFhirResource(observation).fields.find((f) => f.label === 'Resultado')?.value,
    ).toBe('126 mg/dL');
  });
});

describe('FHIRResourceViewer (modo identifiers, JSON colapsável)', () => {
  it('resumo por tipo, JSON colapsado por padrão e mascarado quando aberto; axe', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <FHIRResourceViewer
        resource={patient}
        masking="identifiers"
        collapsibleRaw
        initialView="json"
      />,
    );
    expect(
      screen.getByRole('heading', { name: /Paciente: Maria das Dores Silva/ }),
    ).toBeInTheDocument();
    expect(screen.getByText('***.***.***-25')).toBeInTheDocument();
    expect(screen.queryByLabelText('JSON do recurso')).not.toBeInTheDocument();
    const toggle = screen.getByRole('button', { name: 'Mostrar JSON' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const json = screen.getByLabelText('JSON do recurso');
    expect(json.textContent).not.toContain(CPF);
    expect(json.textContent).not.toContain(CNS);
    expect(json).toHaveTextContent('*** **** **** 7890');
    expect(container.textContent).not.toContain(CPF);
    await user.click(screen.getByRole('button', { name: 'Árvore' }));
    expect(
      within(screen.getByLabelText('Árvore do recurso')).queryByText(new RegExp(CNS)),
    ).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('dois viewers na mesma página não duplicam ids', () => {
    const { container } = render(
      <>
        <FHIRResourceViewer resource={patient} />
        <FHIRResourceViewer resource={observation} />
      </>,
    );
    const ids = [...container.querySelectorAll('[id]')].map((e) => e.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
