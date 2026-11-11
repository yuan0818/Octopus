import type { ThemeConfig } from "antd";

/**
 * Octopus 设计令牌（唯一来源）。
 * - TSX 中的颜色一律引用 palette，不再写死十六进制；
 * - CSS 中使用 styles/tokens.css 的 --oct-* 变量（值与这里保持一致，tests/theme-tokens.test.mjs 会校验）；
 * - Ant Design 组件通过 antdTheme 获得同一套主色与语义色。
 */
export const palette = {
  primary: "#1677ff",
  primaryLight: "#69b1ff",
  primaryBorder: "#91caff",
  primaryBg: "#e6f4ff",
  success: "#52c41a",
  successText: "#389e0d",
  successBorder: "#b7eb8f",
  successBg: "#f6ffed",
  successLight: "#95de64",
  warning: "#faad14",
  warningBg: "#fff7e6",
  orange: "#fa8c16",
  volcano: "#ff7a45",
  error: "#ff4d4f",
  errorText: "#cf1322",
  errorBorder: "#ffccc7",
  errorBg: "#fff2f0",
  purple: "#722ed1",
  purpleBg: "#f9f0ff",
  cyan: "#13c2c2",
  cyanBg: "#e6fffb",
  text: "#333333",
  textSecondary: "#666666",
  textTertiary: "#999999",
  textQuaternary: "#cccccc",
  border: "#d9d9d9",
  borderSubtle: "#f0f0f0",
  bgSubtle: "#fafafa",
  bgHover: "#f5f5f5",
  bgContainer: "#ffffff",
  textOnDark: "#ffffff",
  codeBg: "#1e1e1e",
  codeText: "#d4d4d4",
  diffAddedBg: "#e6ffec",
  diffAddedText: "#1a7f37",
  diffRemovedBg: "#ffebe9",
  diffRemovedText: "#cf222e",
} as const;

export type PaletteKey = keyof typeof palette;

/** 分析任务 / 审查结果等状态的统一配色。 */
export const statusColor: Record<string, string> = {
  COMPLETED: palette.success,
  PASS: palette.success,
  RUNNING: palette.primary,
  QUEUED: palette.primary,
  FAILED: palette.error,
  FAIL: palette.error,
  PENDING: palette.border,
  SKIPPED: palette.warning,
  DEGRADED: palette.orange,
  INCONCLUSIVE: palette.warning,
};

/** Code Review 问题严重度配色（审查页与审查历史共用）。 */
export const severityColor: Record<string, string> = {
  BLOCKER: palette.error,
  CRITICAL: palette.volcano,
  WARNING: palette.warning,
  INFO: palette.primary,
};

export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: palette.primary,
    colorSuccess: palette.success,
    colorWarning: palette.warning,
    colorError: palette.error,
    colorInfo: palette.primary,
    colorTextBase: "#1f1f1f",
    borderRadius: 6,
    fontFamily: "var(--sans)",
  },
  components: {
    Layout: { headerBg: "rgba(255,255,255,0.86)" },
    Card: { borderRadiusLG: 8 },
  },
};
