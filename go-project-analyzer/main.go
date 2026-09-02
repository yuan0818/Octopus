package main

// Octopus Go analyzer service.
//
// Usage:
//
//	go-analyzer serve [--port 8378]
//
// Mirrors jvm-project-analyzer's service mode: a long-running HTTP analyzer
// consumed by code-intelligence's GoAnalyzerExecutor.

import (
	"flag"
	"fmt"
	"os"
	"strings"
	"time"
)

func splitCommaList(s string) []string {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	out := parts[:0]
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func main() {
	serve := flag.NewFlagSet("serve", flag.ExitOnError)
	port := serve.Int("port", 8378, "HTTP listen port")
	routers := serve.String("routers", "",
		"路由框架包白名单（逗号分隔，框架画像）；为空时取 OCTOPUS_GO_ROUTER_PKGS 或内置默认")

	if len(os.Args) < 2 || os.Args[1] != "serve" {
		fmt.Fprintln(os.Stderr, "usage: go-analyzer serve [--port N] [--routers pkg1,pkg2]")
		os.Exit(2)
	}
	if err := serve.Parse(os.Args[2:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}

	routerPkgs := *routers
	if routerPkgs == "" {
		routerPkgs = os.Getenv("OCTOPUS_GO_ROUTER_PKGS")
	}

	srv := NewServer(*port, splitCommaList(routerPkgs))
	fmt.Printf("octopus go-project-analyzer listening on :%d\n", *port)

	// HTTP server runs in the foreground; poll the shutdown flag so the
	// process exits shortly after /shutdown is called.
	errCh := make(chan error, 1)
	go func() { errCh <- srv.Start() }()

	for {
		if srv.IsShutdown() {
			fmt.Println("shutdown requested, exiting")
			os.Exit(0)
		}
		select {
		case err := <-errCh:
			fmt.Fprintln(os.Stderr, "server error:", err)
			os.Exit(1)
		case <-time.After(500 * time.Millisecond):
		}
	}
}
