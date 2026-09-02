package main

// HTTP route registration detection. Recognizes the common registration
// shapes and emits HttpRoute entries (path + HTTP method + handler signature)
// that code-intelligence stores as synthetic "HttpRoute" annotations, so the
// entry-point UI displays "GET /api/users" instead of a bare root method.
//
// Recognized patterns (handler must resolve to a workspace function):
//
//	net/http:   http.HandleFunc("/p", h), mux.HandleFunc("/p", h),
//	            mux.Handle("/p", hFn)          (Go 1.22 "GET /p" patterns too)
//	gin:        engine/group.GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS("/p", h)
//	echo:       same verb methods on *echo.Echo / *echo.Group
//	chi:        r.Get|Post|...("/p", h)
import (
	"go/ast"
	"go/token"
	"go/types"
	"strings"

	"golang.org/x/tools/go/packages"
)

// defaultRouterPkgs 内置的路由框架包白名单；可通过 --routers /
// OCTOPUS_GO_ROUTER_PKGS 覆盖（框架画像：Go 侧唯一的框架知识就是这张表）。
var defaultRouterPkgs = []string{
	"github.com/gin-gonic/gin",
	"github.com/labstack/echo/v4",
	"github.com/labstack/echo",
	"github.com/go-chi/chi/v5",
	"github.com/go-chi/chi",
	"github.com/gorilla/mux",
}

// routerPkgsAsMap 空列表回退到内置默认。
func routerPkgsAsMap(pkgs []string) map[string]bool {
	if len(pkgs) == 0 {
		pkgs = defaultRouterPkgs
	}
	m := make(map[string]bool, len(pkgs))
	for _, p := range pkgs {
		if p = strings.TrimSpace(p); p != "" {
			m[p] = true
		}
	}
	return m
}

var httpVerbs = map[string]string{
	"GET": "GET", "POST": "POST", "PUT": "PUT", "DELETE": "DELETE",
	"PATCH": "PATCH", "HEAD": "HEAD", "OPTIONS": "OPTIONS", "ANY": "*",
}

// chi uses lower-cased verbs (r.Get); gin/echo use upper case.
var chiVerbs = map[string]string{
	"Get": "GET", "Post": "POST", "Put": "PUT", "Delete": "DELETE",
	"Patch": "PATCH", "Head": "HEAD", "Options": "OPTIONS",
}

func (a *analyzer) collectHttpRoutes() {
	for _, pkg := range a.pkgs {
		for _, file := range pkg.Syntax {
			for _, decl := range file.Decls {
				fnDecl, ok := decl.(*ast.FuncDecl)
				if !ok || fnDecl.Body == nil {
					continue
				}
				// 先收集本函数内的 Group 赋值（路由组前缀数据流），再识别注册调用
				groups := collectGroupAssignments(fnDecl.Body)
				ast.Inspect(fnDecl.Body, func(n ast.Node) bool {
					if call, ok := n.(*ast.CallExpr); ok {
						a.emitRoute(pkg, call, groups)
					}
					return true
				})
			}
		}
	}
}

// groupAssignments: 变量名 → Group(...) 调用表达式（路由组数据流）。
type groupAssignments map[string]ast.Expr

// collectGroupAssignments 收集 `x := y.Group("/p")` 形态的赋值，
// 链式与乱序声明由 resolveRoutePrefix 的递归解析处理。
func collectGroupAssignments(body ast.Node) groupAssignments {
	assignments := groupAssignments{}
	ast.Inspect(body, func(n ast.Node) bool {
		assign, ok := n.(*ast.AssignStmt)
		if !ok || len(assign.Lhs) != 1 || len(assign.Rhs) != 1 {
			return true
		}
		ident, ok := assign.Lhs[0].(*ast.Ident)
		if !ok {
			return true
		}
		if call, ok := assign.Rhs[0].(*ast.CallExpr); ok && isGroupCall(call) {
			assignments[ident.Name] = call
		}
		return true
	})
	return assignments
}

func isGroupCall(call *ast.CallExpr) bool {
	sel, ok := call.Fun.(*ast.SelectorExpr)
	return ok && sel.Sel != nil && sel.Sel.Name == "Group" && len(call.Args) >= 1
}

// resolveRoutePrefix 递归解析 receiver 表达式上的路由组前缀：
//
//	api := r.Group("/api"); v1 := api.Group("/v1"); v1.GET("/users")
//	→ "/api/v1"；链式 r.Group("/x").GET("/y") → "/x"；带环检测与深度上限。
func resolveRoutePrefix(expr ast.Expr, groups groupAssignments, depth int) string {
	if depth > 8 {
		return ""
	}
	switch e := expr.(type) {
	case *ast.Ident:
		expr, ok := groups[e.Name]
		if !ok {
			return "" // 根引擎（gin.New() 等）或未知来源
		}
		call, isCall := expr.(*ast.CallExpr)
		if !isCall {
			return ""
		}
		return resolveRoutePrefixCall(call, groups, depth)
	case *ast.CallExpr:
		return resolveRoutePrefixCall(e, groups, depth)
	}
	return ""
}

