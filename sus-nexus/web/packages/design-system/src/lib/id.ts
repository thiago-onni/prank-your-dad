import { useId } from 'react';

/** Gera ids estáveis para associação label/descrição/erro. */
export function useFieldIds(prefix = 'field') {
  const base = useId();
  return {
    inputId: `${prefix}-${base}`,
    descriptionId: `${prefix}-${base}-desc`,
    errorId: `${prefix}-${base}-err`,
  };
}
