import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CursorPagination } from '../Pagination';

describe('CursorPagination', () => {
  it('habilita próxima apenas com cursor', async () => {
    const onNext = vi.fn();
    const { rerender } = render(<CursorPagination nextCursor={null} onNext={onNext} />);
    expect(screen.getByRole('button', { name: 'Próxima página' })).toBeDisabled();
    rerender(<CursorPagination nextCursor="abc" onNext={onNext} />);
    await userEvent.click(screen.getByRole('button', { name: 'Próxima página' }));
    expect(onNext).toHaveBeenCalledWith('abc');
  });
});
