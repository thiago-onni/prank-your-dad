/** Máscaras de entrada para CPF/CNS (apenas formatação visual; valores nunca são logados). */
export function onlyDigits(value: string): string {
  return value.replace(/\D/g, '');
}

export function maskCpfInput(value: string): string {
  const d = onlyDigits(value).slice(0, 11);
  return d
    .replace(/^(\d{3})(\d)/, '$1.$2')
    .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/^(\d{3})\.(\d{3})\.(\d{3})(\d)/, '$1.$2.$3-$4');
}

export function maskCnsInput(value: string): string {
  const d = onlyDigits(value).slice(0, 15);
  return d
    .replace(/^(\d{3})(\d)/, '$1 $2')
    .replace(/^(\d{3}) (\d{4})(\d)/, '$1 $2 $3')
    .replace(/^(\d{3}) (\d{4}) (\d{4})(\d)/, '$1 $2 $3 $4');
}

export function isValidCpfLength(value: string): boolean {
  return onlyDigits(value).length === 11;
}

export function isValidCnsLength(value: string): boolean {
  return onlyDigits(value).length === 15;
}

/** Monta o parâmetro `identifier=system|value` da API. */
export function toIdentifierParam(system: 'CPF' | 'CNS', value: string): string {
  return `${system}|${onlyDigits(value)}`;
}
