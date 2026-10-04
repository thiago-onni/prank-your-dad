import { TasksPage } from '@/features/tarefas/TasksPage';
import { t } from '@/i18n';

export const metadata = { title: t.tasks.title };

export default function Page() {
  return <TasksPage />;
}
