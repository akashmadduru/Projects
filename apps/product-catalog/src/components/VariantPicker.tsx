/**
 * Variant selection: one radio group per option (size, colour, ...). Values that cannot lead to an
 * available variant (given the other selections) are disabled.
 */

import type { ProductVariant } from '../types'

interface Props {
  options: Record<string, string[]>
  variants: ProductVariant[]
  selected: Record<string, string>
  onSelect: (key: string, value: string) => void
}

export function isVariantAvailable(v: ProductVariant): boolean {
  return v.active && (v.availableQuantity == null || v.availableQuantity > 0)
}

export function VariantPicker({ options, variants, selected, onSelect }: Props) {
  return (
    <div className="space-y-4">
      {Object.entries(options).map(([key, values]) => (
        <fieldset key={key}>
          <legend className="mb-2 text-sm font-bold text-on-surface">
            {key}
            {selected[key] && <span className="ml-2 font-normal text-on-surface-muted">{selected[key]}</span>}
          </legend>
          <div className="flex flex-wrap gap-2">
            {values.map((value) => {
              // Available if some variant has this value and matches every other selected option.
              const reachable = variants.some(
                (v) =>
                  v.attributes[key] === value &&
                  isVariantAvailable(v) &&
                  Object.entries(selected).every(([k, sel]) => k === key || v.attributes[k] === sel),
              )
              const checked = selected[key] === value
              return (
                <label
                  key={value}
                  className={`press relative cursor-pointer rounded-full border-2 px-4 py-1.5 text-sm font-bold transition-all duration-500 focus-within:ring-2 focus-within:ring-primary focus-within:ring-offset-2 focus-within:ring-offset-surface ${
                    checked
                      ? 'border-ink bg-brand-gradient text-on-brand shadow-[2px_2px_0_hsl(var(--color-ink))]'
                      : reachable
                        ? 'border-outline bg-surface text-on-surface hover:border-ink'
                        : 'cursor-not-allowed border-dashed border-outline/60 text-on-surface-muted line-through opacity-60'
                  }`}
                >
                  <input
                    type="radio"
                    name={`variant-${key}`}
                    value={value}
                    checked={checked}
                    disabled={!reachable && !checked}
                    onChange={() => onSelect(key, value)}
                    className="sr-only"
                  />
                  {value}
                  {!reachable && <span className="sr-only"> (unavailable)</span>}
                </label>
              )
            })}
          </div>
        </fieldset>
      ))}
    </div>
  )
}
