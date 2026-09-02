package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fixtureDir(t *testing.T) string {
	t.Helper()
	abs, err := filepath.Abs(filepath.Join("testdata", "demo"))
	if err != nil {
		t.Fatal(err)
	}
	return abs
}

func analyzeFixture(t *testing.T) *AnalyzeResult {
	t.Helper()
	result, warnings, err := Analyze(fixtureDir(t))
	if err != nil {
		t.Fatalf("Analyze failed: %v (warnings: %v)", err, warnings)
	}
	return result
}

func nodeSigs(result *AnalyzeResult) map[string]FuncNode {
	out := map[string]FuncNode{}
	for _, m := range result.Methods {
		out[m.Signature] = m
	}
	return out
}

func edgeSet(result *AnalyzeResult) map[string]CallEdge {
	out := map[string]CallEdge{}
	for _, e := range result.Edges {
		out[e.CallerSignature+" -> "+e.CalleeSignature] = e
	}
	return out
}

func TestMethodsIncludeFreeFunctionsAndMethods(t *testing.T) {
	sigs := nodeSigs(analyzeFixture(t))

	want := []string{
		// Free function: qualifiedClassName is the package path.
		"demo.example.com/demo/api.HandleRequest(context.Context,string):error",
		"demo.example.com/demo/api.cleanup():",
		"demo.example.com/demo/service.Format(*model.User):string",
		// Methods: pkgPath.Type.name.
		"demo.example.com/demo/model.User.DisplayName():string",
		"demo.example.com/demo/service.Service.GetUser(context.Context,string):*model.User,error",
		"demo.example.com/demo/store.DbStore.Get(context.Context,string):*model.User,error",
		"demo.example.com/demo/store.validateID(string):error",
		"demo.example.com/demo/store.Base.Hello():string",
	}
	for _, sig := range want {
		node, ok := sigs[sig]
		if !ok {
			t.Errorf("missing method node %q; have %v", sig, keysOf(sigs))
			continue
		}
		if node.SourceCode == "" {
			t.Errorf("node %q has empty source code", sig)
		}
		if node.LineStart <= 0 || node.LineEnd < node.LineStart {
			t.Errorf("node %q has bad line range %d-%d", sig, node.LineStart, node.LineEnd)
		}
	}
}

func TestInterfaceDispatchFansOutToImplementations(t *testing.T) {
	edges := edgeSet(analyzeFixture(t))

	// s.Store.Get(...) in Service.GetUser must fan out to DbStore.Get (DIRECT).
	fanOut := "demo.example.com/demo/service.Service.GetUser(context.Context,string):*model.User,error -> demo.example.com/demo/store.DbStore.Get(context.Context,string):*model.User,error"
	if e, ok := edges[fanOut]; !ok {
		t.Errorf("missing interface fan-out edge %q; have %v", fanOut, keysOf(edges))
	} else if e.CallType != "DIRECT" {
		t.Errorf("fan-out edge should be DIRECT, got %s", e.CallType)
	}
}

func TestUnimplementedInterfaceKeepsInterfaceEdge(t *testing.T) {
	edges := edgeSet(analyzeFixture(t))

	// l.Log(msg) with no workspace implementation → INTERFACE edge to Logger.Log node.
	ifaceEdge := "demo.example.com/demo/service.Service.Warn(service.Logger,string): -> demo.example.com/demo/service.Logger.Log(string):"
	if e, ok := edges[ifaceEdge]; !ok {
		t.Errorf("missing INTERFACE edge %q; have %v", ifaceEdge, keysOf(edges))
	} else if e.CallType != "INTERFACE" {
		t.Errorf("edge should be INTERFACE, got %s", e.CallType)
	}
}

func TestGoroutineAndDeferProduceEdges(t *testing.T) {
	edges := edgeSet(analyzeFixture(t))

	goEdge := "demo.example.com/demo/api.HandleRequest(context.Context,string):error -> demo.example.com/demo/api.cleanup():"
	if _, ok := edges[goEdge]; !ok {
		t.Errorf("missing defer/go cleanup edge %q; have %v", goEdge, keysOf(edges))
	}
}

func TestStdlibEdgesAreDropped(t *testing.T) {
	edges := edgeSet(analyzeFixture(t))
	for key := range edges {
		if strings.Contains(key, " fmt.") || strings.Contains(key, "errors.") {
			t.Errorf("non-workspace edge leaked: %q", key)
		}
	}
}

func TestHttpRoutesDetected(t *testing.T) {
	result := analyzeFixture(t)

	routes := map[string]HttpRoute{}
	for _, r := range result.HttpRoutes {
		routes[r.Path] = r
	}

	users, ok := routes["/api/users"]
	if !ok {
		t.Fatalf("route /api/users missing; have %+v", result.HttpRoutes)
	}
	if users.Method != "*" {
		t.Errorf("/api/users method = %q, want * (unrestricted)", users.Method)
	}
	if users.HandlerSignature != "demo.example.com/demo/api.handleUsers(http.ResponseWriter,*http.Request):" {
		t.Errorf("/api/users handler = %q", users.HandlerSignature)
	}

	orders, ok := routes["/api/orders"]
	if !ok {
		t.Fatalf("route /api/orders missing; have %+v", result.HttpRoutes)
	}
	if orders.Method != "POST" {
		t.Errorf("/api/orders method = %q, want POST (1.22 pattern)", orders.Method)
	}
}

