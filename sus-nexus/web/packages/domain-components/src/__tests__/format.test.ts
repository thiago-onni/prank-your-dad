import { calculateAge, formatCns, formatCpf, maskCns, maskCpf } from '../lib/format';

describe('format', () => {
  it('mascara CPF e CNS mantendo apenas o final', () => {
    expect(maskCpf('12345678912')).toBe('***.***.***-12');
    expect(maskCns('898001234567890')).toBe('*** **** **** 7890');
  });
  it('formata CPF e CNS em claro', () => {
    expect(formatCpf('12345678912')).toBe('123.456.789-12');
    expect(formatCns('898001234567890')).toBe('898 0012 3456 7890');
  });
  it('calcula idade', () => {
    expect(calculateAge('1985-03-15', new Date('2026-10-04T12:00:00'))).toBe(41);
    expect(calculateAge(undefined)).toBeUndefined();
  });
});
