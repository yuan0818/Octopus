import type { Binding } from "../../api/businessTesting";
import type { VerificationPlan } from "../../api/verification";
export function verificationValues(p: VerificationPlan): Binding[] {
  return [...(p.sql?.where.map(f => f.value) || []), ...(p.redis ? [p.redis.key] : []), ...(p.kafka ? [p.kafka.key] : []), ...(p.expect.value ? [p.expect.value] : [])];
}
