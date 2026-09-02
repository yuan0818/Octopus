package main

import (
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"strings"

	"golang.org/x/tools/go/packages"
)

// analyzer carries per-run state across the collection passes.
type analyzer struct {
	fset       *token.FileSet
	pkgs       map[string]*packages.Package
	files      map[string]string // compiled file path → source text
	methods    map[string]*FuncNode
	typeSpecs  []TypeSpec
	edges      []CallEdge
	httpRoutes []HttpRoute
	// routerPkgs 路由框架包白名单（框架画像，可配置）。
	routerPkgs map[string]bool
	// namedTypes are all workspace named types, used for interface fan-out.
	namedTypes []*types.Named
	// interfaces are the subset of namedTypes that are interface types.
	interfaces []*types.Named
}

// collectPackage emits TypeSpecs and FuncNodes for one loaded package.
func (a *analyzer) collectPackage(pkg *packages.Package) {
	for _, file := range pkg.Syntax {
		for _, decl := range file.Decls {
			switch d := decl.(type) {
			case *ast.FuncDecl:
				if node, ok := a.funcNodeFromDecl(pkg, file, d); ok {
					a.methods[node.Signature] = &node
				}
			case *ast.GenDecl:
				if d.Tok.String() == "type" {
					a.collectTypeDecl(pkg, file, d)
				}
			}
		}
	}
}

// collectTypeDecl emits one TypeSpec per named struct/interface type.
func (a *analyzer) collectTypeDecl(pkg *packages.Package, file *ast.File, decl *ast.GenDecl) {
	for _, astSpec := range decl.Specs {
		ts, ok := astSpec.(*ast.TypeSpec)
		if !ok {
			continue
		}
		obj, ok := pkg.TypesInfo.Defs[ts.Name]
		if !ok {
			continue
		}
		named, ok := obj.Type().(*types.Named)
		if !ok {
			continue
		}
		a.namedTypes = append(a.namedTypes, named)

		spec := TypeSpec{
			QualifiedName: named.Obj().Pkg().Path() + "." + named.Obj().Name(),
			Name:          named.Obj().Name(),
			PackagePath:   named.Obj().Pkg().Path(),
			File:          a.fileName(file),
			LineStart:     a.fset.PositionFor(ts.Pos(), false).Line,
			LineEnd:       a.fset.PositionFor(ts.End(), false).Line,
			Implements:    []string{},
			Fields:        []Field{},
		}

		switch u := named.Underlying().(type) {
		case *types.Interface:
			spec.Kind = "interface"
			a.interfaces = append(a.interfaces, named)
		case *types.Struct:
			spec.Kind = "struct"
			for i := 0; i < u.NumFields(); i++ {
				f := u.Field(i)
				spec.Fields = append(spec.Fields, Field{Name: f.Name(), Type: typeString(f.Type())})
				if f.Anonymous() && f.Embedded() && spec.Embedded == "" && strings.TrimPrefix(typeString(f.Type()), "*") != "" {
					// Only the first embedded field is recorded (single "parent").
					if base := receiverNamed(f.Type()); base != nil {
						spec.Embedded = base.Obj().Pkg().Path() + "." + base.Obj().Name()
					}
				}
			}
		default:
			// Named basic/slice/map types: no class_info equivalent, but keep
			// them in namedTypes so they can still implement interfaces.
			continue
		}
		a.typeSpecs = append(a.typeSpecs, spec)
	}
}

// funcNodeFromDecl builds the FuncNode for a function or method declaration.
func (a *analyzer) funcNodeFromDecl(pkg *packages.Package, file *ast.File, d *ast.FuncDecl) (FuncNode, bool) {
	obj, ok := pkg.TypesInfo.Defs[d.Name]
	if !ok {
		return FuncNode{}, false
	}
	fn, ok := obj.(*types.Func)
	if !ok {
		return FuncNode{}, false
	}
	fn = fn.Origin()

	sig := fn.Type().(*types.Signature)
	node := FuncNode{
		QualifiedClassName: qualifiedClassNameOf(fn),
		MethodName:         fn.Name(),
		Signature:          signatureOf(fn),
		ParameterTypes:     paramTypeStrings(sig),
		ReturnTypes:        returnTypeStrings(sig),
		Modifiers:          "",
		SourceFilePath:     a.fileName(file),
		LineStart:          a.fset.PositionFor(d.Pos(), false).Line,
		LineEnd:            a.fset.PositionFor(d.End(), false).Line,
	}
	node.SourceCode = a.sourceSlice(d.Pos(), d.End())
	return node, true
}

