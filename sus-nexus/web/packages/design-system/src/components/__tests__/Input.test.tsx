import { render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { Input } from '../Input';

describe('Input', () => {
  it('associa rótulo, descrição e erro', async () => {
    const { container } = render(
      <Input label="CPF" description="Somente números" error="CPF inválido" required />,
    );
    const input = screen.getByLabelText(/CPF/);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(expect.stringContaining('Somente números'));
    expect(screen.getByRole('alert')).toHaveTextContent('CPF inválido');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('hideLabel mantém o rótulo para leitores de tela', () => {
    render(<Input label="Buscar" hideLabel />);
    expect(screen.getByLabelText('Buscar')).toBeInTheDocument();
  });
});
