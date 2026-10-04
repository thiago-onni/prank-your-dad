import type { components, operations, paths } from './generated/core';

export type { components, operations, paths };

type Schemas = components['schemas'];

export type Problem = Schemas['Problem'];
export type Purpose = Schemas['Purpose'];
export type Domain = Schemas['Domain'];
export type RegistrationState = Schemas['RegistrationState'];
export type IdentifierSystem = Schemas['IdentifierSystem'];
export type MaskedIdentifier = Schemas['MaskedIdentifier'];
export type Provenance = Schemas['Provenance'];
export type CitizenSummary = Schemas['CitizenSummary'];
export type CitizenDetail = Schemas['CitizenDetail'];
export type CitizenRegistration = Schemas['CitizenRegistration'];
export type IdentityResolution = Schemas['IdentityResolution'];
export type MergeCaseStatus = Schemas['MergeCaseStatus'];
export type MergeCase = Schemas['MergeCase'];
export type AppointmentStatus = Schemas['AppointmentStatus'];
export type Appointment = Schemas['Appointment'];
export type AppointmentDuplicate = Schemas['AppointmentDuplicate'];
export type TaskStatus = Schemas['TaskStatus'];
export type Assignee = Schemas['Assignee'];
export type Task = Schemas['Task'];
export type TaskCreate = Schemas['TaskCreate'];
export type ConnectorStatus = Schemas['ConnectorStatus'];
export type IntegrationMessageStatus = Schemas['IntegrationMessageStatus'];
export type IntegrationMessage = Schemas['IntegrationMessage'];
export type DeadLetter = Schemas['DeadLetter'];
export type ReconciliationEntry = Schemas['ReconciliationEntry'];
export type Code = Schemas['Code'];
export type HealthUnit = Schemas['HealthUnit'];
export type TimelineEvent = Schemas['TimelineEvent'];
export type CitizenOperationalSummary = Schemas['CitizenOperationalSummary'];
export type AccessLogEntry = Schemas['AccessLogEntry'];
export type RuleSet = Schemas['RuleSet'];
export type RegulationRequest = Schemas['RegulationRequest'];
export type RegulationIssue = Schemas['RegulationIssue'];
export type RegulationStatus = Schemas['RegulationStatus'];
export type RegulationPriority = Schemas['RegulationPriority'];
export type RegulationKind = Schemas['RegulationKind'];
export type RegulationQueueItem = Schemas['RegulationQueueItem'];
export type ProviderCapacity = Schemas['ProviderCapacity'];
export type ExamOrder = Schemas['ExamOrder'];
export type ExamOrderStatus = Schemas['ExamOrderStatus'];
export type ExamResult = Schemas['ExamResult'];

export type IdentityConfidence = NonNullable<CitizenSummary['identity_confidence']>;
export type TimelineConfidence = NonNullable<TimelineEvent['confidence']>;
export type ConnectorHealth = ConnectorStatus['health'];
export type TaskPriority = Task['priority'];
export type TaskTransitionAction = NonNullable<
  operations['transitionTask']['requestBody']
>['content']['application/json']['action'];

type RegulationListQuery = NonNullable<operations['listRegulationRequests']['parameters']['query']>;
/** Pendências consultáveis na fila (REG-005). */
export type RegulationIssueFilter = NonNullable<RegulationListQuery['issue']>;
export type RegulationSort = NonNullable<RegulationListQuery['sort']>;
export type RegulationQueueGroupBy = NonNullable<
  NonNullable<operations['getRegulationQueueSummary']['parameters']['query']>['group_by']
>;
export type RegulationIssueKind = RegulationIssue['kind'];
export type RegulationIssueInput = NonNullable<
  operations['addRegulationIssue']['requestBody']
>['content']['application/json'];
/** Tipos de pendência que um humano pode abrir (subconjunto de `RegulationIssueKind`). */
export type RegulationIssueKindInput = RegulationIssueInput['kind'];
export type RegulationStatusHistoryEntry = NonNullable<RegulationRequest['status_history']>[number];

export type ExamIssue = NonNullable<ExamOrder['issues']>[number];
export type ExamResultStatus = ExamResult['status'];
export type ExamStatusHistoryEntry = NonNullable<ExamOrder['status_history']>[number];
export type ExamCycleTimes = NonNullable<ExamOrder['cycle_times']>;
/** Referência segura (URL assinada, curta) ao laudo. Nunca armazenar em cache. */
export type ExamResultDocument =
  operations['getExamResultDocument']['responses'][200]['content']['application/json'];

/** Página paginada por cursor opaco. */
export interface Page<T> {
  items: T[];
  next_cursor?: string | null;
}

export const PURPOSES = [
  'care_coordination',
  'regulation',
  'scheduling',
  'identity_management',
  'production_audit',
  'public_health_surveillance',
  'management_analytics',
  'integration_operations',
  'security_audit',
] as const satisfies readonly Purpose[];

export const DOMAINS = [
  'identity',
  'aps',
  'schedule',
  'regulation',
  'exam',
  'hospital',
  'careplan',
  'task',
  'production',
  'communication',
] as const satisfies readonly Domain[];

export function isPurpose(value: unknown): value is Purpose {
  return typeof value === 'string' && (PURPOSES as readonly string[]).includes(value);
}
