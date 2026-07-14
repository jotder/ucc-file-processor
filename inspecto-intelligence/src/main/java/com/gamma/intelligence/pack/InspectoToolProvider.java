package com.gamma.intelligence.pack;

import com.eoiagent.app.McpServerRef;
import com.eoiagent.app.ToolProvider;
import com.eoiagent.tool.Tool;
import com.gamma.service.CollectorService;

import java.util.List;

/** Exposes the P0 read tool belt to the core {@code ToolRegistry}. No MCP servers yet (Feature.MCP_TOOLS is off). */
final class InspectoToolProvider implements ToolProvider {

    private final CollectorService service;

    InspectoToolProvider(CollectorService service) {
        this.service = service;
    }

    @Override
    public List<Tool> tools() {
        return InspectoTools.tools(service);
    }

    @Override
    public List<McpServerRef> mcpServers() {
        return List.of();
    }
}