func resolveRoutePrefixCall(call *ast.CallExpr, groups groupAssignments, depth int) string {
	if !isGroupCall(call) {
		return ""
	}
	sel := call.Fun.(*ast.SelectorExpr)
	parent := resolveRoutePrefix(sel.X, groups, depth+1)
	literal, ok := stringLiteral(call.Args[0])
	if !ok {
		return parent
	}
	return joinRoutePath(parent, literal)
}

func joinRoutePath(prefix, path string) string {
	if prefix == "" {
		return path
	}
	if path == "" {
		return prefix
	}
	p := strings.TrimSuffix(prefix, "/")
	m := strings.TrimPrefix(path, "/")
	return p + "/" + m
}

func (a *analyzer) emitRoute(pkg *packages.Package, call *ast.CallExpr, groups groupAssignments) {
	sel, ok := call.Fun.(*ast.SelectorExpr)
	if !ok || len(call.Args) < 2 {
		return
	}
	name := sel.Sel.Name
	line := a.fset.PositionFor(call.Lparen, false).Line
	// 路由组前缀（gin/echo 的 Group 嵌套），无则为空
	routePrefix := resolveRoutePrefix(sel.X, groups, 0)

	var path, handlerArg ast.Expr
	var method string
	switch name {
	case "HandleFunc", "Handle":
		// http.HandleFunc(path, handler) / mux.HandleFunc(path, handler).
		// Accept any receiver: net/http package, *http.ServeMux, or a project
		// mux wrapper — the handler-argument resolution filters noise.
		path, handlerArg = call.Args[0], call.Args[len(call.Args)-1]
	case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "ANY":
		if !a.isFrameworkRouter(pkg, sel) {
			return
		}
		method, path, handlerArg = name, call.Args[0], call.Args[1]
	case "Get", "Post", "Put", "Delete", "Patch", "Head", "Options":
		if !a.isFrameworkRouter(pkg, sel) {
			return
		}
		method, path, handlerArg = chiVerbs[name], call.Args[0], call.Args[1]
	default:
		return
	}

	pathLit, ok := stringLiteral(path)
	if !ok {
		return
	}
	pathLit = joinRoutePath(routePrefix, pathLit)
	// Go 1.22 ServeMux patterns may be "GET /api/x" (method + space + path).
	if method == "" {
		if idx := strings.Index(pathLit, " "); idx > 0 {
			if verb, ok := httpVerbs[pathLit[:idx]]; ok {
				method = verb
				pathLit = strings.TrimSpace(pathLit[idx+1:])
			}
		}
	}
	if method == "" {
		method = "*"
	}

	handlerSig := a.handlerSignature(pkg, handlerArg)
	if handlerSig == "" {
		return
	}
	a.httpRoutes = append(a.httpRoutes, HttpRoute{
		Path:             pathLit,
		Method:           method,
		HandlerSignature: handlerSig,
		LineNumber:       line,
	})
}

// handlerSignature resolves a handler argument (func value, e.g. `handleUsers`
// or `pkg.Handle`) to its workspace signature; returns "" when unresolvable
// (func literals, http.Handler interface values, non-workspace funcs).
func (a *analyzer) handlerSignature(pkg *packages.Package, expr ast.Expr) string {
	ident, ok := expr.(*ast.Ident)
	if !ok {
		return ""
	}
	obj, ok := pkg.TypesInfo.Uses[ident]
	if !ok {
		return ""
	}
	fn, ok := obj.(*types.Func)
	if !ok || !a.isWorkspaceFunc(fn) {
		return ""
	}
	return signatureOf(fn.Origin())
}

func (a *analyzer) isFrameworkRouter(pkg *packages.Package, sel *ast.SelectorExpr) bool {
	s, ok := pkg.TypesInfo.Selections[sel]
	if !ok {
		// Package-level function call (e.g. http.HandleFunc) — not a router method.
		return false
	}
	recv := s.Recv()
	if types.IsInterface(recv) {
		// chi.Router-style interfaces: accept when the method name is a known verb.
		return true
	}
	named := receiverNamed(recv)
	if named == nil || named.Obj().Pkg() == nil {
		return false
	}
	return a.routerPkgs[named.Obj().Pkg().Path()]
}

func stringLiteral(expr ast.Expr) (string, bool) {
	lit, ok := expr.(*ast.BasicLit)
	if !ok || lit.Kind != token.STRING {
		return "", false
	}
	s := lit.Value
	if len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		return strings.ReplaceAll(s[1:len(s)-1], `\"`, `"`), true
	}
	return "", false
}