// collectInterfaceMethods synthesizes FuncNodes for interface methods so that
// INTERFACE edges (dispatch with no workspace implementation) have a target.
func (a *analyzer) collectInterfaceMethods() {
	for _, iface := range a.interfaces {
		ms := types.NewMethodSet(iface)
		for i := 0; i < ms.Len(); i++ {
			fn, ok := ms.At(i).Obj().(*types.Func)
			if !ok {
				continue
			}
			sig := signatureOf(fn)
			if _, exists := a.methods[sig]; exists {
				continue
			}
			// Interface methods have no body; line info stays 0 (the Java
			// reconstruct side treats 0 as "no source to extract").
			node := FuncNode{
				QualifiedClassName: qualifiedClassNameOf(fn),
				MethodName:         fn.Name(),
				Signature:          sig,
				ParameterTypes:     paramTypeStrings(fn.Type().(*types.Signature)),
				ReturnTypes:        returnTypeStrings(fn.Type().(*types.Signature)),
				Modifiers:          "",
				SourceFilePath:     a.typeFile(qualifiedClassNameOf(fn)),
			}
			a.methods[sig] = &node
		}
	}
}

// computeImplements fills TypeSpec.Implements: for every workspace type,
// which workspace interfaces it satisfies (value or pointer receiver).
func (a *analyzer) computeImplements() {
	qn := func(n *types.Named) string { return n.Obj().Pkg().Path() + "." + n.Obj().Name() }
	for i := range a.typeSpecs {
		named := a.findNamed(a.typeSpecs[i].QualifiedName)
		if named == nil {
			continue
		}
		for _, ifaceNamed := range a.interfaces {
			iface, ok := ifaceNamed.Underlying().(*types.Interface)
			if !ok {
				continue
			}
			if types.Implements(named, iface) || types.Implements(types.NewPointer(named), iface) {
				a.typeSpecs[i].Implements = append(a.typeSpecs[i].Implements, qn(ifaceNamed))
			}
		}
	}
}

func (a *analyzer) findNamed(qname string) *types.Named {
	for _, n := range a.namedTypes {
		if n.Obj().Pkg().Path()+"."+n.Obj().Name() == qname {
			return n
		}
	}
	return nil
}

func (a *analyzer) typeFile(qname string) string {
	if n := a.findNamed(qname); n != nil {
		return a.fset.PositionFor(n.Obj().Pos(), false).Filename
	}
	return ""
}

// ── Call edge extraction ───────────────────────────────────────────────────

// collectEdges walks every function body and emits DIRECT/INTERFACE edges for
// workspace-internal calls. goroutine `go f()` and `defer f()` are plain
// CallExprs in the AST and are captured as DIRECT edges for free.
func (a *analyzer) collectEdges() {
	for _, pkg := range a.pkgs {
		for _, file := range pkg.Syntax {
			for _, decl := range file.Decls {
				fnDecl, ok := decl.(*ast.FuncDecl)
				if !ok || fnDecl.Body == nil {
					continue
				}
				obj, ok := pkg.TypesInfo.Defs[fnDecl.Name]
				if !ok {
					continue
				}
				caller, ok := obj.(*types.Func)
				if !ok {
					continue
				}
				callerSig := signatureOf(caller.Origin())
				if _, known := a.methods[callerSig]; !known {
					continue
				}
				ast.Inspect(fnDecl.Body, func(n ast.Node) bool {
					if call, ok := n.(*ast.CallExpr); ok {
						a.emitCall(pkg, call, callerSig)
					}
					return true
				})
			}
		}
	}
}

func (a *analyzer) emitCall(pkg *packages.Package, call *ast.CallExpr, callerSig string) {
	line := a.fset.PositionFor(call.Lparen, false).Line

	switch fun := call.Fun.(type) {
	case *ast.SelectorExpr:
		if sel, ok := pkg.TypesInfo.Selections[fun]; ok && sel.Kind() == types.MethodVal {
			recv := sel.Recv()
			fn := sel.Obj().(*types.Func).Origin()
			if types.IsInterface(recv) {
				a.emitInterfaceDispatch(callerSig, fn, line)
				return
			}
			if a.isWorkspaceFunc(fn) {
				a.addEdge(callerSig, signatureOf(fn), "DIRECT", line)
			}
			return
		}
		// pkg.Func(...) — qualified function call: resolve the selector ident.
		if obj, ok := pkg.TypesInfo.Uses[fun.Sel]; ok {
			if fn, ok := obj.(*types.Func); ok && a.isWorkspaceFunc(fn) {
				a.addEdge(callerSig, signatureOf(fn.Origin()), "DIRECT", line)
			}
		}
	case *ast.Ident:
		if obj, ok := pkg.TypesInfo.Uses[fun]; ok {
			if fn, ok := obj.(*types.Func); ok && a.isWorkspaceFunc(fn) {
				a.addEdge(callerSig, signatureOf(fn.Origin()), "DIRECT", line)
			}
		}
	case *ast.IndexExpr:
		// Generic instantiation f[T](...): unwrap to the base expression.
		a.emitCall(pkg, &ast.CallExpr{Fun: fun.X, Lparen: call.Lparen}, callerSig)
	case *ast.IndexListExpr:
		// Generic instantiation f[T1, T2](...): same unwrapping.
		a.emitCall(pkg, &ast.CallExpr{Fun: fun.X, Lparen: call.Lparen}, callerSig)
	}
	// Conversions (types.TypeName), builtins (make/len/append) resolve to
	// non-*types.Func objects and are intentionally ignored.
}

