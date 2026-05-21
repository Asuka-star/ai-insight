interface StatusBadgeProps {
  label: string;
  tone?: "neutral" | "success" | "running" | "danger" | "warning";
}

export function StatusBadge({ label, tone = "neutral" }: StatusBadgeProps) {
  return <span className={`status-badge ${tone}`}>{label}</span>;
}
