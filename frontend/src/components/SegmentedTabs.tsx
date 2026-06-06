export interface SegmentedTabOption<T extends string> {
  label: string;
  value: T;
}

interface SegmentedTabsProps<T extends string> {
  ariaLabel: string;
  options: Array<SegmentedTabOption<T>>;
  value: T;
  onChange: (value: T) => void;
  className?: string;
}

export function SegmentedTabs<T extends string>({
  ariaLabel,
  options,
  value,
  onChange,
  className
}: SegmentedTabsProps<T>) {
  return (
    <div className={className ? `segmented-tabs ${className}` : "segmented-tabs"} role="tablist" aria-label={ariaLabel}>
      {options.map((option) => {
        const selected = value === option.value;
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={selected}
            className={selected ? "selected" : ""}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
