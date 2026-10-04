import {
  isValidCnsLength,
  isValidCpfLength,
  maskCnsInput,
  maskCpfInput,
  toIdentifierParam,
} from '@/lib/masks';

describe('masks', () => {
  it('aplica máscara de CPF progressivamente', () => {
    expect(maskCpfInput('123')).toBe('123');
    expect(maskCpfInput('1234')).toBe('123.4');
    expect(maskCpfInput('12345678912')).toBe('123.456.789-12');
    expect(maskCpfInput('123456789123456')).toBe('123.456.789-12');
  });
  it('aplica máscara de CNS', () => {
    expect(maskCnsInput('898001234567890')).toBe('898 0012 3456 7890');
  });
  it('valida tamanho e monta identifier', () => {
    expect(isValidCpfLength('123.456.789-12')).toBe(true);
    expect(isValidCnsLength('898 0012 3456 789')).toBe(false);
    expect(toIdentifierParam('CPF', '123.456.789-12')).toBe('CPF|12345678912');
  });
});
