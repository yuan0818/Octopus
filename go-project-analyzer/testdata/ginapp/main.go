package main

import (
	"net/http"

	"routerfw.example.com/fw"
)

func main() {
	r := fw.New()
	api := r.Group("/api")
	v1 := api.Group("/v1")
	v1.GET("/users", listUsers)     // → /api/v1/users
	v1.POST("/orders", createOrder) // → /api/v1/orders
	r.GET("/health", health)        // → /health（无前缀）
	r.Group("/x").GET("/y", health) // → /x/y（链式）
	_ = http.StatusOK
}

func listUsers(w http.ResponseWriter, r *http.Request)  {}
func createOrder(w http.ResponseWriter, r *http.Request) {}
func health(w http.ResponseWriter, r *http.Request)     {}
