import { render, screen } from '@testing-library/react';
import { FHIRResourceViewer, maskResource } from '../components/FHIRResourceViewer';
import { SENSITIVE_KEYS } from '../lib/format';

const patient = {
  resourceType: 'Patient',
  id: 'pat-1',
  identifier: [{ system: 'urn:oid:2.16.840.1.113883.13.237', value: '898001234567890' }],
  name: [{ family: 'Silva', given: ['Maria'] }],
  birthDate: '1985-03-15',
  gender: 'female',
};

describe('FHIRResourceViewer', () => {
  it('mascara valores sensíveis e preserva metadados estruturais', () => {
    const masked = maskResource(patient, SENSITIVE_KEYS) as typeof patient;
    expect(masked.resourceType).toBe('Patient');
    expect(masked.identifier[0]?.system).toBe('urn:oid:2.16.840.1.113883.13.237');
    expect(masked.identifier[0]?.value).not.toBe('898001234567890');
    expect(masked.name[0]?.family).not.toBe('Silva');
    expect(masked.birthDate).not.toBe('1985-03-15');
    expect(masked.gender).toBe('female');
  });

  it('não renderiza o valor sensível em claro em nenhum modo', () => {
    render(<FHIRResourceViewer resource={patient} profile="br-core-patient" initialView="json" />);
    expect(screen.queryByText(/898001234567890/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('JSON do recurso')).toHaveTextContent('Patient');
  });
});
