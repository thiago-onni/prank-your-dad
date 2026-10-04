export function GET(): Response {
  return Response.json({ status: 'ok', mock: process.env.NEXT_PUBLIC_API_MOCK === 'true' });
}
