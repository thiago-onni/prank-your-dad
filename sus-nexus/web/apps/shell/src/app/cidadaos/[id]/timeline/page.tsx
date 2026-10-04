import { redirect } from 'next/navigation';

/** Rota canônica da convenção (`/cidadaos/[id]/timeline`) — a aba timeline vive na página do cidadão. */
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  redirect(`/cidadaos/${id}#timeline`);
}
