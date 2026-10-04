import { t } from '@/i18n';

export function SkipLink() {
  return (
    <a
      href="#conteudo"
      className="sr-only-focusable fixed left-2 top-2 z-[200] rounded-md bg-primary px-4 py-2 font-semibold text-fg-on-primary"
    >
      {t.app.skipToContent}
    </a>
  );
}
