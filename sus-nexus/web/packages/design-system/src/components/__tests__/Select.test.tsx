import { render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { Select } from '../Select';

describe('Select', () => {
  it('tem rótulo acessível e erro associado', async () => {
    const { container } = render(
      <Select
        label="Finalidade"
        options={[{ value: 'care_coordination', label: 'Coordenação do cuidado' }]}
        error="Obrigatório"
      />,
    );
    const trigger = screen.getByRole('combobox', { name: /Finalidade/ });
    expect(trigger).toHaveAttribute('aria-invalid', 'true');
    expect(await axe(container)).toHaveNoViolations();
  });
});
