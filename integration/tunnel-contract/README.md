# Real tunnel service contract

Runs the unmodified production `internal/wstunnel` Go package from CLIProxyAPI and the desktop `CliProxyTunnelServerService` + `WebServerService`. All copied source bytes and Go module metadata are checked against `source-lock.json`. Business RPC is an explicit fixture; no Electron bootstrap, personal config, plugins, or model runtime is loaded.

## Host

```sh
node scripts/test-tunnel-contract.mjs
```

Full host checks, including both direct and tunnel contracts:

```sh
node scripts/test-tunnel-contract.mjs node scripts/test-desktop-contract.mjs ./scripts/verify.sh
```

A first build needs the Go toolchain/modules from the locked `go.mod`. `GOPROXY=https://goproxy.cn` can be supplied for an unavailable default proxy; normal `go.sum` verification remains enabled. No persistent Go configuration is changed. The isolated Go source/build cache lives under ignored `.local/tunnel-contract`; the two source repositories are read-only.

## Device

Start the API37 emulator, build/install the current app and test APKs, then run:

```sh
bash scripts/test-tunnel-device-contract.sh
```

The script only operates on `emulator-5580`, grants its API37 LAN permission, allocates/removes its own `adb reverse` mapping, and runs one instrumentation test. It neither clears app data nor launches an Activity. Ephemeral fixture keys/passwords are passed transiently as process/instrumentation arguments; raw instrumentation output is suppressed. The gateway listens only on host loopback and is shut down afterwards.

Assertions cover real discovery, invalid gateway key, desktop password rejection/login, a large Unicode RPC echo, event forwarding, desktop offline/online recovery with a new RPC connection, and gateway API-key revocation. Synthetic `fixture.*` RPC handlers do not constitute Agent, project/file, public-network, reverse-proxy, or TLS deployment validation. Ordinary test runs skip this opt-in case when the source fixture is absent; skip is not success.
