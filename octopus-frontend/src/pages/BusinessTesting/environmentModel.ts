export const presetEnvironments = ["test", "staging", "production"];
const labels: Record<string, string> = { test: "测试环境", staging: "预发布环境", production: "生产环境" };
export const environmentLabel = (value: string) => labels[value] || value;
export function environmentOptions(values: string[] = []) {
  return [...new Set([...presetEnvironments, ...values].filter(Boolean))].map(value => ({ value, label: environmentLabel(value) }));
}
