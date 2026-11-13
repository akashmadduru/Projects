/** Header language + display-currency switchers. */

import { DISPLAY_CURRENCIES, useCurrency, useFxLoader } from '../lib/currency'
import { LANGS, useLocale, useT, type Lang } from '../lib/i18n'

const select =
  'rounded-full border-2 border-transparent bg-transparent px-2 py-1 text-xs font-bold text-on-brand hover:border-on-brand/40 focus-visible:border-on-brand [&>option]:bg-surface [&>option]:text-on-surface'

export function PreferencesSwitcher() {
  const t = useT()
  const lang = useLocale((s) => s.lang)
  const setLang = useLocale((s) => s.setLang)
  const display = useCurrency((s) => s.display)
  const setDisplay = useCurrency((s) => s.setDisplay)
  useFxLoader()

  return (
    <div className="hidden items-center gap-1 md:flex">
      <select aria-label={t('Language')} className={select} value={lang} onChange={(e) => setLang(e.target.value as Lang)}>
        {LANGS.map((l) => (
          <option key={l.code} value={l.code}>
            {l.label}
          </option>
        ))}
      </select>
      <select aria-label={t('Currency')} className={select} value={display} onChange={(e) => setDisplay(e.target.value)}>
        {DISPLAY_CURRENCIES.map((c) => (
          <option key={c} value={c}>
            {c === 'NATIVE' ? 'Native' : c}
          </option>
        ))}
      </select>
    </div>
  )
}
