'use client';

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient } from '../provider';
import type { Appointment, AppointmentStatus, Page } from '../types';

export interface AppointmentsParams {
  citizen_id?: string;
  cnes?: string;
  status?: AppointmentStatus;
  from?: string;
  to?: string;
  cursor?: string;
  limit?: number;
}

export function useAppointments(params: AppointmentsParams = {}) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.appointments(params),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<Appointment>> =>
      unwrap(
        await client.GET('/api/v1/appointments', {
          params: {
            query: {
              citizen_id: params.citizen_id || undefined,
              cnes: params.cnes || undefined,
              status: params.status,
              from: params.from || undefined,
              to: params.to || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 50,
            },
          },
        }),
      ),
  });
}
