// Isolated entry point for the unmodified, source-locked production Hub.
package main

import (
 "bufio"
 "fmt"
 "net"
 "net/http"
 "os"
 "github.com/router-for-me/CLIProxyAPI/v8/internal/wstunnel"
)

func main() {
 key := os.Getenv("CPA_TUNNEL_FIXTURE_KEY")
 if key == "" { panic("fixture key required") }
 hub := wstunnel.NewHub(wstunnel.Options{})
 defer hub.Close()
 hub.UpdateAllowedAPIKeys([]string{key})
 listener, err := net.Listen("tcp", "127.0.0.1:0")
 if err != nil { panic("fixture listener failed") }
 mux := http.NewServeMux()
 mux.Handle("/v1/tunnel", hub)
 server := &http.Server{Handler: mux}
 go func() { _ = server.Serve(listener) }()
 fmt.Println("http://" + listener.Addr().String())
 scanner := bufio.NewScanner(os.Stdin)
 for scanner.Scan() {
  if scanner.Text() == "revoke" { hub.UpdateAllowedAPIKeys(nil) }
 }
 _ = server.Close()
}
