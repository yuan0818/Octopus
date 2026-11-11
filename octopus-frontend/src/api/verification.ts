import axios from "axios";
import type { Binding } from "./businessTesting";
const http = axios.create({ baseURL: "/business-testing/api/business-testing/verifications", timeout: 270000 });
export interface VerificationPlan {
  kind: "SQL" | "REDIS" | "KAFKA";
  connection: { repositoryKey: string; modulePath: string; connectionId: string };
  description: string;
  sql: { table: string; columns: string[]; count: boolean; where: { column: string; operator: string; value: Binding }[] } | null;
  redis: { command: string; keyPrefix: string; key: Binding; field: string | null } | null;
  kafka: { topic: string; key: Binding } | null;
  expect: { path: string; operator: string; value: Binding | null };
  timeoutMillis: number;
}
export interface VerificationSuggestion { plan: VerificationPlan | null; preview: string; questions: string[]; assumptions: string[]; model: string }
export const verificationApi = {
  suggest: (body: { source: VerificationPlan["connection"]; environment: string; description: string; variables: Record<string,string>; model: string }) =>
    http.post<VerificationSuggestion>("/suggest", body).then(r => r.data),
  preview: (plan: VerificationPlan, environment: string) => http.post<{ preview: string }>("/preview", { plan, environment }).then(r => r.data),
};
