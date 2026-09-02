package main

// Octopus Go analyzer: loads a Go workspace with golang.org/x/tools/go/packages
// (the real go/types type checker) and emits the JSON contract consumed by
// code-intelligence's GoProjectAnalyzer (see domain/GoAnalysisOutput.java).
//
// Contract highlights:
//   - Only methods/edges INSIDE the scanned module(s) are emitted; calls into
//     stdlib or third-party dependencies are dropped (mirrors the JVM pipeline,
//     where GraphStoreService's MATCH silently drops edges to absent nodes).
//   - Signatures are the single source of truth, built exclusively from
//     *types.Func so declaration nodes and call edges always agree:
//     <pkgPath[.Type]>.<name>(<paramTypes,>[:<returnTypes,>]
//   - Interface dispatch is fanned out on this side: a call through an
//     interface value emits DIRECT edges to every workspace implementation;
//     when no implementation exists the edge points at the interface method
//     node with callType=INTERFACE.

import (
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"golang.org/x/tools/go/packages"
)

// ── JSON output shapes (mirror Java GoAnalysisOutput records) ──────────────

type AnalyzeResult struct {
	Types   []TypeSpec `json:"types"`
	Methods []FuncNode `json:"methods"`
	Edges   []CallEdge `json:"edges"`
	// HTTP route registrations (net/http mux, gin, echo, chi) detected in
	// function bodies; consumed by code-intelligence as synthetic "HttpRoute"
	// annotations so entry-point queries display the API path.
	HttpRoutes []HttpRoute `json:"httpRoutes"`
}

type TypeSpec struct {
	QualifiedName string   `json:"qualifiedName"` // pkgPath.TypeName
	Name          string   `json:"name"`
	Kind          string   `json:"kind"` // "struct" | "interface"
	PackagePath   string   `json:"packagePath"`
	File          string   `json:"file"`
	LineStart     int      `json:"lineStart"`
	LineEnd       int      `json:"lineEnd"`
	Embedded      string   `json:"embedded"`   // qname of the embedded base struct, "" if none
	Implements    []string `json:"implements"` // workspace interfaces satisfied
	Fields        []Field  `json:"fields"`
}

type Field struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

type FuncNode struct {
	QualifiedClassName string   `json:"qualifiedClassName"` // pkgPath (free func) | pkgPath.Type (method)
	MethodName         string   `json:"methodName"`
	Signature          string   `json:"signature"`
	ParameterTypes     []string `json:"parameterTypes"`
	ReturnTypes        []string `json:"returnTypes"`
	Modifiers          string   `json:"modifiers"` // reserved, always ""
	SourceFilePath     string   `json:"sourceFilePath"`
	SourceCode         string   `json:"sourceCode"`
	LineStart          int      `json:"lineStart"`
	LineEnd            int      `json:"lineEnd"`
}

type CallEdge struct {
	CallerSignature string `json:"callerSignature"`
	CalleeSignature string `json:"calleeSignature"`
	CallType        string `json:"callType"` // DIRECT | INTERFACE
	LineNumber      int    `json:"lineNumber"`
}

type HttpRoute struct {
	Path             string `json:"path"`
	Method           string `json:"method"` // GET/POST/... or "*"
	HandlerSignature string `json:"handlerSignature"`
	LineNumber       int    `json:"lineNumber"`
}

// ── Analysis entry point ───────────────────────────────────────────────────

const (
	maxModuleSearchDepth = 4
)

// Options 自定义分析行为；零值 = 全部默认。
type Options struct {
	// RouterPkgs 路由框架包白名单（框架画像）；为空用内置默认。
	RouterPkgs []string
}

// Analyze walks projectDir for go.mod files (each is one module), type-checks
// every module with the Go toolchain, and produces the graph payload.
func Analyze(projectDir string) (*AnalyzeResult, []string, error) {
	return AnalyzeWithOptions(projectDir, Options{})
}

