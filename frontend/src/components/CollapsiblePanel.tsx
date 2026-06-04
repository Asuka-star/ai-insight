import type { ReactNode } from "react";
import { ChevronDown } from "lucide-react";

interface CollapsiblePanelProps {
  eyebrow: string;
  title: string;
  icon?: ReactNode;
  summary?: ReactNode;
  collapsed: boolean;
  onToggle: () => void;
  children: ReactNode;
  className?: string;
}

export function CollapsiblePanel({
  eyebrow,
  title,
  icon,
  summary,
  collapsed,
  onToggle,
  children,
  className
}: CollapsiblePanelProps) {
  return (
    <section className={`panel collapsible-panel ${collapsed ? "collapsed" : ""} ${className ?? ""}`}>
      <div className="section-title collapsible-title">
        <div>
          <p className="eyebrow">{eyebrow}</p>
          <h2>{title}</h2>
          {summary ? <small className="collapse-summary">{summary}</small> : null}
        </div>
        <div className="collapse-actions">
          {icon}
          <button
            className="collapse-toggle"
            type="button"
            aria-expanded={!collapsed}
            aria-label={collapsed ? `展开${title}` : `折叠${title}`}
            onClick={onToggle}
          >
            <ChevronDown size={16} />
          </button>
        </div>
      </div>
      {collapsed ? null : children}
    </section>
  );
}
