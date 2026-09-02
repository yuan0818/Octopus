package api

import (
	"context"
	"fmt"
	"net/http"

	"demo.example.com/demo/service"
	"demo.example.com/demo/store"
)

// RegisterRoutes wires handlers onto the mux (net/http registration shape).
func RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/users", handleUsers)
	// Go 1.22 method-prefixed pattern.
	mux.HandleFunc("POST /api/orders", handleOrders)
}

// handleUsers is an HTTP entry point.
func handleUsers(w http.ResponseWriter, r *http.Request) {
	svc := &service.Service{Store: &store.DbStore{}}
	u, err := svc.GetUser(r.Context(), r.URL.Query().Get("id"))
	if err != nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	_, _ = w.Write([]byte(u.Name))
}

// handleOrders is an HTTP entry point registered with a method pattern.
func handleOrders(w http.ResponseWriter, r *http.Request) {
	fmt.Fprintln(w, "ok")
}

// HandleRequest is the entry-point free function of the demo module.
func HandleRequest(ctx context.Context, id string) error {
	svc := &service.Service{Store: &store.DbStore{}}
	u, err := svc.GetUser(ctx, id)
	if err != nil {
		return fmt.Errorf("get user: %w", err)
	}

	// goroutine and defer calls must appear as DIRECT edges.
	go svc.GetUser(ctx, id)
	defer cleanup()

	_ = service.Format(u)
	return nil
}

// cleanup is invoked via defer in HandleRequest.
func cleanup() {}