func TestEmbeddedPromotionResolvesToBaseMethod(t *testing.T) {
	// DbStore embeds Base; a promoted call resolves to Base's method.
	result := analyzeFixture(t)
	var dbStore TypeSpec
	found := false
	for _, ts := range result.Types {
		if ts.QualifiedName == "demo.example.com/demo/store.DbStore" {
			dbStore = ts
			found = true
		}
	}
	if !found {
		t.Fatalf("DbStore type spec missing: %v", result.Types)
	}
	if dbStore.Embedded != "demo.example.com/demo/store.Base" {
		t.Errorf("DbStore.Embedded = %q, want store.Base qname", dbStore.Embedded)
	}
}

func TestImplementsIsComputed(t *testing.T) {
	result := analyzeFixture(t)
	var dbStore TypeSpec
	for _, ts := range result.Types {
		if ts.QualifiedName == "demo.example.com/demo/store.DbStore" {
			dbStore = ts
		}
	}
	found := false
	for _, iface := range dbStore.Implements {
		if iface == "demo.example.com/demo/store.Store" {
			found = true
		}
	}
	if !found {
		t.Errorf("DbStore.Implements = %v, want to contain store.Store", dbStore.Implements)
	}
}

func TestJSONContractShape(t *testing.T) {
	result := analyzeFixture(t)
	b, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(b, &raw); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"types", "methods", "edges"} {
		if _, ok := raw[key]; !ok {
			t.Errorf("JSON payload missing key %q", key)
		}
	}
}

func TestNoModuleFound(t *testing.T) {
	dir := t.TempDir()
	_, _, err := Analyze(dir)
	if err == nil {
		t.Fatal("expected error for directory without go.mod")
	}
}

// TestModuleWithoutTypesCoversNullSlices guards the JSON contract: a module
// with only free functions (no struct/interface) must still emit non-null
// types/edges/httpRoutes arrays, or the Java consumer NPEs on deserialization.
func TestModuleWithoutTypesCoversNullSlices(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "go.mod"), []byte("module bare.example.com/x\n\ngo 1.21\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "main.go"), []byte("package x\n\nfunc Only() int { return 1 }\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	result, _, err := Analyze(dir)
	if err != nil {
		t.Fatalf("Analyze failed: %v", err)
	}
	if result.Types == nil || result.Edges == nil || result.HttpRoutes == nil || result.Methods == nil {
		t.Fatalf("nil slice in result: types=%v edges=%v httpRoutes=%v methods=%v",
			result.Types, result.Edges, result.HttpRoutes, result.Methods)
	}
}

func keysOf[V any](m map[string]V) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}

func TestMain(m *testing.M) {
	// Ensure the fixture module does not trip on toolchain resolution when
	// offline: the fixture uses stdlib only.
	os.Exit(m.Run())
}

// TestRouteGroupPrefixesAndConfigurableWhitelist 验证两项框架画像改进：
// 1) 路由组前缀拼接（嵌套 Group / 链式 Group / 无前缀混合）
// 2) 白名单可通过 Options 配置（白名单外框架默认不识别，配置后识别）
func TestRouteGroupPrefixesAndConfigurableWhitelist(t *testing.T) {
	dir, err := filepath.Abs(filepath.Join("testdata", "ginapp"))
	if err != nil {
		t.Fatal(err)
	}

	// 默认白名单不含假框架 → 不识别任何路由
	defaultResult, _, err := Analyze(dir)
	if err != nil {
		t.Fatalf("Analyze failed: %v", err)
	}
	if len(defaultResult.HttpRoutes) != 0 {
		t.Fatalf("默认白名单不应识别假框架路由，got %v", defaultResult.HttpRoutes)
	}

	// 配置白名单后识别，且前缀正确拼接
	result, _, err := AnalyzeWithOptions(dir, Options{RouterPkgs: []string{"routerfw.example.com/fw"}})
	if err != nil {
		t.Fatalf("AnalyzeWithOptions failed: %v", err)
	}
	got := map[string]string{} // path → method
	for _, r := range result.HttpRoutes {
		got[r.Path] = r.Method
	}
	want := map[string]string{
		"/api/v1/users": "GET",
		"/api/v1/orders": "POST",
		"/health":        "GET",
		"/x/y":           "GET",
	}
	for path, method := range want {
		if got[path] != method {
			t.Errorf("route %s = %q, want %q (all: %v)", path, got[path], method, got)
		}
	}
	if len(got) != len(want) {
		t.Errorf("unexpected extra routes: %v", got)
	}
}
