# MCP Modules

The backend exposes four separate MCP capabilities:

```text
integration.mcp.blog    -> /api/mcp          -> local blog authorization and article CRUD
integration.mcp.csdn    -> /api/mcp/csdn     -> CSDN QR authorization and sync of owned posts
integration.mcp.dual    -> /api/mcp/dual     -> composite publish_to_both workflow
integration.mcp.juejin  -> /api/mcp/juejin   -> Juejin account status and owned-post publish
```

The first two are complete, independent platform capabilities. The CSDN module
owns its authorization, encrypted credentials, Selenium handoff, image handling,
Markdown conversion, HTTP publisher, and sync application service. The dual
endpoint is a thin application-level composition: it creates and publishes the
local article, then asks the CSDN application service to synchronize the same
owned post. The Juejin module mirrors the CSDN module's shape: per-user
encrypted credentials, an HTTP publisher over juejin.cn content_api
(draft create + publish), and a sync application service; its QR authorization
handoff arrives in a later change. Neither platform module reads the other's
credentials. Client authorization is shared OAuth infrastructure only; each
request is mapped to the consenting Xander Lab user and the business flows
remain separate.
