import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { Button } from '../Button';

describe('Button', () => {
  it('dispara onClick e é acessível', async () => {
    const onClick = vi.fn();
    const { container } = render(<Button onClick={onClick}>Salvar</Button>);
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('em loading fica desabilitado e com aria-busy', () => {
    render(<Button loading>Enviando</Button>);
    const btn = screen.getByRole('button', { name: 'Enviando' });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('aria-busy', 'true');
  });

  it('usa type="button" por padrão', () => {
    render(<Button>Ok</Button>);
    expect(screen.getByRole('button')).toHaveAttribute('type', 'button');
  });
});
