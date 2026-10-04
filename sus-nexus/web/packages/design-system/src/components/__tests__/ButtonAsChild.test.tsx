import { render, screen } from '@testing-library/react';
import { Button } from '../Button';

describe('Button asChild', () => {
  it('renderiza o filho como raiz (ex.: link) com as classes do botão', () => {
    render(
      <Button asChild variant="secondary">
        <a href="/inicio">Início</a>
      </Button>,
    );
    const link = screen.getByRole('link', { name: 'Início' });
    expect(link).toHaveAttribute('href', '/inicio');
    expect(link.className).toContain('rounded-full');
  });
});
