package fw

import "net/http"

// Engine 模拟 gin.Engine 的路由注册形状。
type Engine struct{ prefix string }

func New() *Engine { return &Engine{} }

func (e *Engine) Group(prefix string) *RouterGroup {
	return &RouterGroup{prefix: prefix}
}

func (e *Engine) GET(path string, h http.HandlerFunc) {}

// RouterGroup 模拟 gin.RouterGroup：可继续嵌套 Group 与 verb 注册。
type RouterGroup struct{ prefix string }

func (g *RouterGroup) Group(prefix string) *RouterGroup {
	return &RouterGroup{prefix: g.prefix + prefix}
}

func (g *RouterGroup) GET(path string, h http.HandlerFunc)  {}
func (g *RouterGroup) POST(path string, h http.HandlerFunc) {}
