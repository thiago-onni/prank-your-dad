import { parsePurposeCookie, purposesForRoles } from '@/lib/purpose';

describe('purpose', () => {
  it('valida cookie', () => {
    expect(parsePurposeCookie('care_coordination')).toBe('care_coordination');
    expect(parsePurposeCookie('hack')).toBeUndefined();
    expect(parsePurposeCookie(undefined)).toBeUndefined();
  });
  it('deriva finalidades por papel', () => {
    expect(purposesForRoles(['cadastrador'])).toEqual(['identity_management', 'scheduling']);
    expect(purposesForRoles(['admin'])).toHaveLength(9);
    expect(purposesForRoles(['desconhecido'])).toEqual([]);
  });
});
