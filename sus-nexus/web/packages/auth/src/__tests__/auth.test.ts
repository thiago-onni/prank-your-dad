import { hasRole, toPublicSession, ROLES } from '../types';
import { readAuthEnv } from '../config';
import { decodeJwtPayload, extractRoles, extractScope } from '../jwt';

describe('hasRole', () => {
  it('admin passa em qualquer verificação', () => {
    expect(hasRole({ roles: ['admin'] }, ROLES.REGULADOR)).toBe(true);
    expect(hasRole({ roles: ['acs'] }, ROLES.REGULADOR)).toBe(false);
    expect(hasRole({ roles: ['acs', 'regulador'] }, ROLES.REGULADOR)).toBe(true);
    expect(hasRole(null, ROLES.REGULADOR)).toBe(false);
  });
});

describe('toPublicSession', () => {
  it('remove accessToken', () => {
    const pub = toPublicSession({
      user: { id: 'u1', name: 'Ana' },
      roles: ['acs'],
      expires: '2026-01-01T00:00:00Z',
      accessToken: 'secret',
    });
    expect(pub).not.toHaveProperty('accessToken');
    expect(JSON.stringify(pub)).not.toContain('secret');
  });
});

describe('readAuthEnv', () => {
  it('modo mock lê papéis por env', () => {
    const env = readAuthEnv({ AUTH_MODE: 'mock', AUTH_MOCK_ROLES: 'acs, regulador' });
    expect(env.mode).toBe('mock');
    expect(env.mock.roles).toEqual(['acs', 'regulador']);
  });
  it('modo mock lê a lotação (cnes/equipes/microáreas)', () => {
    const env = readAuthEnv({
      AUTH_MODE: 'mock',
      AUTH_MOCK_CNES: '2126672',
      AUTH_MOCK_MICROAREAS: '01, 02',
    });
    expect(env.mock.cnes).toEqual(['2126672']);
    expect(env.mock.teams).toEqual([]);
    expect(env.mock.microareas).toEqual(['01', '02']);
  });
  it('mock em produção é bloqueado', () => {
    expect(() => readAuthEnv({ AUTH_MODE: 'mock', NODE_ENV: 'production' })).toThrow();
  });
  it('keycloak exige AUTH_SECRET', () => {
    expect(() => readAuthEnv({ AUTH_MODE: 'keycloak' })).toThrow();
  });
});

describe('jwt', () => {
  it('extrai papéis do realm e do cliente', () => {
    const payload = {
      sub: 'abc',
      realm_access: { roles: ['regulador'] },
      resource_access: { 'sus-nexus-web': { roles: ['gestor'] } },
    };
    const token = `h.${Buffer.from(JSON.stringify(payload)).toString('base64url')}.s`;
    const decoded = decodeJwtPayload(token);
    expect(decoded.sub).toBe('abc');
    expect(extractRoles(decoded, 'sus-nexus-web').sort()).toEqual(['gestor', 'regulador']);
  });
});

describe('extractScope', () => {
  it('lê cnes/teams/microareas como listas e ignora valores inválidos', () => {
    expect(
      extractScope({ cnes: '2126672', teams: ['ine_1', 3], microareas: ['01', '02'] }),
    ).toEqual({ cnes: ['2126672'], teams: ['ine_1'], microareas: ['01', '02'] });
    expect(extractScope({ cnes: [], teams: 'x'.slice(1) })).toEqual({});
  });
});