func AnalyzeWithOptions(projectDir string, opts Options) (*AnalyzeResult, []string, error) {
	moduleDirs, err := findModuleDirs(projectDir)
	if err != nil {
		return nil, nil, err
	}
	if len(moduleDirs) == 0 {
		return nil, nil, fmt.Errorf("no go.mod found under %s", projectDir)
	}

	var warnings []string
	result := &AnalyzeResult{Types: []TypeSpec{}, Methods: []FuncNode{}, Edges: []CallEdge{}, HttpRoutes: []HttpRoute{}}

	// pkgPath → loaded package, shared across modules so cross-module calls resolve.
	workspacePkgs := map[string]*packages.Package{}
	var pkgOrder []string
	fileContents := map[string]string{}

	for _, modDir := range moduleDirs {
		cfg := &packages.Config{
			Mode: packages.NeedName | packages.NeedFiles | packages.NeedSyntax |
				packages.NeedTypes | packages.NeedTypesInfo | packages.NeedDeps |
				packages.NeedImports | packages.NeedModule,
			Dir:   modDir,
			Tests: false,
		}
		pkgs, err := packages.Load(cfg, "./...")
		if err != nil {
			warnings = append(warnings, fmt.Sprintf("module %s: load failed: %v", modDir, err))
			continue
		}
		for _, pkg := range pkgs {
			for _, e := range pkg.Errors {
				warnings = append(warnings, fmt.Sprintf("package %s: %v", pkg.PkgPath, e))
			}
			if pkg.TypesInfo == nil {
				continue
			}
			if _, dup := workspacePkgs[pkg.PkgPath]; !dup {
				workspacePkgs[pkg.PkgPath] = pkg
				pkgOrder = append(pkgOrder, pkg.PkgPath)
			}
			for _, fname := range pkg.GoFiles {
				if _, ok := fileContents[fname]; !ok {
					if src, err := readFileIfExists(fname); err == nil {
						fileContents[fname] = src
					}
				}
			}
		}
	}
	if len(workspacePkgs) == 0 {
		return nil, warnings, fmt.Errorf("no type-checkable Go packages under %s", projectDir)
	}

	a := &analyzer{
		fset:       workspacePkgs[pkgOrder[0]].Fset,
		pkgs:       workspacePkgs,
		files:      fileContents,
		methods:    map[string]*FuncNode{},
		routerPkgs: routerPkgsAsMap(opts.RouterPkgs),
	}
	for _, path := range pkgOrder {
		a.collectPackage(workspacePkgs[path])
	}
	a.collectInterfaceMethods()
	a.computeImplements()
	a.collectEdges()
	a.collectHttpRoutes()

	// Deterministic ordering: stable output regardless of map iteration.
	// Ensure non-nil slices: a workspace without named types / edges would
	// otherwise marshal as "types":null and break the Java consumer.
	result.Types = a.typeSpecs
	if result.Types == nil {
		result.Types = []TypeSpec{}
	}
	result.Edges = a.edges
	if result.Edges == nil {
		result.Edges = []CallEdge{}
	}
	result.HttpRoutes = a.httpRoutes
	if result.HttpRoutes == nil {
		result.HttpRoutes = []HttpRoute{}
	}
	result.Methods = make([]FuncNode, 0, len(a.methods))
	for _, node := range a.methods {
		result.Methods = append(result.Methods, *node)
	}
	sort.Slice(result.Methods, func(i, j int) bool { return result.Methods[i].Signature < result.Methods[j].Signature })
	sort.Slice(result.Edges, func(i, j int) bool {
		if result.Edges[i].CallerSignature != result.Edges[j].CallerSignature {
			return result.Edges[i].CallerSignature < result.Edges[j].CallerSignature
		}
		return result.Edges[i].CalleeSignature < result.Edges[j].CalleeSignature
	})
	return result, warnings, nil
}

// ── Module discovery ───────────────────────────────────────────────────────

func findModuleDirs(root string) ([]string, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	var modules []string
	err = filepath.WalkDir(abs, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // unreadable dirs are skipped, not fatal
		}
		if d.IsDir() {
			name := d.Name()
			if path != abs && (name == "vendor" || name == "testdata" || name == ".git" || name == "node_modules") {
				return filepath.SkipDir
			}
			if depth(abs, path) > maxModuleSearchDepth {
				return filepath.SkipDir
			}
			if _, err := statFile(filepath.Join(path, "go.mod")); err == nil {
				modules = append(modules, path)
				// Nested modules are independent; keep walking siblings but do not
				// descend into a found module's vendor dir (handled above).
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return modules, nil
}

func depth(root, path string) int {
	rel, err := filepath.Rel(root, path)
	if err != nil {
		return 0
	}
	if rel == "." {
		return 0
	}
	return strings.Count(rel, string(filepath.Separator)) + 1
}

func statFile(path string) (os.FileInfo, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if info.IsDir() {
		return nil, fmt.Errorf("%s is a directory", path)
	}
	return info, nil
}

func readFileIfExists(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return string(b), nil
}
