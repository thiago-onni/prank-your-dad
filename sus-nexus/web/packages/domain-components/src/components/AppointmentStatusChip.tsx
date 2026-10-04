import type { AppointmentStatus } from '@sus-nexus/api-client';
import { Badge } from '@sus-nexus/design-system';
import { appointmentStatusLabels } from '../lib/labels';

export interface AppointmentStatusChipProps {
  status: AppointmentStatus;
  className?: string;
}

/** Estados de agenda padronizados (proposed … noshow/waitlist). */
export function AppointmentStatusChip({ status, className }: AppointmentStatusChipProps) {
  const meta = appointmentStatusLabels[status];
  return (
    <Badge tone={meta.tone} className={className} data-status={status}>
      {meta.label}
    </Badge>
  );
}
