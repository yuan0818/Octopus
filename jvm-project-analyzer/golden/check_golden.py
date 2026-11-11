#!/usr/bin/env python3
"""PSI 抽取 golden 检查（Phase 2.6）。

用法：
  # 对已启动的分析服务（./run-jvm-analyzer-local.sh，默认 8377）
  python3 jvm-project-analyzer/golden/check_golden.py --url http://localhost:8377
  # 或检查一次性模式输出的 JSON
  python3 jvm-project-analyzer/golden/check_golden.py --json output.json

required 预期缺失 → 退出码 1；known_gap 只报告（若已满足会提示"可升级为 required"）。只用标准库。
"""
import argparse
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def method_matches(signature, method):
    """签名格式 'pkg.Class.method(params):ret'；预期写 'pkg.Class.method'，构造函数可写 '<init>'。"""
    if not isinstance(signature, str):
        return False
    return signature.startswith(method + "(") or signature == method


def any_sig(values, method):
    return any(method_matches(v, method) for v in (values or []))


def check(data, exp):
    results = []

    def record(kind, item, ok, detail=""):
        results.append({"kind": kind, "status": item.get("status", "required"), "ok": ok,
                        "item": item, "detail": detail})

    beans = data.get("beans") or []
    for item in exp.get("beans", []):
        match = [b for b in beans if item["className"] in (b.get("beanClassName"), b.get("className"))]
        ok = bool(match)
        if ok and item.get("interfaceIncludes"):
            ok = any(item["interfaceIncludes"] in (b.get("interfaceNames") or []) for b in match)
        record("bean", item, ok)

    edges = data.get("edges") or []
    for item in exp.get("edges", []):
        callees = [item["callee"]] + item.get("calleeAlternatives", [])
        def edge_ok(e):
            if not method_matches(e.get("callerSignature"), item["caller"]):
                return False
            callee = e.get("calleeSignature") or ""
            if not any(method_matches(callee, c) or callee.startswith(c) for c in callees):
                return False
            return not item.get("callType") or e.get("callType") == item["callType"]
        record("edge", item, any(edge_ok(e) for e in edges))

    for item in exp.get("asyncBindings", []):
        ok = any(method_matches(b.get("asyncMethodSignature"), item["asyncMethod"])
                 and (not item.get("callerIncludes") or any_sig(b.get("callerMethodSignatures"), item["callerIncludes"]))
                 for b in data.get("asyncBindings") or [])
        record("async", item, ok)

    for item in exp.get("scheduledBindings", []):
        ok = any(method_matches(b.get("methodSignature"), item["method"])
                 and (not item.get("cronExpression") or b.get("cronExpression") == item["cronExpression"])
                 for b in data.get("scheduledBindings") or [])
        record("scheduled", item, ok)

    for item in exp.get("eventBindings", []):
        def event_ok(b):
            if not any_sig(b.get("listenerMethodSignatures"), item["listener"]):
                return False
            if item.get("eventType") and b.get("eventType") != item["eventType"]:
                return False
            return not item.get("publisherIncludes") or any_sig(b.get("publisherMethodSignatures"), item["publisherIncludes"])
        bindings = data.get("eventBindings") or []
        detail = "实际 eventType: " + ", ".join(repr(b.get("eventType")) for b in bindings)
        record("event", item, any(event_ok(b) for b in bindings), detail)

    for item in exp.get("repositoryBindings", []):
        ok = any(b.get("repositoryInterfaceName") == item["repository"]
                 and (not item.get("domainClass") or b.get("domainClassName") == item["domainClass"])
                 and (not item.get("callerIncludes") or any_sig(b.get("callerMethodSignatures"), item["callerIncludes"]))
                 for b in data.get("repositoryBindings") or [])
        record("repository", item, ok)

    for item in exp.get("aopBindings", []):
        def aop_ok(b):
            if b.get("aspectClassName") != item["aspectClass"]:
                return False
            if item.get("adviceType") and b.get("adviceType") != item["adviceType"]:
                return False
            if item.get("pointcutIncludes") and item["pointcutIncludes"] not in (b.get("pointcutExpression") or ""):
                return False
            return not item.get("matchedIncludes") or any_sig(b.get("matchedMethodSignatures"), item["matchedIncludes"])
        record("aop", item, any(aop_ok(b) for b in data.get("aopBindings") or []))
    return results


def load(args, project_dir):
    if args.json:
        with open(args.json, encoding="utf-8") as f:
            payload = json.load(f)
    else:
        body = json.dumps({"projectDir": project_dir}).encode("utf-8")
        request = urllib.request.Request(args.url.rstrip("/") + "/analyze", data=body,
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            payload = json.load(response)
    if "success" in payload:
        if not payload.get("success"):
            raise SystemExit("analysis failed: " + str(payload.get("error")))
        payload = payload.get("data") or {}
    return payload


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:8377")
    parser.add_argument("--json", help="使用一次性模式输出的 JSON 文件而不是调用服务")
    parser.add_argument("--project", default=os.path.join(HERE, "spring-shop"))
    parser.add_argument("--expectations", default=os.path.join(HERE, "spring-shop.expectations.json"))
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    with open(args.expectations, encoding="utf-8") as f:
        expectations = json.load(f)
    results = check(load(args, os.path.abspath(args.project)), expectations)
    failed = [r for r in results if not r["ok"] and r["status"] == "required"]
    for r in results:
        mark = "PASS" if r["ok"] else ("FAIL" if r["status"] == "required" else "GAP ")
        target = r["item"].get("caller") or r["item"].get("className") or r["item"].get("listener") or \
            r["item"].get("asyncMethod") or r["item"].get("method") or r["item"].get("repository") or r["item"].get("aspectClass")
        line = f"[{mark}] {r['kind']:<10} {target}"
        if not r["ok"] and r["detail"]:
            line += f"  ({r['detail']})"
        if r["ok"] and r["status"] == "known_gap":
            line += "  ← 已满足，可将 status 升级为 required"
        print(line)
    print(f"\n{len(results) - len(failed)}/{len(results)} expectations satisfied; {len(failed)} required failure(s)")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
