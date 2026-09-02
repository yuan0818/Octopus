package main

// HTTP server mirroring the jvm-project-analyzer AnalysisHttpServer contract:
//   GET  /health   → {"status":"ok","uptime":<seconds>}
//   POST /analyze  → {"projectDir":"..."} / {"success":true,"data":{...},"warnings":[...]}
//   POST /shutdown → graceful exit
//
// Analysis runs single-flight (one analysis at a time), matching the PSI
// service's concurrency model.

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

type Server struct {
	port       int
	routerPkgs []string // 路由框架包白名单（--routers / 环境变量），空 = 内置默认
	startedAt  time.Time
	mu         sync.Mutex // single-flight analysis guard
	shutdown   atomic.Bool
}

func NewServer(port int, routerPkgs []string) *Server {
	return &Server{port: port, routerPkgs: routerPkgs, startedAt: time.Now()}
}

func (s *Server) Start() error {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/analyze", s.handleAnalyze)
	mux.HandleFunc("/shutdown", s.handleShutdown)
	addr := fmt.Sprintf(":%d", s.port)
	return http.ListenAndServe(addr, mux)
}

func (s *Server) IsShutdown() bool { return s.shutdown.Load() }

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": map[bool]string{true: "shutting_down", false: "ok"}[s.IsShutdown()],
		"uptime": int(time.Since(s.startedAt).Seconds()),
	})
}

func (s *Server) handleAnalyze(w http.ResponseWriter, r *http.Request) {
	if s.IsShutdown() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"success": false,
			"error":   "service is shutting down",
		})
		return
	}

	var req struct {
		ProjectDir string `json:"projectDir"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"success": false,
			"error":   "invalid JSON body: " + err.Error(),
		})
		return
	}
	if req.ProjectDir == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"success": false,
			"error":   "Missing required field: projectDir",
		})
		return
	}
	if info, err := os.Stat(req.ProjectDir); err != nil || !info.IsDir() {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"success": false,
			"error":   fmt.Sprintf("Project directory does not exist: %s", req.ProjectDir),
		})
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	result, warnings, err := AnalyzeWithOptions(req.ProjectDir, Options{RouterPkgs: s.routerPkgs})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"success": false,
			"error":   err.Error(),
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"success":  true,
		"data":     result,
		"warnings": warnings,
	})
}

func (s *Server) handleShutdown(w http.ResponseWriter, _ *http.Request) {
	s.shutdown.Store(true)
	writeJSON(w, http.StatusOK, map[string]any{"message": "shutting down"})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