// isWorkspaceFunc reports whether fn is declared in one of the scanned modules.
// Stdlib and third-party callees are dropped so the emitted graph only contains
// resolvable workspace nodes (mirrors the JVM pipeline semantics).
func (a *analyzer) isWorkspaceFunc(fn *types.Func) bool {
	fn = fn.Origin()
	pkg := fn.Pkg()
	return pkg != nil && a.pkgs[pkg.Path()] != nil
}

// emitInterfaceDispatch fans a call through an interface value out to every
// workspace implementation; with no implementation it keeps one INTERFACE edge
// to the interface method node.
func (a *analyzer) emitInterfaceDispatch(callerSig string, method *types.Func, line int) {
	methodName := method.Name()
	fanned := false
	for _, named := range a.namedTypes {
		if types.IsInterface(named) {
			continue
		}
		for _, recv := range []types.Type{types.NewPointer(named), named} {
			sel := types.NewMethodSet(recv).Lookup(named.Obj().Pkg(), methodName)
			if sel == nil {
				continue
			}
			if implFn, ok := sel.Obj().(*types.Func); ok {
				a.addEdge(callerSig, signatureOf(implFn.Origin()), "DIRECT", line)
				fanned = true
			}
			break
		}
	}
	if !fanned {
		a.addEdge(callerSig, signatureOf(method.Origin()), "INTERFACE", line)
	}
}

func (a *analyzer) addEdge(callerSig, calleeSig, callType string, line int) {
	a.edges = append(a.edges, CallEdge{
		CallerSignature: callerSig,
		CalleeSignature: calleeSig,
		CallType:        callType,
		LineNumber:      line,
	})
}

// ── Signature helpers (single source of truth for the contract) ────────────

// qualifiedClassNameOf returns pkgPath for free functions and pkgPath.Type for
// methods (including interface methods — their receiver is the named interface).
func qualifiedClassNameOf(fn *types.Func) string {
	pkgPath := ""
	if fn.Pkg() != nil {
		pkgPath = fn.Pkg().Path()
	}
	sig := fn.Type().(*types.Signature)
	if sig.Recv() != nil {
		if named := receiverNamed(sig.Recv().Type()); named != nil {
			return pkgPath + "." + named.Obj().Name()
		}
	}
	return pkgPath
}

// receiverNamed strips pointer wrappers off a receiver type to find the
// underlying *types.Named.
func receiverNamed(t types.Type) *types.Named {
	for {
		switch v := t.(type) {
		case *types.Pointer:
			t = v.Elem()
		case *types.Named:
			return v
		default:
			return nil
		}
	}
}

// signatureOf builds <qualifiedClassName>.<name>(<params>):<results> from the
// *types.Func. Declaration nodes and call edges both use this function, so
// the strings always match.
func signatureOf(fn *types.Func) string {
	sig := fn.Type().(*types.Signature)
	return fmt.Sprintf("%s.%s(%s):%s",
		qualifiedClassNameOf(fn), fn.Name(),
		strings.Join(paramTypeStrings(sig), ","),
		strings.Join(returnTypeStrings(sig), ","))
}

func paramTypeStrings(sig *types.Signature) []string {
	out := make([]string, 0, sig.Params().Len())
	for i := 0; i < sig.Params().Len(); i++ {
		out = append(out, typeString(sig.Params().At(i).Type()))
	}
	return out
}

func returnTypeStrings(sig *types.Signature) []string {
	out := make([]string, 0, sig.Results().Len())
	for i := 0; i < sig.Results().Len(); i++ {
		out = append(out, typeString(sig.Results().At(i).Type()))
	}
	return out
}

// typeString prints a Go type using package short names (like source code).
func typeString(t types.Type) string {
	return types.TypeString(t, func(p *types.Package) string {
		if p == nil {
			return ""
		}
		return p.Name()
	})
}

// ── File helpers ───────────────────────────────────────────────────────────

func (a *analyzer) fileName(file *ast.File) string {
	return a.fset.PositionFor(file.Pos(), false).Filename
}

// sourceSlice extracts source text between two positions in the same file.
func (a *analyzer) sourceSlice(start, end token.Pos) string {
	p := a.fset.PositionFor(start, false)
	q := a.fset.PositionFor(end, false)
	if p.Filename != q.Filename {
		return ""
	}
	src, ok := a.files[p.Filename]
	if !ok {
		return ""
	}
	if p.Offset < 0 || q.Offset > len(src) || p.Offset > q.Offset {
		return ""
	}
	return src[p.Offset:q.Offset]
}
